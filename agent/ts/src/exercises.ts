// Exercise bodies. Every `// Module NN, Exercise N: Title` marker labels a function attendees implement.
// Unmarked functions at the bottom of the file are helpers — scaffolding, not exercises.

import { z } from "zod";
import * as fs from "fs";
import * as path from "path";
import { execFileSync, execSync, spawnSync, type ExecException } from "child_process";
import {
  ReadFileParams,
  ShellParams,
  ListFilesParams,
  EditFileParams,
  WebFetchParams,
  MessageUserParams,
  AskUserParams,
  formatToolForPrompt,
} from "./tools.js";
import type {
  Tool,
  ToolName,
  ToolResult,
  SkillInfo,
  CommandPrompt,
  ContextUsage,
  RawToolCall,
  ValidatedToolCall,
  PermissionResult,
  AuditEvent,
  CheckpointInfo,
} from "./tools.js";
import { defaultConfig } from "./config.js";
import type { AgentConfig } from "./config.js";
import type { ChatProvider, Conversation, Message, ParseResult } from "./conversation.js";
import { conversationToMessages, addTurn } from "./conversation.js";
import type { ToolDecorator, ToolInvocation, AgentState } from "./decorators.js";
import { snapshotAgentState, allow, deny } from "./decorators.js";
import {
  readTextFileIfExists,
  parseFrontmatter,
  loadSavedSessionFile,
  truncateSummaryText,
  sessionFilePath,
} from "./session.js";
import type { TerminalOutput } from "./terminal.js";

// ═════════════════════════════════════════════════════════════════════════════
// Module 01 — Foundations: agent loop, tools, system prompt, evaluation
// ═════════════════════════════════════════════════════════════════════════════

// ---------------------------------------------------------------------------
// Module 01, Exercise 1: Build the system prompt
// ---------------------------------------------------------------------------

// Implementation: Load the prompt template and render enabled tool descriptions and schemas.
// Failure mode: Missing or vague affordances make the model guess tool names and arguments.
// Agentic coding lesson: Tool schemas are delegation contracts, not incidental documentation.

const TTY_INTERACTIVE =
  "You are attached to an interactive terminal. The user is present and can answer "
  + "follow-up questions, so ask_user is a real option when a decision is genuinely theirs.";

const TTY_NON_INTERACTIVE =
  "You are running non-interactively. Nobody can answer a follow-up question mid-task, "
  + "so state your assumptions and finish the work rather than waiting on input.";

/**
 * Builds the system prompt by loading the template from `system-prompt.txt`
 * and injecting tool descriptions with JSON schemas at the `{{TOOL_DESCRIPTIONS}}`
 * placeholder.
 */
function buildSystemPrompt(config: AgentConfig, enabledTools: Tool[], interactive = false): string {
  const template = fs.readFileSync(config.systemPrompt, "utf-8");
  const descriptions = enabledTools.map(formatToolForPrompt).join("\n\n");
  const ttyInfo = interactive ? TTY_INTERACTIVE : TTY_NON_INTERACTIVE;

  // Replacement callbacks, not raw strings: tool schemas contain `$ref`/`$schema`,
  // and String.replace treats `$` sequences in a string replacement as capture-group
  // references, which would silently corrupt the rendered schema.
  let prompt = template
    .replace("{{TOOL_DESCRIPTIONS}}", () => descriptions)
    .replace("{{TTY_INFO}}", () => ttyInfo);

  if (config.rolePrompt !== null) {
    const role = readTextFileIfExists(config.rolePrompt);
    if (role !== null && role.trim().length > 0) {
      prompt += `\n\n## Role\n\n${role.trim()}`;
    }
  }

  return prompt;
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 2: Parse tool calls from LLM text
// ---------------------------------------------------------------------------

// Implementation: Parse canonical and flat tool envelopes while preserving valid calls and errors.
// Failure mode: Silently discarded malformed output leaves the loop unable to recover.
// Agentic coding lesson: Treat model output as untrusted protocol input and return repairable errors.

const TOOL_CALL_BLOCK = /<tool_call>([\s\S]*?)<\/tool_call>/g;

/**
 * Extracts tool calls from the LLM's response text.
 *
 * Scans for `<tool_call>...</tool_call>` blocks, parses the JSON inside
 * each one, and returns those that have a `name` field. Handles both the
 * canonical format `{"name": "...", "arguments": {...}}` and the flat format
 * where arguments are at the same level as `name`.
 * Malformed JSON blocks and blocks missing the `name` field are reported
 * as errors so the agent loop can feed them back to the model for correction.
 */
function parseToolCalls(text: string): ParseResult {
  const calls: RawToolCall[] = [];
  const errors: string[] = [];

  // Fresh matcher each call: a module-level /g regex carries lastIndex between calls.
  const matcher = new RegExp(TOOL_CALL_BLOCK.source, "g");
  let match: RegExpExecArray | null;
  while ((match = matcher.exec(text)) !== null) {
    const body = match[1].trim();

    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch (e) {
      errors.push(`Malformed tool call JSON: ${body} (${e instanceof Error ? e.message : String(e)})`);
      continue;
    }

    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      errors.push(`Malformed tool call JSON: ${body} (expected a JSON object)`);
      continue;
    }

    const envelope = parsed as Record<string, unknown>;
    const name = envelope.name;
    if (typeof name !== "string" || name.length === 0) {
      errors.push(`Tool call is missing "name": ${body}`);
      continue;
    }

    calls.push({ name, arguments: extractArguments(envelope) });
  }

  return { calls, errors };
}

// Canonical envelope nests args under `arguments`; the flat variant (Exercise 7)
// puts them beside `name`. Accept both so a cosmetic protocol slip is not a failure.
function extractArguments(envelope: Record<string, unknown>): Record<string, unknown> {
  const nested = envelope.arguments;
  if (typeof nested === "object" && nested !== null && !Array.isArray(nested)) {
    return { ...(nested as Record<string, unknown>) };
  }

  const flat: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(envelope)) {
    if (key !== "name") flat[key] = value;
  }
  return flat;
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 3: Implement the agent loop
// ---------------------------------------------------------------------------

// Implementation: Iterate model calls, execute one tool, return its result, and terminate explicitly.
// Failure mode: A one-shot or lossy loop acts on guesses and cannot learn from execution.
// Agentic coding lesson: An agent is a feedback loop, not a single model response.

const TOOL_CALL_CLOSE = "</tool_call>";

const NUDGE_MESSAGE =
  "You must call a tool to make progress. Reply with exactly one <tool_call> block. "
  + "If the task is already complete, call message_user with your final answer.";

// Tools that end the turn rather than feeding a result back into the loop.
const TERMINATING_TOOLS: readonly ToolName[] = ["message_user", "ask_user"];

/**
 * Runs the agent's internal loop for a single user turn.
 *
 * Appends the user message to the conversation, then iterates: send to LLM,
 * parse tool calls, execute, feed results back. Continues until the LLM
 * calls `message_user` or the iteration limit (20) is reached.
 *
 * Only the first tool call per response is executed. The assistant message
 * is truncated at the first `</tool_call>` in conversation history to
 * prevent the model from learning that multiple calls per turn are accepted.
 *
 * If the LLM responds with no tool call, a nudge message is injected.
 *
 * Mutates `messages` in place. Returns the final response content and an
 * ordered list of tool names invoked. The terminating tool (`message_user`
 * or `ask_user`) is included as the last entry so callers can distinguish
 * a final delivery (`message_user`) from a gate (`ask_user`).
 */
