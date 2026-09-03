// Exercise bodies. Every `// Module NN, Exercise N: Title` marker labels a function attendees implement.
// Unmarked functions at the bottom of the file are helpers — scaffolding, not exercises.

import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";
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

/**
 * Builds the system prompt by loading the template from `system-prompt.txt`
 * and injecting tool descriptions with JSON schemas at the `{{TOOL_DESCRIPTIONS}}`
 * placeholder.
 */
function buildSystemPrompt(config: AgentConfig, enabledTools: Tool[], interactive = false): string {
  // TODO — Module 01, Exercise 1. See 01-foundations/exercises.md and the
  // contract comments and exercise notes above for the required behavior.
  throw new Error("Module 01, Exercise 1: Build the system prompt — see 01-foundations/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 2: Parse tool calls from LLM text
// ---------------------------------------------------------------------------

// Implementation: Parse canonical and flat tool envelopes while preserving valid calls and errors.
// Failure mode: Silently discarded malformed output leaves the loop unable to recover.
// Agentic coding lesson: Treat model output as untrusted protocol input and return repairable errors.

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
  // TODO — Module 01, Exercise 2. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 2: Parse tool calls from LLM text — see 01-foundations/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 3: Implement the agent loop
// ---------------------------------------------------------------------------

// Implementation: Iterate model calls, execute one tool, return its result, and terminate explicitly.
// Failure mode: A one-shot or lossy loop acts on guesses and cannot learn from execution.
// Agentic coding lesson: An agent is a feedback loop, not a single model response.

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
  // TODO — Module 01, Exercise 3. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 3: Implement the agent loop — see 01-foundations/exercises.md");
}

function exerciseLog(config: AgentConfig, msg: string): void {
  if (config.verbose) process.stderr.write(msg + "\n");
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
  // TODO — Module 01, Exercise 4. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 4: Implement read_file — see 01-foundations/exercises.md");
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
function executeShell(args: Record<string, unknown>): ToolResult {
  // TODO — Module 01, Exercise 5. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 5: Implement shell — see 01-foundations/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 6: Iterate on the system prompt
// ---------------------------------------------------------------------------

// Implementation: Compare this naïve prompt with defensive clauses using fixed behavioral tasks.
// Failure mode: Plausible wording gets accepted without evidence that agent behavior improved.
// Agentic coding lesson: Prompt quality is empirical, so evaluate outcomes rather than prose.

// TODO — Module 01, Exercise 6. See 01-foundations/exercises.md.
// Replace this placeholder with the prompt variant the exercise asks you to evaluate.
const NAIVE_SYSTEM_PROMPT = "";

// ---------------------------------------------------------------------------
// Module 01, Exercise 7: Harden the loop
// ---------------------------------------------------------------------------

// Implementation: Normalize malformed calls and bound tool output before it enters model history.
// Failure mode: Bad envelopes and oversized results compound across later iterations.
// Agentic coding lesson: Model-visible context is a controlled interface with a finite budget.

// Context-budget guardrail: caps tool output before it enters conversation history.
// This protects model context size, not terminal UX.
// TODO — Module 01, Exercise 7. See 01-foundations/exercises.md.
// Choose a finite model-visible output ceiling for the truncation guardrail.
const MAX_OUTPUT_CHARS = 0;

// Must run before tool output is appended to model-visible history. Truncation is
// character-based and intentionally lossy.
function truncateOutput(output: string): string {
  // TODO — Module 01, Exercise 7. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 7: Harden the loop — see 01-foundations/exercises.md");
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

function verifySudoku(grid: number[][]): string[] {
  // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 9: Sudoku — the reasoning illusion — see 01-foundations/exercises.md");
}

function parseSudokuGrid(text: string): number[][] | null {
  // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 9: Sudoku — the reasoning illusion — see 01-foundations/exercises.md");
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
  // TODO — Module 01, Exercise 10. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 10: The fabrication test — see 01-foundations/exercises.md");
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
  // TODO — Module 01, Exercise 11. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 11: Implement edit_file — see 01-foundations/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 12: Implement the list_files tool
// ---------------------------------------------------------------------------

// Implementation: Return deterministic repository paths up to a caller-selected depth.
// Failure mode: Editing starts from an invented or incomplete understanding of project scope.
// Agentic coding lesson: Repository discovery should ground planning before modification begins.

function executeListFiles(args: Record<string, unknown>): ToolResult {
  // TODO — Module 01, Exercise 12. See 01-foundations/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 01, Exercise 12: Implement the list_files tool — see 01-foundations/exercises.md");
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

function loadInstructions(workDir: string, instructionsPath: string | null): string | null {
  // TODO — Module 02, Exercise 1. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 1: Load persistent instructions — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 2: Discover and load skills
// ---------------------------------------------------------------------------

// Implementation: Discover skill metadata eagerly and load full specialist guidance on demand.
// Failure mode: Loading everything crowds context; loading nothing omits needed expertise.
// Agentic coding lesson: Selective context improves decisions without permanently consuming the window.

function discoverSkills(skillsDir: string | null): SkillInfo[] {
  // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 2: Discover and load skills — see 02-context-engineering/exercises.md");
}

function loadSkillContent(skillPath: string): string {
  // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 2: Discover and load skills — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 3: Discover and execute command prompts
// ---------------------------------------------------------------------------

// Implementation: Discover prompt templates, strip metadata, and substitute invocation arguments.
// Failure mode: Rewritten ad hoc prompts drift and become difficult to inspect or reproduce.
// Agentic coding lesson: Commands turn recurring delegation patterns into explicit workflows.

function discoverCommands(commandsDir: string | null): CommandPrompt[] {
  // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 3: Discover and execute command prompts — see 02-context-engineering/exercises.md");
}

function executeCommand(command: CommandPrompt, args: string): string {
  // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 3: Discover and execute command prompts — see 02-context-engineering/exercises.md");
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
  // TODO — Module 02, Exercise 4. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 4: Measure context usage — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 5: Compact conversation history
// ---------------------------------------------------------------------------

// Implementation: Format history for summarization and replace it with a coherent summary turn pair.
// Failure mode: Naïve truncation loses active constraints, decisions, and task state.
// Agentic coding lesson: Compaction trades detail for capacity, so preserve operational state.

// Strips system messages and emits a plain role:content transcript — the compaction
// prompt is written against this exact shape.
function formatConversationForCompaction(
  conversation: Conversation,
): string {
  // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 5: Compact conversation history — see 02-context-engineering/exercises.md");
}

// Preserves the original system prompt, replaces prior turns with the summary, and
// injects a synthetic message_user acknowledgment so post-compaction history still
// matches the normal protocol shape.
function applyCompaction(
  conversation: Conversation,
  summary: string,
): void {
  // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 5: Compact conversation history — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 6: Auto-compact on budget
// ---------------------------------------------------------------------------

// Implementation: Trigger compaction only when a configured context budget is exceeded.
// Failure mode: Late compaction degrades behavior; eager compaction destroys useful detail.
// Agentic coding lesson: Context budgets need deliberate thresholds rather than reactive cleanup.

function shouldAutoCompact(usage: ContextUsage): boolean {
  // TODO — Module 02, Exercise 6. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 6: Auto-compact on budget — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 7: Save and resume conversation
// ---------------------------------------------------------------------------

// Implementation: Persist and load the stored prompt and turns faithfully; callers choose what to trust.
// Failure mode: Corrupt storage loses continuity, while trusting stale prompts preserves old authority.
// Agentic coding lesson: Separate persistence fidelity from resume policy—restore history, rebuild authority.

function saveSession(historyDir: string, sessionId: string, conversation: Conversation): void {
  // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 7: Save and resume conversation — see 02-context-engineering/exercises.md");
}

function loadSession(historyDir: string, sessionId: string): Conversation | null {
  // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 7: Save and resume conversation — see 02-context-engineering/exercises.md");
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
  // TODO — Module 02, Exercise 8. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 8: Session-aware compaction — see 02-context-engineering/exercises.md");
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
  // TODO — Module 02, Exercise 8. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 8: Session-aware compaction — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 9: Extract memories
// ---------------------------------------------------------------------------

// Implementation: Extract durable facts and append only meaningful entries to the memory side channel.
// Failure mode: Transient or fabricated claims poison the context of future delegated work.
// Agentic coding lesson: Agent memory must be selective, durable, and reviewable.

async function extractMemories(
  provider: ChatProvider,
  userMessage: string,
  agentResponse: string,
  workDir: string,
): Promise<void> {
  // TODO — Module 02, Exercise 9. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 9: Extract memories — see 02-context-engineering/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 10: Inject memories into context
// ---------------------------------------------------------------------------

// Implementation: Load saved memory so scaffolding can inject it explicitly into current context.
// Failure mode: Hidden or unbounded memory silently steers later tasks and competes with current rules.
// Agentic coding lesson: Persistent knowledge must stay visible, bounded, and subordinate to current authority.

function loadMemories(workDir: string): string | null {
  // TODO — Module 02, Exercise 10. See 02-context-engineering/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 02, Exercise 10: Inject memories into context — see 02-context-engineering/exercises.md");
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
  // TODO — Module 03, Exercise 1. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 1: Check tool permissions — see 03-guardrails-and-safety/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 2: Enforce sandbox boundaries
// ---------------------------------------------------------------------------

// Implementation: Check paths and protected files, then conservatively classify shell commands.
// Failure mode: Prompt-only analysis can approve an escaping or ambiguous command.
// Agentic coding lesson: Advisory model judgment is not an execution boundary or real isolation.

function enforceSandbox(
  toolName: string, args: Record<string, unknown>,
  workDir: string, protectedFiles: string[],
): PermissionResult {
  // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 2: Enforce sandbox boundaries — see 03-guardrails-and-safety/exercises.md");
}

async function analyzeShellSandbox(
  provider: ChatProvider, command: string, workDir: string,
): Promise<PermissionResult> {
  // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 2: Enforce sandbox boundaries — see 03-guardrails-and-safety/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 3: Redact secrets
// ---------------------------------------------------------------------------

// Implementation: Apply configured patterns safely to text explicitly passed to this teaching primitive.
// Failure mode: Assuming an unwired helper protects output or logs leaves disclosure paths open.
// Agentic coding lesson: Treat every model and log channel as a surface that must be wired and verified.

function redactSecrets(text: string, patterns: string[]): string {
  // TODO — Module 03, Exercise 3. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 3: Redact secrets — see 03-guardrails-and-safety/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 4: Log audit events
// ---------------------------------------------------------------------------

// Implementation: Append structured JSONL events that reconstruct attempted and completed actions.
// Failure mode: A final agent narrative cannot prove what tools actually ran or changed.
// Agentic coding lesson: Auditable traces preserve operator accountability and support diagnosis.

function logAuditEvent(logPath: string, event: AuditEvent): void {
  // TODO — Module 03, Exercise 4. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 4: Log audit events — see 03-guardrails-and-safety/exercises.md");
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
  // TODO — Module 03, Exercise 5. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 5: Apply tool decorators — see 03-guardrails-and-safety/exercises.md");
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

function truncateGateOutput(s: string, max: number): string {
  // TODO — Module 03, Exercise 6. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 6: Build an exit-gate decorator — see 03-guardrails-and-safety/exercises.md");
}

function runGate(cmd: string, cwd: string): { code: number; stdout: string; stderr: string } {
  // TODO — Module 03, Exercise 6. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 6: Build an exit-gate decorator — see 03-guardrails-and-safety/exercises.md");
}

export function exitGate(commands: string[]): ToolDecorator {
  // TODO — Module 03, Exercise 6. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 6: Build an exit-gate decorator — see 03-guardrails-and-safety/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 7: Create and restore checkpoints
// ---------------------------------------------------------------------------

// Implementation: Copy checkpointed files back over current files; later-created files remain.
// Failure mode: Treating this partial restore as exact rollback leaves unwanted files behind.
// Agentic coding lesson: Recovery limits risk only when operators verify its actual semantics.

let _checkpointCounter = 0;
function createCheckpoint(workDir: string, checkpointsDir: string): CheckpointInfo {
  // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 7: Create and restore checkpoints — see 03-guardrails-and-safety/exercises.md");
}

function restoreCheckpoint(workDir: string, checkpointId: string, checkpointsDir: string): boolean {
  // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 7: Create and restore checkpoints — see 03-guardrails-and-safety/exercises.md");
}

function listCheckpoints(checkpointsDir: string): CheckpointInfo[] {
  // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 7: Create and restore checkpoints — see 03-guardrails-and-safety/exercises.md");
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 8: Sandbox in Lima VM
// ---------------------------------------------------------------------------

// Implementation: Adapt command execution to a separately provisioned Lima VM and return its result.
// Failure mode: A wrapper is mistaken for isolation despite missing provisioning or platform support.
// Agentic coding lesson: Real isolation is an operational boundary, not a stronger prompt warning.

function executeSandboxedShell(
  command: string,
  workDir: string,
  limactl = "limactl",
): ToolResult {
  // TODO — Module 03, Exercise 8. See 03-guardrails-and-safety/exercises.md and the
  // docstring and exercise notes above for the contract.
  throw new Error("Module 03, Exercise 8: Sandbox in Lima VM — see 03-guardrails-and-safety/exercises.md");
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