async function handleTurn(
  provider: ChatProvider,
  conversation: Conversation,
  userContent: string,
  config: AgentConfig,
  enabledTools: Tool[],
  terminal?: TerminalOutput,
  codeDecorators: ToolDecorator[] = [],
): Promise<{ content: string; toolCalls: ToolName[] }> {
  addTurn(conversation, "user", userContent);

  const toolsByName = new Map<string, Tool>(enabledTools.map((tool) => [tool.name, tool]));
  const toolNames = enabledTools.map((tool) => tool.name);
  const invoked: ToolName[] = [];
  const currentPhase = currentPhaseFromConfig(config);

  for (let iteration = 1; iteration <= config.maxIterations; iteration++) {
    exerciseLog(config, `[iteration ${iteration}/${config.maxIterations}]`);

    terminal?.spinnerStart();
    let response: string;
    try {
      response = await provider(conversationToMessages(conversation));
    } finally {
      terminal?.spinnerStop();
    }

    const { calls, errors } = parseToolCalls(response);

    // History hygiene (Exercise 7): keep at most one tool call per assistant turn so
    // the model never learns that batching calls — or narrating results — is accepted.
    addTurn(conversation, "assistant", truncateAtFirstToolCall(response));
    showThinking(terminal, response);

    if (calls.length === 0) {
      if (errors.length > 0) {
        exerciseLog(config, `parse errors: ${errors.join(" | ")}`);
        addTurn(
          conversation,
          "user",
          `Tool call parse error: ${errors.join("\n")}\n`
          + "Please fix the JSON and reply with exactly one valid <tool_call> block.",
        );
      } else {
        addTurn(conversation, "user", NUDGE_MESSAGE);
      }
      continue;
    }

    // One tool per turn. Extra calls in the same response are dropped, not queued.
    const call = calls[0];
    const tool = toolsByName.get(call.name);
    if (tool === undefined) {
      const unknown = `Unknown tool: ${call.name}. Available tools: ${toolNames.join(", ")}.`;
      terminal?.error(unknown);
      addTurn(conversation, "user", unknown);
      continue;
    }

    // Module 03, Exercise 1: operator policy is checked at the boundary, before
    // any decorator or execution can happen.
    const permission = checkToolPermission(
      tool.name,
      permissionArgsFor(call.arguments),
      config.allowTools,
      config.denyTools,
    );
    if (!permission.allowed) {
      terminal?.error(`${tool.name} denied: ${permission.reason}`);
      addTurn(conversation, "user", `Tool ${tool.name} denied by policy: ${permission.reason}`);
      continue;
    }

    // Module 03, Exercise 5: one snapshot per tool call, shared by every decorator
    // so the whole chain observes a coherent state.
    const state = snapshotAgentState({
      conversation,
      enabledTools: toolNames,
      config,
      currentPhase,
      workDir: config.workDir,
    });

    const beforeOutcome = await runBeforeDecorators(
      codeDecorators,
      { name: tool.name, arguments: resolvePathArgument(call.arguments, config.workDir) },
      state,
    );
    if (beforeOutcome.kind === "deny") {
      terminal?.error(`${tool.name} blocked by ${beforeOutcome.decorator}`);
      addTurn(
        conversation,
        "user",
        `Tool ${tool.name} blocked by decorator ${beforeOutcome.decorator}: ${beforeOutcome.reason}`,
      );
      continue;
    }

    const invocation = beforeOutcome.invocation;
    const validated: ValidatedToolCall = { name: tool.name, arguments: { ...invocation.arguments } };
    const terminating = TERMINATING_TOOLS.includes(tool.name);

    // Terminating tools end the turn, so a bad argument shape has to be repairable
    // rather than fatal: hand the validation error back and give the model another turn.
    if (terminating) {
      const schema = tool.name === "message_user" ? MessageUserParams : AskUserParams;
      const parsedArgs = schema.safeParse(validated.arguments);
      if (!parsedArgs.success) {
        addTurn(
          conversation,
          "user",
          `Invalid arguments for ${tool.name}: ${parsedArgs.error.message}\n`
          + `Please fix the JSON and call ${tool.name} again.`,
        );
        continue;
      }
    }

    // Terminating tools are rendered as the answer, not as a tool call — the user
    // reads the message, not the envelope that carried it.
    if (!terminating) terminal?.toolCall(validated);
    const result = await executeTool(tool, validated.arguments, config);
    invoked.push(tool.name);

    const afterOutcome = await runAfterDecorators(codeDecorators, invocation, result, state);
    if (afterOutcome.kind === "deny") {
      terminal?.error(`${tool.name} output blocked by ${afterOutcome.decorator}`);
      addTurn(
        conversation,
        "user",
        `Tool ${tool.name} output blocked by decorator ${afterOutcome.decorator}: ${afterOutcome.reason}`,
      );
      continue;
    }

    if (terminating && result.ok) {
      terminal?.answer(result.output);
      return { content: result.output, toolCalls: invoked };
    }

    terminal?.toolResult(result);
    const output = result.ok ? result.output : result.error;
    addTurn(
      conversation,
      "user",
      `Tool ${tool.name} ${result.ok ? "returned" : "failed"}:\n${truncateOutput(output)}`,
    );
  }

  const exhausted = `Max iterations reached (${config.maxIterations}) without a final response.`;
  terminal?.error(exhausted);
  return { content: exhausted, toolCalls: invoked };
}

function exerciseLog(config: AgentConfig, msg: string): void {
  if (config.verbose) process.stderr.write(msg + "\n");
}

// Everything after the first closing tag is commentary the model invented about a
// result it has not seen yet. Drop it before it becomes history.
function truncateAtFirstToolCall(text: string): string {
  const idx = text.indexOf(TOOL_CALL_CLOSE);
  return idx === -1 ? text : text.slice(0, idx + TOOL_CALL_CLOSE.length);
}

function showThinking(terminal: TerminalOutput | undefined, response: string): void {
  if (terminal === undefined) return;
  const idx = response.indexOf("<tool_call>");
  const preamble = (idx === -1 ? response : response.slice(0, idx)).trim();
  if (preamble.length > 0) terminal.thinking(preamble);
}

// A relative `path` is only meaningful next to a working directory. Resolving it once,
// up front, means decorators, permission reasons, the terminal and the tool itself all
// talk about the same file.
function resolvePathArgument(
  args: Record<string, unknown>,
  workDir: string,
): Record<string, unknown> {
  const rawPath = args.path;
  if (typeof rawPath !== "string" || path.isAbsolute(rawPath)) return args;
  return { ...args, path: resolveToolPath(rawPath, workDir) };
}

// Permission patterns like `shell(rm *)` are written against the argument a human
// would recognise, not against the JSON envelope.
function permissionArgsFor(args: Record<string, unknown>): string {
  for (const key of ["command", "path", "url", "message", "question"]) {
    const value = args[key];
    if (typeof value === "string") return value;
  }
  return JSON.stringify(args);
}

// Tools resolve relative paths — and run shell commands — against the process cwd,
// so the loop owns the working directory and always restores it.
async function executeTool(
  tool: Tool,
  args: Record<string, unknown>,
  config: AgentConfig,
): Promise<ToolResult> {
  const previousCwd = process.cwd();
  let changed = false;
  try {
    if (config.workDir.length > 0 && fs.existsSync(config.workDir)) {
      process.chdir(config.workDir);
      changed = true;
    }
    return await tool.execute(args);
  } catch (e) {
    return { ok: false, error: `executing ${tool.name}: ${e}` };
  } finally {
    if (changed) process.chdir(previousCwd);
  }
}

type BeforeOutcome =
  | { kind: "allow"; invocation: ToolInvocation }
  | { kind: "deny"; decorator: string; reason: string };

type AfterOutcome =
  | { kind: "allow" }
  | { kind: "deny"; decorator: string; reason: string };

// Registration order is policy order: each Allow feeds its (possibly rewritten)
// invocation to the next decorator; the first Deny short-circuits the rest.
async function runBeforeDecorators(
  decorators: ToolDecorator[],
  invocation: ToolInvocation,
  state: AgentState,
): Promise<BeforeOutcome> {
  let current = invocation;
  for (const decorator of decorators) {
    if (decorator.before === undefined) continue;
    if (!decorator.appliesTo(current, state)) continue;
    const outcome = await decorator.before(current, state);
    if (outcome.kind === "deny") {
      return { kind: "deny", decorator: decorator.name, reason: outcome.reason };
    }
    current = outcome.invocation;
  }
  return { kind: "allow", invocation: current };
}

async function runAfterDecorators(
  decorators: ToolDecorator[],
  invocation: ToolInvocation,
  result: ToolResult,
  state: AgentState,
): Promise<AfterOutcome> {
  for (const decorator of decorators) {
    if (decorator.after === undefined) continue;
    if (!decorator.appliesTo(invocation, state)) continue;
    const outcome = await decorator.after(invocation, result, state);
    if (outcome.kind === "deny") {
      return { kind: "deny", decorator: decorator.name, reason: outcome.reason };
    }
  }
  return { kind: "allow" };
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 4: Implement read_file
// ---------------------------------------------------------------------------

// Implementation: Validate a path and return file contents or a visible read failure.
// Failure mode: Without readable evidence, the model can invent facts about the repository.
// Agentic coding lesson: Require agents to inspect source evidence before accepting their claims.

function resolveToolPath(toolPath: string, workDir: string): string {
  return path.isAbsolute(toolPath) ? toolPath : path.resolve(workDir, toolPath);
}

function executeReadFile(args: Record<string, unknown>): ToolResult {
  try {
    const { path: filePath } = ReadFileParams.parse(args);
    const resolved = resolveToolPath(filePath, process.cwd());
    return { ok: true, output: fs.readFileSync(resolved, "utf-8") };
  } catch (e) {
    if (e instanceof z.ZodError) {
      return { ok: false, error: `invalid arguments: ${e.message}` };
    }
    return { ok: false, error: `reading file: ${e}` };
  }
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 5: Implement shell
// ---------------------------------------------------------------------------

// Implementation: Execute commands with a cwd, timeout, bounded streams, and explicit exit evidence.
// Failure mode: Missing stderr, status, or output bounds turns failed checks into ambiguous feedback.
// Agentic coding lesson: Shell commands make claims observable only when all evidence returns to the loop.

// Cross-language contract: 30s timeout, 1MB output cap, failures return error strings.
// Node's execSync drains stdout internally (no pipe-deadlock risk); Rust Command::output()
// and Scala ProcessBuilder+redirectErrorStream each handle draining per their stdlib idiom.
const SHELL_TIMEOUT_MS = 30_000;
const SHELL_MAX_OUTPUT_BYTES = 1024 * 1024;

function executeShell(args: Record<string, unknown>): ToolResult {
  let command: string;
  try {
    command = ShellParams.parse(args).command;
  } catch (e) {
    return { ok: false, error: `invalid arguments: ${e instanceof z.ZodError ? e.message : e}` };
  }

  try {
    const output = execSync(command, {
      cwd: process.cwd(),
      timeout: SHELL_TIMEOUT_MS,
      maxBuffer: SHELL_MAX_OUTPUT_BYTES,
      encoding: "utf-8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { ok: true, output: output.slice(0, SHELL_MAX_OUTPUT_BYTES) };
  } catch (e) {
    // A failed command is evidence, not a crash: the model needs the exit code and
    // both streams to diagnose it, so all three come back as a normal tool result.
    const failure = e as ExecException & { stdout?: string; stderr?: string; status?: number | null };
    const status = typeof failure.status === "number" ? failure.status : null;
    const streams = [failure.stdout ?? "", failure.stderr ?? ""]
      .filter((stream) => stream.length > 0)
      .join("\n")
      .trim();
    const header = status === null
      ? `command failed: ${failure.message ?? String(e)}`
      : `command failed (exit ${status})`;
    const error = streams.length > 0 ? `${header}\n${streams}` : header;
    return { ok: false, error: error.slice(0, SHELL_MAX_OUTPUT_BYTES) };
  }
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 6: Iterate on the system prompt
// ---------------------------------------------------------------------------

// Implementation: Compare this naïve prompt with defensive clauses using fixed behavioral tasks.
// Failure mode: Plausible wording gets accepted without evidence that agent behavior improved.
// Agentic coding lesson: Prompt quality is empirical, so evaluate outcomes rather than prose.

// The starting point from the exercise text, kept as a fixture so the before/after
// comparison stays visible: it describes the protocol but constrains no behavior.
// The gold prompt now lives in agent/system-prompt.txt.
const NAIVE_SYSTEM_PROMPT = `You are a coding agent. You can use tools to interact with the filesystem
and run shell commands.

To use a tool, respond with a JSON object wrapped in <tool_call> tags:

<tool_call>
{"name": "tool_name", "arguments": {"param": "value"}}
</tool_call>

You may include reasoning or commentary before or after the tool call.
When you have completed the task, use the message_user tool.`;

// ---------------------------------------------------------------------------
// Module 01, Exercise 7: Harden the loop
// ---------------------------------------------------------------------------

// Implementation: Normalize malformed calls and bound tool output before it enters model history.
// Failure mode: Bad envelopes and oversized results compound across later iterations.
// Agentic coding lesson: Model-visible context is a controlled interface with a finite budget.

// Context-budget guardrail: caps tool output before it enters conversation history.
// This protects model context size, not terminal UX.
const MAX_OUTPUT_CHARS = 10_000;

// Must run before tool output is appended to model-visible history. Truncation is
// character-based and intentionally lossy.
function truncateOutput(output: string): string {
  if (output.length <= MAX_OUTPUT_CHARS) return output;
  const remaining = output.length - MAX_OUTPUT_CHARS;
  return `${output.slice(0, MAX_OUTPUT_CHARS)}\n[truncated — ${remaining} more chars]`;
}

// Workshop waypoint — Module 01, Exercise 8 (manual; no source implementation)
// Implementation: Run identical tasks across models and settings, then record behavioral differences.
// Failure mode: Selection by reputation hides task-specific reliability and cost trade-offs.
// Agentic coding lesson: Choose models from observed task behavior, risk, and cost—not brand reputation.

// ---------------------------------------------------------------------------
// Module 01, Exercise 9: Sudoku — the reasoning illusion
// ---------------------------------------------------------------------------

// Implementation: Parse candidate grids and validate every Sudoku constraint deterministically.
// Failure mode: Fluent reasoning can confidently return a grid that violates hard constraints.
// Agentic coding lesson: Use deterministic validators instead of treating model reasoning as proof.

const SUDOKU_SIZE = 9;
const SUDOKU_BOX = 3;

function verifySudoku(grid: number[][]): string[] {
  if (grid.length !== SUDOKU_SIZE || grid.some((row) => row.length !== SUDOKU_SIZE)) {
    return [`Grid must be ${SUDOKU_SIZE}x${SUDOKU_SIZE}`];
  }

  const errors: string[] = [];

  for (let row = 0; row < SUDOKU_SIZE; row++) {
    const values = grid[row];
    if (!isCompleteGroup(values)) errors.push(`Row ${row + 1}: [${values.join(", ")}]`);
  }

  for (let col = 0; col < SUDOKU_SIZE; col++) {
    const values = grid.map((row) => row[col]);
    if (!isCompleteGroup(values)) errors.push(`Col ${col + 1}: [${values.join(", ")}]`);
  }

  for (let boxRow = 0; boxRow < SUDOKU_BOX; boxRow++) {
    for (let boxCol = 0; boxCol < SUDOKU_BOX; boxCol++) {
      const values: number[] = [];
      for (let r = 0; r < SUDOKU_BOX; r++) {
        for (let c = 0; c < SUDOKU_BOX; c++) {
          values.push(grid[boxRow * SUDOKU_BOX + r][boxCol * SUDOKU_BOX + c]);
        }
      }
      if (!isCompleteGroup(values)) {
        errors.push(`Box (${boxRow + 1},${boxCol + 1}): [${values.join(", ")}]`);
      }
    }
  }

  return errors;
}

// A group is valid only when it is exactly the digits 1–9 — no duplicates, no blanks.
function isCompleteGroup(values: number[]): boolean {
  const seen = new Set(values);
  if (seen.size !== SUDOKU_SIZE) return false;
  for (let digit = 1; digit <= SUDOKU_SIZE; digit++) {
    if (!seen.has(digit)) return false;
  }
  return true;
}

function parseSudokuGrid(text: string): number[][] | null {
  const rows: number[][] = [];
  for (const line of text.split("\n")) {
    const tokens = line.trim().split(/\s+/).filter((token) => token.length > 0);
    if (tokens.length !== SUDOKU_SIZE) continue;
    if (!tokens.every((token) => /^[0-9]$/.test(token))) continue;
    rows.push(tokens.map(Number));
    if (rows.length === SUDOKU_SIZE) return rows;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 10: The fabrication test
// ---------------------------------------------------------------------------

// Implementation: Judge whether a response answers the question or reports insufficient specification.
// Failure mode: A model may answer an underspecified question confidently; this judge cannot establish truth.
// Agentic coding lesson: Test fabrication pressure with controlled ambiguity, then verify factual claims with tools.

async function evaluateResponse(
  provider: ChatProvider,
  agentResponse: string,
): Promise<"SPECIFIED" | "UNSPECIFIED"> {
  const messages: Message[] = [{
    role: "user",
    content: `You are grading an agent's answer to a question it could not have had the data for.

Agent response:
${agentResponse}

Did the agent (a) provide a specific answer with concrete data, or (b) refuse to answer due to lack of information?

Reply with exactly one word: SPECIFIED for (a), UNSPECIFIED for (b).`,
  }];

  const verdict = (await provider(messages)).trim().toUpperCase();
  // Substring order matters: "SPECIFIED" is contained in "UNSPECIFIED".
  if (verdict.includes("UNSPECIFIED")) return "UNSPECIFIED";
  if (verdict.includes("SPECIFIED")) return "SPECIFIED";
  // An unreadable verdict is not evidence of fabrication.
  return "UNSPECIFIED";
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 11: Implement edit_file
// ---------------------------------------------------------------------------

// Implementation: Edit only a line range whose exact old text still matches.
// Failure mode: Broad or stale replacements overwrite unintended code and obscure review.
// Agentic coding lesson: Guarded, range-scoped edits reduce blast radius and preserve ownership.

// Line range is 1-based and inclusive. Only the first occurrence of old_text within
// the selected slice is replaced. File stays unchanged if range is invalid or text is absent.
function executeEditFile(args: Record<string, unknown>): ToolResult {
  let params: z.infer<typeof EditFileParams>;
  try {
    params = EditFileParams.parse(args);
  } catch (e) {
    return { ok: false, error: `invalid arguments: ${e instanceof z.ZodError ? e.message : e}` };
  }

  const { path: filePath, line_start: lineStart, line_end: lineEnd, old_text: oldText, new_text: newText } = params;

  let content: string;
  try {
    content = fs.readFileSync(resolveToolPath(filePath, process.cwd()), "utf-8");
  } catch (e) {
    return { ok: false, error: `reading file: ${e}` };
  }

  // A trailing newline is a terminator, not an empty final line — preserve it verbatim.
  const endsWithNewline = content.endsWith("\n");
  const lines = content.split("\n");
  if (endsWithNewline) lines.pop();

  if (lineStart < 1) {
    return { ok: false, error: `line_start ${lineStart} is out of range (must be >= 1)` };
  }
  if (lineEnd < lineStart) {
    return { ok: false, error: `line_end ${lineEnd} is out of range (must be >= line_start ${lineStart})` };
  }
  if (lineStart > lines.length) {
    return { ok: false, error: `line_start ${lineStart} is out of range (file has ${lines.length} lines)` };
  }
  if (lineEnd > lines.length) {
    return { ok: false, error: `line_end ${lineEnd} is out of range (file has ${lines.length} lines)` };
  }

  const slice = lines.slice(lineStart - 1, lineEnd).join("\n");
  const idx = slice.indexOf(oldText);
  if (idx === -1) {
    return { ok: false, error: `old_text not found in lines ${lineStart}-${lineEnd} of ${filePath}` };
  }

  // Index arithmetic, not String.replace: new_text is literal, so `$&` and friends
  // must never be interpreted as replacement patterns.
  const edited = slice.slice(0, idx) + newText + slice.slice(idx + oldText.length);
  const updated = [
    ...lines.slice(0, lineStart - 1),
    ...edited.split("\n"),
    ...lines.slice(lineEnd),
  ].join("\n") + (endsWithNewline ? "\n" : "");

  try {
    fs.writeFileSync(resolveToolPath(filePath, process.cwd()), updated, "utf-8");
  } catch (e) {
    return { ok: false, error: `writing file: ${e}` };
  }

  return { ok: true, output: `Successfully edited ${filePath} (${lineEnd - lineStart + 1} lines)` };
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 12: Implement the list_files tool
// ---------------------------------------------------------------------------

// Implementation: Return deterministic repository paths up to a caller-selected depth.
// Failure mode: Editing starts from an invented or incomplete understanding of project scope.
// Agentic coding lesson: Repository discovery should ground planning before modification begins.

function executeListFiles(args: Record<string, unknown>): ToolResult {
  let dirPath: string;
  let maxDepth: number;
  try {
    const parsed = ListFilesParams.parse(args);
    dirPath = parsed.path;
    maxDepth = parsed.max_depth;
  } catch (e) {
    return { ok: false, error: `invalid arguments: ${e instanceof z.ZodError ? e.message : e}` };
  }

  const root = resolveToolPath(dirPath, process.cwd());
  try {
    if (!fs.statSync(root).isDirectory()) {
      return { ok: false, error: `listing files: ${root} is not a directory` };
    }
  } catch (e) {
    return { ok: false, error: `listing files: ${e}` };
  }

  const entries: string[] = [];
  const walk = (dir: string, prefix: string, depth: number): void => {
    if (depth > maxDepth) return;
    const items = fs.readdirSync(dir, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name));
    for (const item of items) {
      const relative = prefix + item.name;
      if (item.isDirectory()) {
        entries.push(`${relative}/`);
        walk(path.join(dir, item.name), `${relative}/`, depth + 1);
      } else {
        entries.push(relative);
      }
    }
  };

  try {
    walk(root, "", 1);
  } catch (e) {
    return { ok: false, error: `listing files: ${e}` };
  }

  return { ok: true, output: entries.join("\n") };
}

async function executeWebFetch(args: Record<string, unknown>): Promise<ToolResult> {
  try {
    const { url } = WebFetchParams.parse(args);
    const response = await fetch(url);
    const body = await response.text();
    return { ok: true, output: body };
  } catch (e) {
    if (e instanceof z.ZodError) {
      return { ok: false, error: `invalid arguments: ${e.message}` };
    }
    return { ok: false, error: `fetching url: ${e}` };
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// Module 02 — Context Engineering: instructions, skills, compaction, sessions
// ═════════════════════════════════════════════════════════════════════════════

// ---------------------------------------------------------------------------
// Module 02, Exercise 1: Load persistent instructions
// ---------------------------------------------------------------------------

// Implementation: Resolve explicit or ancestor instruction files with deterministic precedence.
// Failure mode: Missing or surprising precedence makes delegated work violate project rules.
// Agentic coding lesson: Persistent instructions must be predictable and inspectable.

const INSTRUCTIONS_FILENAME = "AGENTS.md";

function loadInstructions(workDir: string, instructionsPath: string | null): string | null {
  if (instructionsPath !== null) {
    return readTextFileIfExists(resolveToolPath(instructionsPath, workDir));
  }

  // Root-first ordering: the nearest AGENTS.md is appended last so it gets final say.
  const collected: string[] = [];
  let dir = path.resolve(workDir);
  for (;;) {
    const content = readTextFileIfExists(path.join(dir, INSTRUCTIONS_FILENAME));
    if (content !== null) collected.unshift(content.trim());
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }

  return collected.length > 0 ? collected.join("\n\n") : null;
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 2: Discover and load skills
// ---------------------------------------------------------------------------

// Implementation: Discover skill metadata eagerly and load full specialist guidance on demand.
// Failure mode: Loading everything crowds context; loading nothing omits needed expertise.
// Agentic coding lesson: Selective context improves decisions without permanently consuming the window.

const SKILL_FILENAME = "SKILL.md";

function discoverSkills(skillsDir: string | null): SkillInfo[] {
  const entries = readDirEntries(skillsDir);
  const skills: SkillInfo[] = [];

  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const skillPath = path.resolve(skillsDir!, entry.name, SKILL_FILENAME);
    const content = readTextFileIfExists(skillPath);
    if (content === null) continue;

    const { attributes } = parseFrontmatter(content);
    skills.push({
      name: attributes.name ?? entry.name,
      description: attributes.description ?? "",
      path: skillPath,
    });
  }

  return skills;
}

function loadSkillContent(skillPath: string): string {
  return readTextFileIfExists(skillPath) ?? "";
}

// Shared directory reader for skills and commands: a missing or unreadable directory
// is an empty catalog, never a crash — context loading must not be able to kill a turn.
function readDirEntries(dir: string | null): fs.Dirent[] {
  if (dir === null || dir.length === 0) return [];
  try {
    return fs.readdirSync(path.resolve(dir), { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 3: Discover and execute command prompts
// ---------------------------------------------------------------------------

// Implementation: Discover prompt templates, strip metadata, and substitute invocation arguments.
// Failure mode: Rewritten ad hoc prompts drift and become difficult to inspect or reproduce.
// Agentic coding lesson: Commands turn recurring delegation patterns into explicit workflows.

const ARGUMENTS_PLACEHOLDER = "$ARGUMENTS";

function discoverCommands(commandsDir: string | null): CommandPrompt[] {
  const commands: CommandPrompt[] = [];

  for (const entry of readDirEntries(commandsDir)) {
    if (!entry.isFile() || !entry.name.endsWith(".md")) continue;
    const commandPath = path.resolve(commandsDir!, entry.name);
    const content = readTextFileIfExists(commandPath);
    if (content === null) continue;

    const { attributes } = parseFrontmatter(content);
    const hint = attributes["argument-hint"];
    commands.push({
      name: path.basename(entry.name, ".md"),
      description: attributes.description ?? "",
      argumentHint: hint !== undefined && hint.length > 0 ? hint : null,
      path: commandPath,
    });
  }

  return commands;
}

function executeCommand(command: CommandPrompt, args: string): string {
  const content = readTextFileIfExists(command.path);
  if (content === null) return "";
  const { body } = parseFrontmatter(content);
  // split/join, not replace: the arguments are literal user text and must not be
  // reinterpreted as `$1`-style replacement patterns.
  return body.split(ARGUMENTS_PLACEHOLDER).join(args);
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 4: Measure context usage
// ---------------------------------------------------------------------------

// Implementation: Measure system, conversation, and tool-schema characters against a budget.
// Failure mode: Context is removed or retained by intuition rather than actual pressure.
// Agentic coding lesson: Measure context before deciding what to preserve, remove, or compress.

function measureContext(
  systemPrompt: string,
  conversation: Conversation,
  tools: Tool[],
  maxChars: number | null,
): ContextUsage {
  const system = systemPrompt.length;
  const conversationChars = conversation.turns.reduce((total, turn) => total + turn.content.length, 0);
  const toolChars = tools.reduce((total, tool) => total + formatToolForPrompt(tool).length, 0);
  const total = system + conversationChars + toolChars;

  return {
    system,
    conversation: conversationChars,
    tools: toolChars,
    total,
    limit: maxChars,
    // A zero or absent budget yields no percentage rather than a division artifact.
    percentage: maxChars !== null && maxChars > 0 ? total / maxChars : null,
  };
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 5: Compact conversation history
// ---------------------------------------------------------------------------

// Implementation: Format history for summarization and replace it with a coherent summary turn pair.
// Failure mode: Naïve truncation loses active constraints, decisions, and task state.
// Agentic coding lesson: Compaction trades detail for capacity, so preserve operational state.

const COMPACTION_ACK = "Context compacted. Continuing from the summary above.";

// Strips system messages and emits a plain role:content transcript — the compaction
// prompt is written against this exact shape.
function formatConversationForCompaction(
  conversation: Conversation,
): string {
  return conversation.turns.map((turn) => `${turn.role}: ${turn.content}`).join("\n\n");
}

// Preserves the original system prompt, replaces prior turns with the summary, and
// injects a synthetic message_user acknowledgment so post-compaction history still
// matches the normal protocol shape.
function applyCompaction(
  conversation: Conversation,
  summary: string,
): void {
  conversation.turns = [
    { role: "user", content: `Summary of the conversation so far:\n\n${summary}` },
    {
      role: "assistant",
      content: `<tool_call>\n${JSON.stringify({ name: "message_user", arguments: { message: COMPACTION_ACK } })}\n</tool_call>`,
    },
  ];
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 6: Auto-compact on budget
// ---------------------------------------------------------------------------

// Implementation: Trigger compaction only when a configured context budget is exceeded.
// Failure mode: Late compaction degrades behavior; eager compaction destroys useful detail.
// Agentic coding lesson: Context budgets need deliberate thresholds rather than reactive cleanup.

function shouldAutoCompact(usage: ContextUsage): boolean {
  return usage.limit !== null && usage.total > usage.limit;
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 7: Save and resume conversation
// ---------------------------------------------------------------------------

// Implementation: Persist and load the stored prompt and turns faithfully; callers choose what to trust.
// Failure mode: Corrupt storage loses continuity, while trusting stale prompts preserves old authority.
// Agentic coding lesson: Separate persistence fidelity from resume policy—restore history, rebuild authority.

function saveSession(historyDir: string, sessionId: string, conversation: Conversation): void {
  const filePath = sessionFilePath(historyDir, sessionId);
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const session = {
    timestamp: new Date().toISOString(),
    workDir: process.cwd(),
    model: defaultConfig().model,
    systemPrompt: conversation.system,
    turns: conversation.turns,
  };
  fs.writeFileSync(filePath, `${JSON.stringify(session, null, 2)}\n`, "utf-8");
}

function loadSession(historyDir: string, sessionId: string): Conversation | null {
  const saved = loadSavedSessionFile(sessionFilePath(historyDir, sessionId));
  if (saved === null) return null;
  // Persistence is faithful; whether to trust the stored prompt is the caller's
  // policy decision. The CLI rebuilds the system prompt from current config.
  return { system: saved.systemPrompt, turns: saved.turns };
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 8: Session-aware compaction
// ---------------------------------------------------------------------------

// Implementation: Select, summarize, and bound relevant prior sessions for the compaction prompt.
// Failure mode: Indiscriminate history becomes noise or consumes the current task's context budget.
// Agentic coding lesson: Cross-session continuity is useful only when it remains bounded and relevant.

async function loadPastSessionSummaries(
  historyDir: string,
  currentSessionId: string,
  provider: ChatProvider,
  sessionSummaryMaxChars = 80,
): Promise<string> {
  if (historyDir.length === 0) return "";

  const resolvedDir = path.resolve(historyDir);
  let files: string[];
  try {
    files = fs.readdirSync(resolvedDir).filter((file) => file.endsWith(".json")).sort();
  } catch {
    return "";
  }

  const summaries: { timestamp: string; topic: string }[] = [];
  for (const file of files) {
    if (path.basename(file, ".json") === currentSessionId) continue;
    const saved = loadSavedSessionFile(path.join(resolvedDir, file));
    if (saved === null) continue;
    const firstUserTurn = saved.turns.find((turn) => turn.role === "user");
    // A session with no user turn has no topic to carry forward.
    if (firstUserTurn === undefined) continue;
    summaries.push({
      timestamp: saved.timestamp,
      topic: await summarizeTopic(provider, firstUserTurn.content, sessionSummaryMaxChars),
    });
  }

  return summaries
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp))
    .map((entry) => `[${entry.timestamp}] topic: ${entry.topic}`)
    .join("\n");
}

/**
 * Compress `content` to at most `maxChars` characters as a one-line topic preview.
 * Inputs that already fit are returned (after whitespace normalization) without an
 * LLM call — preserves cost and determinism for short prompts. Inputs that would
 * otherwise be truncated mid-sentence are handed to the provider with a "describe
 * the topic in N chars" prompt; `truncateSummaryText` is the final cap if the
 * model overshoots.
 */
async function summarizeTopic(
  provider: ChatProvider,
  content: string,
  maxChars: number,
): Promise<string> {
  const normalized = content.replace(/\s+/g, " ").trim();
  if (normalized.length <= maxChars) return normalized;

  const messages: Message[] = [{
    role: "user",
    content: `Describe the topic of the following text in at most ${maxChars} characters. `
      + `Reply with the description only, on one line.\n\n${content}`,
  }];
  return truncateSummaryText(await provider(messages), maxChars);
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 9: Extract memories
// ---------------------------------------------------------------------------

// Implementation: Extract durable facts and append only meaningful entries to the memory side channel.
// Failure mode: Transient or fabricated claims poison the context of future delegated work.
// Agentic coding lesson: Agent memory must be selective, durable, and reviewable.

const MEMORY_DIR = ".agent";
const MEMORY_FILENAME = "memories.md";
const MEMORY_NONE = "NONE";

async function extractMemories(
  provider: ChatProvider,
  userMessage: string,
  agentResponse: string,
  workDir: string,
): Promise<void> {
  const messages: Message[] = [{
    role: "user",
    content: `Extract durable facts worth remembering from this conversation turn.
Focus on: user preferences, project conventions, technical decisions, recurring patterns.

User message:
${userMessage}

Agent response:
${agentResponse}

Return one fact per line. If nothing is worth remembering, return exactly ${MEMORY_NONE}.`,
  }];

  const response = await provider(messages);
  if (response.trim().toUpperCase() === MEMORY_NONE) return;

  const facts = response
    .split("\n")
    .map((line) => line.replace(/^\s*[-*]\s*/, "").trim())
    .filter((fact) => fact.length > 0 && fact.toUpperCase() !== MEMORY_NONE);
  if (facts.length === 0) return;

  const memoryPath = memoryFilePath(workDir);
  fs.mkdirSync(path.dirname(memoryPath), { recursive: true });
  // Append, never rewrite: memory is a reviewable log, so earlier facts stay auditable.
  fs.appendFileSync(memoryPath, facts.map((fact) => `- ${fact}\n`).join(""), "utf-8");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 10: Inject memories into context
// ---------------------------------------------------------------------------

// Implementation: Load saved memory so scaffolding can inject it explicitly into current context.
// Failure mode: Hidden or unbounded memory silently steers later tasks and competes with current rules.
// Agentic coding lesson: Persistent knowledge must stay visible, bounded, and subordinate to current authority.

function loadMemories(workDir: string): string | null {
  return readTextFileIfExists(memoryFilePath(workDir));
}

function memoryFilePath(workDir: string): string {
  return path.join(workDir, MEMORY_DIR, MEMORY_FILENAME);
}

// ═════════════════════════════════════════════════════════════════════════════
// Module 03 — Guardrails and Safety: permissions, sandbox, secrets, audit, decorators
// ═════════════════════════════════════════════════════════════════════════════

// ---------------------------------------------------------------------------
// Module 03, Exercise 1: Check tool permissions
// ---------------------------------------------------------------------------

// Implementation: Apply allow and deny patterns with deny precedence and a completion exception.
// Failure mode: Broad permissions let delegated work exceed its intended authority.
// Agentic coding lesson: Grant the minimum capability needed, and let explicit denial veto allowance.

function matchesToolPattern(pattern: string, toolName: string, toolArgs: string): boolean {
  const parenIdx = pattern.indexOf("(");
  if (parenIdx === -1) {
    return pattern === toolName;
  }
  const patName = pattern.slice(0, parenIdx);
  if (patName !== toolName) return false;
  const glob = pattern.slice(parenIdx + 1, pattern.endsWith(")") ? pattern.length - 1 : pattern.length);
  // The `s` (dotall) flag makes `.` match newlines too — without it, multi-line
  // shell commands (heredocs, multi-line python -c) bypass deny patterns like
  // `shell(*sed -i*)` because the regex anchors `^...$` only see the first line.
  const re = new RegExp("^" + glob.split("*").map(s => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join(".*") + "$", "s");
  return re.test(toolArgs);
}

function checkToolPermission(
  toolName: string, toolArgs: string,
  allowPatterns: string[], denyPatterns: string[],
): PermissionResult {
  // The agent must always be able to terminate, or a denial becomes a hang.
  if (toolName === "message_user") {
    return { allowed: true, reason: "message_user is always permitted so the agent can finish" };
  }

  // Deny is a veto: it is checked first and overrides any allow match.
  const denied = denyPatterns.find((pattern) => matchesToolPattern(pattern, toolName, toolArgs));
  if (denied !== undefined) {
    return { allowed: false, reason: `denied by pattern "${denied}"` };
  }

  if (allowPatterns.length === 0) {
    return { allowed: true, reason: "no allow patterns configured, so all tools are permitted" };
  }

  const allowed = allowPatterns.find((pattern) => matchesToolPattern(pattern, toolName, toolArgs));
  if (allowed !== undefined) {
    return { allowed: true, reason: `allowed by pattern "${allowed}"` };
  }

  return { allowed: false, reason: `${toolName} does not match any allow pattern` };
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 2: Enforce sandbox boundaries
// ---------------------------------------------------------------------------

// Implementation: Check paths and protected files, then conservatively classify shell commands.
// Failure mode: Prompt-only analysis can approve an escaping or ambiguous command.
// Agentic coding lesson: Advisory model judgment is not an execution boundary or real isolation.

const FILE_TOOLS: readonly string[] = ["read_file", "write_file", "edit_file", "list_files"];
const WRITE_TOOLS: readonly string[] = ["write_file", "edit_file"];

function enforceSandbox(
  toolName: string, args: Record<string, unknown>,
  workDir: string, protectedFiles: string[],
): PermissionResult {
  if (!FILE_TOOLS.includes(toolName)) {
    // The shell is not path-checkable by inspection; analyzeShellSandbox is the
    // (advisory) side channel for it, and Exercise 8 is the real boundary.
    return { allowed: true, reason: `${toolName} is not a path-scoped tool` };
  }

  const rawPath = args.path;
  if (typeof rawPath !== "string") {
    return { allowed: false, reason: `${toolName} requires a string "path" argument` };
  }

  const root = path.resolve(workDir);
  const target = path.isAbsolute(rawPath) ? path.resolve(rawPath) : path.resolve(root, rawPath);
  const relative = path.relative(root, target);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    return { allowed: false, reason: `path "${rawPath}" escapes the working directory ${root}` };
  }

  if (WRITE_TOOLS.includes(toolName)) {
    // Protected files stay readable — the agent needs to see config to reason about
    // it — but never writable.
    const match = protectedFiles.find((pattern) =>
      matchesFilePattern(pattern, relative)
      || matchesFilePattern(pattern, rawPath)
      || matchesFilePattern(pattern, path.basename(target)));
    if (match !== undefined) {
      return { allowed: false, reason: `"${rawPath}" is protected by pattern "${match}"` };
    }
  }

  return { allowed: true, reason: `"${rawPath}" is inside ${root}` };
}

function matchesFilePattern(pattern: string, value: string): boolean {
  const re = new RegExp(
    "^" + pattern.split("*").map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join(".*") + "$",
  );
  return re.test(value);
}

async function analyzeShellSandbox(
  provider: ChatProvider, command: string, workDir: string,
): Promise<PermissionResult> {
  const messages: Message[] = [{
    role: "user",
    content: `Analyze this shell command and tell me if it can read or write files outside of ${workDir}. `
      + `Answer with exactly 'yes', 'no', or 'unknown'.\n\nCommand:\n${command}`,
  }];

  const answer = (await provider(messages)).trim().toLowerCase().replace(/[^a-z]/g, "");

  // Fail closed. Only an unambiguous "no" allows the command: "unknown", "yes",
  // and anything unparseable are all treated as escape risks. This is advisory
  // model judgment, not isolation — it is trivially fooled by obfuscated commands.
  if (answer === "no") {
    return { allowed: true, reason: `model analysis reports no access outside ${workDir}` };
  }
  return {
    allowed: false,
    reason: `model analysis returned "${answer || "empty"}"; treating as a possible escape from ${workDir}`,
  };
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 3: Redact secrets
// ---------------------------------------------------------------------------

// Implementation: Apply configured patterns safely to text explicitly passed to this teaching primitive.
// Failure mode: Assuming an unwired helper protects output or logs leaves disclosure paths open.
// Agentic coding lesson: Treat every model and log channel as a surface that must be wired and verified.

const REDACTION = "[REDACTED]";
const INLINE_IGNORECASE = "(?i)";

function redactSecrets(text: string, patterns: string[]): string {
  let redacted = text;
  for (const pattern of patterns) {
    // The shared default patterns use the inline `(?i)` flag, which JS regexes do
    // not support — translate it to the `i` flag instead of silently skipping them.
    const caseInsensitive = pattern.startsWith(INLINE_IGNORECASE);
    const source = caseInsensitive ? pattern.slice(INLINE_IGNORECASE.length) : pattern;
    try {
      redacted = redacted.replace(new RegExp(source, caseInsensitive ? "gi" : "g"), REDACTION);
    } catch {
      // An unparseable pattern is an operator config error, not a reason to drop
      // the text or throw mid-turn: skip it and keep applying the rest.
      continue;
    }
  }
  return redacted;
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 4: Log audit events
// ---------------------------------------------------------------------------

// Implementation: Append structured JSONL events that reconstruct attempted and completed actions.
// Failure mode: A final agent narrative cannot prove what tools actually ran or changed.
// Agentic coding lesson: Auditable traces preserve operator accountability and support diagnosis.

function logAuditEvent(logPath: string, event: AuditEvent): void {
  const resolved = path.resolve(logPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  // One JSON object per line: append-only, greppable, and safe to tail while running.
  fs.appendFileSync(resolved, `${JSON.stringify(event)}\n`, "utf-8");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 5: Apply tool decorators
// ---------------------------------------------------------------------------

// Implementation: Wire ordered pre/post hooks, rewrites, denials, and feedback through the agent loop above.
// Failure mode: Prompt instructions can be bypassed at the exact moment an action is attempted.
// Agentic coding lesson: Enforce critical policy deterministically at tool boundaries.

/**
 * Phase identifier the agent loop will hand to every decorator's `appliesTo`
 * via `AgentState.currentPhase`.
 *
 * Resolution order:
 *   1. `config.currentPhase` — set explicitly by workflow callers
 *      that materialize the role-prompt to a temp file whose basename does
 *      NOT carry the phase name. ALWAYS use this when constructing
 *      RunPhaseOptions from a known phase.
 *   2. Basename-of-rolePrompt (legacy fallback) — only works when the role
 *      prompt is a clean `<phase>.md`. Files like `<phase>.prompt.md` or
 *      `phase-role-<pid>-<ns>.md` produce a value that won't match any
 *      phase-scoped decorator's `appliesTo` check; the explicit field exists
 *      precisely to avoid that failure mode.
 *   3. `null` — no role prompt and no explicit phase (bare worker agent).
 */
function currentPhaseFromConfig(config: AgentConfig): string | null {
  if (config.currentPhase !== null && config.currentPhase.length > 0) {
    return config.currentPhase;
  }
  if (config.rolePrompt === null) return null;
  return path.basename(config.rolePrompt).replace(/\.[^.]+$/, "");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 6: Build an exit-gate decorator
// ---------------------------------------------------------------------------

// Implementation: Run independent commands before accepting message_user and feed failures back.
// Failure mode: The agent declares completion while required tests or checks still fail.
// Agentic coding lesson: Completion is a claim—prompts advise, but executable gates enforce.
//
// The "guardrails > prompts" doctrine: an instruction in a prompt ("verify
// your work before calling message_user") can be ignored by the LLM. The
// same enforcement expressed as a code decorator on message_user CANNOT —
// the agent loop refuses to let the call through until every gate command
// exits 0.
//
// The decorator's `after` hook fires when the LLM tries to terminate via
// message_user. For each shell command in `commands` it runs `sh -c <cmd>`
// in the agent's workDir; the first non-zero exit becomes a Deny carrying
// the gate's exit code + truncated stdout/stderr. The handleTurn loop
// surfaces that Deny back to the LLM as a fresh user-role turn, so the
// LLM sees the diagnostic and gets another iteration to fix the root
// cause. All-zero → Allow → message is delivered normally.

const EXIT_GATE_MAX_OUTPUT_BYTES = 2048; // per gate; trims long stderr in deny reasons
const EXIT_GATE_TIMEOUT_MS = 120_000;    // wall-clock cap so a hung gate cannot lock the agent

function truncateGateOutput(s: string, max: number): string {
  if (s.length <= max) return s;
  return `${s.slice(0, max)}\n[truncated — ${s.length - max} more chars]`;
}

function runGate(cmd: string, cwd: string): { code: number; stdout: string; stderr: string } {
  // sh -c, not a re-implemented shell: gates are user-authored and use pipes,
  // redirects and && freely.
  const result = spawnSync("sh", ["-c", cmd], {
    cwd,
    encoding: "utf-8",
    timeout: EXIT_GATE_TIMEOUT_MS,
    maxBuffer: SHELL_MAX_OUTPUT_BYTES,
  });

  const stdout = result.stdout ?? "";
  const stderr = result.stderr ?? "";
  if (result.error !== undefined) {
    // A gate that could not run has not passed.
    return { code: result.status ?? 1, stdout, stderr: `${stderr}${result.error.message}` };
  }
  return { code: result.status ?? 1, stdout, stderr };
}

export function exitGate(commands: string[]): ToolDecorator {
  return {
    name: "exit-gate",
    // No gates configured means no decorator: appliesTo returns false so the
    // empty case is a true no-op rather than an always-allow hook.
    appliesTo: (invocation) => commands.length > 0 && invocation.name === "message_user",
    after: async (_invocation, _result, state) => {
      for (const cmd of commands) {
        const { code, stdout, stderr } = runGate(cmd, state.workDir);
        if (code !== 0) {
          // Short-circuit: later gates cannot reverse the verdict and would only
          // bury the diagnostic the model needs.
          const output = truncateGateOutput([stdout, stderr].join("\n").trim(), EXIT_GATE_MAX_OUTPUT_BYTES);
          return deny(
            `Exit-gate failed: \`${cmd}\` exited ${code}.\n${output}\n`
            + "You cannot finish until every exit gate passes. Fix the underlying failure, then try again.",
          );
        }
      }
      return allow({ name: "message_user", arguments: {} });
    },
  };
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 7: Create and restore checkpoints
// ---------------------------------------------------------------------------

// Implementation: Copy checkpointed files back over current files; later-created files remain.
// Failure mode: Treating this partial restore as exact rollback leaves unwanted files behind.
// Agentic coding lesson: Recovery limits risk only when operators verify its actual semantics.

const CHECKPOINT_PREFIX = "cp-";

let _checkpointCounter = 0;
function createCheckpoint(workDir: string, checkpointsDir: string): CheckpointInfo {
  const timestamp = new Date().toISOString();
  // The counter disambiguates checkpoints created inside the same millisecond, so
  // ids stay lexicographically sortable in creation order.
  const id = `${CHECKPOINT_PREFIX}${timestamp.replace(/[:.]/g, "-")}-${String(_checkpointCounter++).padStart(4, "0")}`;
  const target = path.resolve(checkpointsDir, id);
  fs.mkdirSync(target, { recursive: true });
  fs.cpSync(path.resolve(workDir), target, { recursive: true });
  return { id, timestamp, path: target };
}

function restoreCheckpoint(workDir: string, checkpointId: string, checkpointsDir: string): boolean {
  const source = path.resolve(checkpointsDir, checkpointId);
  try {
    if (!fs.statSync(source).isDirectory()) return false;
  } catch {
    return false;
  }
  // Copy-over, not replace: files created after the checkpoint survive. This is a
  // partial restore, not an exact rollback — operators must know the difference.
  fs.cpSync(source, path.resolve(workDir), { recursive: true });
  return true;
}

function listCheckpoints(checkpointsDir: string): CheckpointInfo[] {
  const resolved = path.resolve(checkpointsDir);
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(resolved, { withFileTypes: true });
  } catch {
    return [];
  }

  return entries
    .filter((entry) => entry.isDirectory() && entry.name.startsWith(CHECKPOINT_PREFIX))
    .map((entry) => {
      const checkpointPath = path.resolve(resolved, entry.name);
      return {
        id: entry.name,
        timestamp: fs.statSync(checkpointPath).mtime.toISOString(),
        path: checkpointPath,
      };
    })
    .sort((a, b) => b.id.localeCompare(a.id));
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 8: Sandbox in Lima VM
// ---------------------------------------------------------------------------

// Implementation: Adapt command execution to a separately provisioned Lima VM and return its result.
// Failure mode: A wrapper is mistaken for isolation despite missing provisioning or platform support.
// Agentic coding lesson: Real isolation is an operational boundary, not a stronger prompt warning.

const LIMA_INSTANCE = "default";

function executeSandboxedShell(
  command: string,
  workDir: string,
  limactl = "limactl",
): ToolResult {
  // The cd is quoted here rather than passed as a spawn option: the working
  // directory has to be resolved inside the VM, not in the host process.
  const remote = `cd -- ${shellQuote(workDir)} && ${command}`;
  try {
    const output = execFileSync(limactl, ["shell", LIMA_INSTANCE, "--", "bash", "-c", remote], {
      encoding: "utf-8",
      timeout: SHELL_TIMEOUT_MS,
      maxBuffer: SHELL_MAX_OUTPUT_BYTES,
      stdio: ["ignore", "pipe", "pipe"],
    });
    return { ok: true, output };
  } catch (e) {
    // Both failure shapes — the VM command exiting non-zero, and limactl itself
    // not starting (not installed, no provisioned instance) — surface as one
    // structured error rather than an exception through the agent loop.
    const failure = e as ExecException & { stdout?: string; stderr?: string; status?: number | null };
    const status = typeof failure.status === "number" ? failure.status : null;
    const streams = [failure.stdout ?? "", failure.stderr ?? ""]
      .filter((stream) => stream.length > 0)
      .join("\n")
      .trim();
    const header = status === null
      ? `sandboxed shell error: ${failure.message ?? String(e)}`
      : `sandboxed shell error: exited ${status}`;
    return { ok: false, error: streams.length > 0 ? `${header}\n${streams}` : header };
  }
}

// POSIX single-quoting: everything is literal inside '...', and an embedded quote
// is closed, escaped, and reopened.
function shellQuote(value: string): string {
  return `'${value.split("'").join("'\\''")}'`;
}

// ─── Production exports — called by agent.ts at runtime ────────────────────
export {
  buildSystemPrompt,
  handleTurn,
  loadInstructions,
  discoverSkills,
  loadSkillContent,
  discoverCommands,
  executeCommand,
  measureContext,
  formatConversationForCompaction,
  applyCompaction,
  shouldAutoCompact,
  saveSession,
  loadPastSessionSummaries,
  extractMemories,
  loadMemories,
  executeReadFile,
  executeShell,
  executeEditFile,
  executeListFiles,
  executeWebFetch,
};

// ─── Test-facing exports — re-exported solely so module exercise tests can reach them ───
// Don't import these from agent code; use the production exports above. If you
// need one of these in agent code, promote it above and remove the duplicate.
export {
  parseToolCalls,
  truncateOutput,
  verifySudoku,
  parseSudokuGrid,
  evaluateResponse,
  MAX_OUTPUT_CHARS,
  NAIVE_SYSTEM_PROMPT,
  loadSession,
  checkToolPermission,
  enforceSandbox,
  analyzeShellSandbox,
  redactSecrets,
  logAuditEvent,
  currentPhaseFromConfig,
  createCheckpoint,
  restoreCheckpoint,
  listCheckpoints,
  executeSandboxedShell,
};
