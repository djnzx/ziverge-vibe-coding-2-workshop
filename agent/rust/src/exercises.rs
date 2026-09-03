// Exercise bodies. Every `// Module NN, Exercise N: Title` marker labels a function attendees implement.
// Unmarked functions at the bottom of the file are helpers — scaffolding, not exercises.

#![allow(dead_code)]

use crate::config::AgentConfig;
use crate::conversation::{
    build_message, message_role_and_content, ChatCompletionRequestAssistantMessageArgs,
    ChatCompletionRequestMessage, ChatCompletionRequestUserMessageArgs, ChatProvider, Conversation,
    ParseResult, TurnResult,
};
use crate::session::{
    format_timestamp_iso8601, memories_file_path, normalize_single_line, parse_frontmatter,
    session_file_path, StoredMessage, StoredSession, DEFAULT_SESSION_MODEL,
    SESSION_TOPIC_CHAR_LIMIT,
};
use crate::terminal::TerminalOutput;
use crate::tools::{
    format_tool_for_context, AskUserParams, AuditEvent, CheckpointInfo, CommandPrompt,
    ContextUsage, EditFileParams, ListFilesParams, MessageUserParams, PermissionResult,
    RawToolCall, ReadFileParams, ShellParams, SkillInfo, Tool, ToolName, ToolResult,
    ValidatedToolCall, WebFetchParams,
};
use anyhow::Result;
use async_openai::types::{
    ChatCompletionRequestAssistantMessageContent, ChatCompletionRequestUserMessageContent,
};
use regex::Regex;
use serde_json::Value;
use std::env;
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::LazyLock;
use std::time::{Duration, SystemTime};
use wait_timeout::ChildExt;

// ═════════════════════════════════════════════════════════════════════════════
// Module 01 — Foundations: agent loop, tools, system prompt, evaluation
// ═════════════════════════════════════════════════════════════════════════════

// ---------------------------------------------------------------------------
// Module 01, Exercise 1: Build the system prompt
// ---------------------------------------------------------------------------

// Implementation: Load the prompt template and render enabled tool descriptions and schemas.
// Failure mode: Missing or vague affordances make the model guess tool names and arguments.
// Agentic coding lesson: Tool schemas are delegation contracts, not incidental documentation.

const TTY_HINT: &str = "The user is in an interactive terminal with colored output. \
Do NOT use markdown formatting (no **, `, #, or ```) in your message_user responses. \
Write plain text — the terminal UI handles all visual formatting.";

/// Assembles the system prompt that teaches the LLM the tool protocol.
///
/// Reads the template from `../system-prompt.txt` and replaces `{{TOOL_DESCRIPTIONS}}`
/// with the formatted tool descriptions (name, description, and JSON Schema for each tool).
/// If `interactive` is true, replaces `{{TTY_INFO}}` with TTY_HINT; otherwise replaces it with an empty string.
pub(crate) fn build_system_prompt(
    config: &AgentConfig,
    tools: &[Tool],
    interactive: bool,
) -> String {
    // TODO — Module 01, Exercise 1. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 1 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 2: Parse tool calls from LLM text
// ---------------------------------------------------------------------------

// Implementation: Parse canonical and flat tool envelopes while preserving valid calls and errors.
// Failure mode: Silently discarded malformed output leaves the loop unable to recover.
// Agentic coding lesson: Treat model output as untrusted protocol input and return repairable errors.

pub(crate) static TOOL_CALL_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)<tool_call>\s*(.*?)\s*</tool_call>").expect("static regex must compile")
});
/// Extracts tool calls from the LLM's response text.
///
/// Finds all `<tool_call>...</tool_call>` blocks and deserializes the JSON
/// inside each into a [`ToolCall`]. Handles both the canonical format
/// `{"name": "...", "arguments": {...}}` and the flat format where arguments
/// are at the same level as `name`. Malformed JSON and missing `name` fields
/// are reported as errors so the agent loop can feed them back to the model.
pub(crate) fn parse_tool_calls(text: &str) -> ParseResult {
    // TODO — Module 01, Exercise 2. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 2 — see 01-foundations/exercises.md")
}
fn resolve_tool_path(tool_path: &str, work_dir: &Path) -> PathBuf {
    let p = Path::new(tool_path);
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        work_dir.join(p)
    }
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 3: Implement the agent loop
// ---------------------------------------------------------------------------

// Implementation: Iterate model calls, execute one tool, return its result, and terminate explicitly.
// Failure mode: A one-shot or lossy loop acts on guesses and cannot learn from execution.
// Agentic coding lesson: An agent is a feedback loop, not a single model response.

pub(crate) static THINKING_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?si)THINKING:\s*(.*?)(?:ACTION:|<tool_call>|$)")
        .expect("static regex must compile")
});

pub(crate) async fn handle_turn(
    provider: &dyn ChatProvider,
    config: &AgentConfig,
    available_tools: &[Tool],
    conversation: &mut Conversation,
    user_content: &str,
    terminal: Option<&dyn TerminalOutput>,
    code_decorators: &[crate::decorators::ToolDecorator],
) -> Result<TurnResult> {
    // TODO — Module 01, Exercise 3. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 3 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 4: Implement read_file
// ---------------------------------------------------------------------------

// Implementation: Validate a path and return file contents or a visible read failure.
// Failure mode: Without readable evidence, the model can invent facts about the repository.
// Agentic coding lesson: Require agents to inspect source evidence before accepting their claims.

/// Reads a file and returns its contents, or an error string.
pub(crate) fn execute_read_file(args: Value) -> ToolResult {
    // TODO — Module 01, Exercise 4. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 4 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 5: Implement shell
// ---------------------------------------------------------------------------

// Implementation: Execute commands with a cwd, timeout, bounded streams, and explicit exit evidence.
// Failure mode: Missing stderr, status, or output bounds turns failed checks into ambiguous feedback.
// Agentic coding lesson: Shell commands make claims observable only when all evidence returns to the loop.

/// Runs a shell command via `sh -c` and returns stdout on success,
/// or stderr (or exit status) on failure.
// Non-zero exits return combined stdout/stderr as Error string so the loop continues.
pub(crate) const SHELL_OUTPUT_CAP_BYTES: usize = 1_048_576;

pub(crate) fn cap_shell_stream(bytes: &[u8]) -> String {
    let capped = if bytes.len() > SHELL_OUTPUT_CAP_BYTES {
        &bytes[..SHELL_OUTPUT_CAP_BYTES]
    } else {
        bytes
    };
    String::from_utf8_lossy(capped).to_string()
}

// Cross-language contract: 30s timeout, 1MB output cap, failures return error strings.
// Drain both pipes concurrently while the child runs. Waiting before reading can
// deadlock once either OS pipe buffer fills.
pub(crate) fn execute_shell(args: Value, work_dir: &Path) -> ToolResult {
    // TODO — Module 01, Exercise 5. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 5 — see 01-foundations/exercises.md")
}

pub(crate) fn execute_shell_default(args: Value) -> ToolResult {
    execute_shell(args, Path::new("."))
}

pub(crate) fn execute_web_fetch(args: Value) -> ToolResult {
    let params: WebFetchParams = match serde_json::from_value(args) {
        Ok(v) => v,
        Err(e) => return ToolResult::Err(format!("invalid arguments: {e}")),
    };

    // Consume the Response inside block_in_place. The Response carries an Arc
    // to reqwest's internal current_thread runtime; if it drops outside this
    // closure, the runtime drop happens in an async context and tokio panics
    // ("Cannot drop a runtime in a context where blocking is not allowed").
    let result = tokio::task::block_in_place(|| match reqwest::blocking::get(&params.url) {
        Ok(r) => r.text().map_err(|e| format!("reading response: {e}")),
        Err(e) => Err(format!("fetching url: {e}")),
    });
    match result {
        Ok(body) => ToolResult::Ok(body),
        Err(e) => ToolResult::Err(e),
    }
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 6: Iterate on the system prompt
// ---------------------------------------------------------------------------

// Implementation: Compare this naïve prompt with defensive clauses using fixed behavioral tasks.
// Failure mode: Plausible wording gets accepted without evidence that agent behavior improved.
// Agentic coding lesson: Prompt quality is empirical, so evaluate outcomes rather than prose.

// TODO — Module 01, Exercise 6. See 01-foundations/exercises.md.
// Replace this placeholder with the prompt variant used by the exercise.
pub(crate) const NAIVE_SYSTEM_PROMPT: &str = "";

// ---------------------------------------------------------------------------
// Module 01, Exercise 7: Harden the loop
// ---------------------------------------------------------------------------

// Implementation: Normalize malformed calls and bound tool output before it enters model history.
// Failure mode: Bad envelopes and oversized results compound across later iterations.
// Agentic coding lesson: Model-visible context is a controlled interface with a finite budget.

fn preview(text: &str, max_chars: usize) -> String {
    let mut output = text.chars().take(max_chars).collect::<String>();
    if text.chars().count() > max_chars {
        output.push_str("...");
    }
    output
}

// Context-budget guardrail: caps tool output before it enters conversation history.
// Protects model context size, not terminal UX.
// TODO — Module 01, Exercise 7. See 01-foundations/exercises.md.
// Choose a sensible character ceiling for model-visible tool output.
pub(crate) const MAX_OUTPUT_CHARS: usize = 0;

// Must run before tool output is appended to model-visible history. Character-based,
// intentionally lossy.
pub(crate) fn truncate_output(output: &str) -> String {
    // TODO — Module 01, Exercise 7. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 7 — see 01-foundations/exercises.md")
}

fn truncate_tool_result(result: ToolResult) -> ToolResult {
    match result {
        ToolResult::Ok(text) => ToolResult::Ok(truncate_output(&text)),
        ToolResult::Err(text) => ToolResult::Err(truncate_output(&text)),
    }
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

pub(crate) fn verify_sudoku(grid: &[[u8; 9]; 9]) -> Vec<String> {
    // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 9 — see 01-foundations/exercises.md")
}

pub(crate) fn parse_sudoku_grid(text: &str) -> Option<[[u8; 9]; 9]> {
    // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 9 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 10: The fabrication test
// ---------------------------------------------------------------------------

// Implementation: Judge whether a response answers the question or reports insufficient specification.
// Failure mode: A model may answer an underspecified question confidently; this judge cannot establish truth.
// Agentic coding lesson: Test fabrication pressure with controlled ambiguity, then verify factual claims with tools.

pub(crate) async fn evaluate_response(
    provider: &dyn ChatProvider,
    agent_response: &str,
) -> Result<String> {
    // TODO — Module 01, Exercise 10. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 10 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 11: Implement edit_file
// ---------------------------------------------------------------------------

// Implementation: Edit only a line range whose exact old text still matches.
// Failure mode: Broad or stale replacements overwrite unintended code and obscure review.
// Agentic coding lesson: Guarded, range-scoped edits reduce blast radius and preserve ownership.

/// Edits a file by replacing the first occurrence of `old_text` with
/// `new_text` within the inclusive line range `[line_start, line_end]`.
///
/// If the range is invalid or `old_text` is not found in that range, the file
/// is left unmodified and an `Error: ...` string is returned.
// Line range is 1-based and inclusive. Only the first occurrence is replaced (replacen).
// File stays unchanged if range is invalid or old_text is absent.
pub(crate) fn execute_edit_file(args: Value) -> ToolResult {
    // TODO — Module 01, Exercise 11. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 11 — see 01-foundations/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 01, Exercise 12: Implement the list_files tool
// ---------------------------------------------------------------------------

// Implementation: Return deterministic repository paths up to a caller-selected depth.
// Failure mode: Editing starts from an invented or incomplete understanding of project scope.
// Agentic coding lesson: Repository discovery should ground planning before modification begins.

pub(crate) fn execute_list_files(args: Value) -> ToolResult {
    // TODO — Module 01, Exercise 12. See 01-foundations/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 01, Exercise 12 — see 01-foundations/exercises.md")
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

pub(crate) fn load_instructions(
    work_dir: &Path,
    instructions_path: Option<&Path>,
) -> Option<String> {
    // TODO — Module 02, Exercise 1. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 1 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 2: Discover and load skills
// ---------------------------------------------------------------------------

// Implementation: Discover skill metadata eagerly and load full specialist guidance on demand.
// Failure mode: Loading everything crowds context; loading nothing omits needed expertise.
// Agentic coding lesson: Selective context improves decisions without permanently consuming the window.

pub(crate) fn discover_skills(skills_dir: Option<&Path>) -> Vec<SkillInfo> {
    // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 2 — see 02-context-engineering/exercises.md")
}

pub(crate) fn load_skill_content(skill_path: &Path) -> String {
    // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 2 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 3: Discover and execute command prompts
// ---------------------------------------------------------------------------

// Implementation: Discover prompt templates, strip metadata, and substitute invocation arguments.
// Failure mode: Rewritten ad hoc prompts drift and become difficult to inspect or reproduce.
// Agentic coding lesson: Commands turn recurring delegation patterns into explicit workflows.

pub(crate) fn discover_commands(commands_dir: Option<&Path>) -> Vec<CommandPrompt> {
    // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 3 — see 02-context-engineering/exercises.md")
}

pub(crate) fn execute_command(command: &CommandPrompt, args: &str) -> String {
    // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 3 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 4: Measure context usage
// ---------------------------------------------------------------------------

// Implementation: Measure system, conversation, and tool-schema characters against a budget.
// Failure mode: Context is removed or retained by intuition rather than actual pressure.
// Agentic coding lesson: Measure context before deciding what to preserve, remove, or compress.

pub(crate) fn measure_context(
    system_prompt: &str,
    conversation: &[ChatCompletionRequestMessage],
    tools: &[Tool],
    max_chars: Option<usize>,
) -> ContextUsage {
    // TODO — Module 02, Exercise 4. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 4 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 5: Compact conversation history
// ---------------------------------------------------------------------------

// Implementation: Format history for summarization and replace it with a coherent summary turn pair.
// Failure mode: Naïve truncation loses active constraints, decisions, and task state.
// Agentic coding lesson: Compaction trades detail for capacity, so preserve operational state.

// Strips system messages and normalizes into plain role:text transcript.
// The compaction prompt is written against this exact shape.
pub(crate) fn format_conversation_for_compaction(conversation: &Conversation) -> String {
    // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 5 — see 02-context-engineering/exercises.md")
}

// Preserves the original system prompt, replaces prior turns with summary, and injects
// a synthetic message_user acknowledgment so post-compaction history matches protocol shape.
pub(crate) fn apply_compaction(conversation: &mut Conversation, summary: &str) -> Result<()> {
    // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 5 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 6: Auto-compact on budget
// ---------------------------------------------------------------------------

// Implementation: Trigger compaction only when a configured context budget is exceeded.
// Failure mode: Late compaction degrades behavior; eager compaction destroys useful detail.
// Agentic coding lesson: Context budgets need deliberate thresholds rather than reactive cleanup.

pub(crate) fn should_auto_compact(usage: &ContextUsage) -> bool {
    // TODO — Module 02, Exercise 6. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 6 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 7: Save and resume conversation
// ---------------------------------------------------------------------------

// Implementation: Persist and load the stored prompt and turns faithfully; callers choose what to trust.
// Failure mode: Corrupt storage loses continuity, while trusting stale prompts preserves old authority.
// Agentic coding lesson: Separate persistence fidelity from resume policy—restore history, rebuild authority.

pub(crate) fn save_session(
    history_dir: &Path,
    session_id: &str,
    conversation: &[ChatCompletionRequestMessage],
) -> Result<()> {
    // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 7 — see 02-context-engineering/exercises.md")
}

pub(crate) fn load_session(
    history_dir: &Path,
    session_id: &str,
) -> Result<Option<Vec<ChatCompletionRequestMessage>>> {
    // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 7 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 8: Session-aware compaction
// ---------------------------------------------------------------------------

// Implementation: Select, summarize, and bound relevant prior sessions for the compaction prompt.
// Failure mode: Indiscriminate history becomes noise or consumes the current task's context budget.
// Agentic coding lesson: Cross-session continuity is useful only when it remains bounded and relevant.

pub(crate) async fn load_past_session_summaries(
    history_dir: &Path,
    current_session_id: &str,
    provider: &dyn ChatProvider,
) -> String {
    // TODO — Module 02, Exercise 8. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 8 — see 02-context-engineering/exercises.md")
}

/// Compress `content` to at most `max_chars` characters as a one-line topic
/// preview. Inputs that already fit are returned (after whitespace normalisation)
/// without an LLM call — preserves cost and determinism for short prompts. Inputs
/// that would otherwise be truncated mid-sentence are handed to the provider
/// with a "describe the topic in N chars" prompt; the final cap is applied
/// belt-and-suspenders if the model overshoots.
async fn summarize_topic(provider: &dyn ChatProvider, content: &str, max_chars: usize) -> String {
    let normalized = normalize_single_line(content);
    if normalized.len() <= max_chars {
        return normalized;
    }
    let prompt = format!(
        "Describe the topic of the following user message in {max_chars} characters or fewer.\n\
         Output ONLY the topic text — no preamble, no quotes, no trailing punctuation beyond what fits.\n\
         \n\
         ---\n\
         {normalized}"
    );
    let message: async_openai::types::ChatCompletionRequestMessage =
        async_openai::types::ChatCompletionRequestUserMessageArgs::default()
            .content(prompt)
            .build()
            .map(Into::into)
            .unwrap_or_else(|_| {
                async_openai::types::ChatCompletionRequestUserMessage::from("").into()
            });
    let response = provider.complete(&[message]).await.unwrap_or_default();
    let response = normalize_single_line(&response);
    if response.len() <= max_chars {
        response
    } else {
        let prefix = response
            .get(..max_chars.saturating_sub(3))
            .unwrap_or(&response);
        format!("{prefix}...")
    }
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 9: Extract memories
// ---------------------------------------------------------------------------

// Implementation: Extract durable facts and append only meaningful entries to the memory side channel.
// Failure mode: Transient or fabricated claims poison the context of future delegated work.
// Agentic coding lesson: Agent memory must be selective, durable, and reviewable.

pub(crate) async fn extract_memories(
    provider: &dyn ChatProvider,
    user_message: &str,
    agent_response: &str,
    work_dir: &Path,
) -> Result<()> {
    // TODO — Module 02, Exercise 9. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 9 — see 02-context-engineering/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 02, Exercise 10: Inject memories into context
// ---------------------------------------------------------------------------

// Implementation: Load saved memory so scaffolding can inject it explicitly into current context.
// Failure mode: Hidden or unbounded memory silently steers later tasks and competes with current rules.
// Agentic coding lesson: Persistent knowledge must stay visible, bounded, and subordinate to current authority.

pub(crate) fn load_memories(work_dir: &Path) -> Option<String> {
    // TODO — Module 02, Exercise 10. See 02-context-engineering/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 02, Exercise 10 — see 02-context-engineering/exercises.md")
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

fn matches_tool_pattern(pattern: &str, tool_name: &str, tool_args: &str) -> bool {
    if let Some(paren) = pattern.find('(') {
        let pat_name = &pattern[..paren];
        if pat_name != tool_name {
            return false;
        }
        let glob = pattern[paren + 1..].trim_end_matches(')');
        // The `(?s)` (dotall) flag makes `.` match newlines too — without it,
        // multi-line shell commands (heredocs, multi-line python -c) bypass
        // deny patterns like `shell(*sed -i*)` because the regex anchors
        // `^...$` only see the first line.
        let re_str = format!(
            "(?s)^{}$",
            glob.split('*')
                .map(regex::escape)
                .collect::<Vec<_>>()
                .join(".*")
        );
        regex::Regex::new(&re_str)
            .map(|re| re.is_match(tool_args))
            .unwrap_or(false)
    } else {
        pattern == tool_name
    }
}

pub(crate) fn check_tool_permission(
    tool_name: &str,
    tool_args: &str,
    allow_patterns: &[String],
    deny_patterns: &[String],
) -> PermissionResult {
    // TODO — Module 03, Exercise 1. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 1 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 2: Enforce sandbox boundaries
// ---------------------------------------------------------------------------

// Implementation: Check paths and protected files, then conservatively classify shell commands.
// Failure mode: Prompt-only analysis can approve an escaping or ambiguous command.
// Agentic coding lesson: Advisory model judgment is not an execution boundary or real isolation.

fn normalize_path(p: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in p.components() {
        match component {
            std::path::Component::ParentDir => {
                result.pop();
            }
            std::path::Component::CurDir => {}
            _ => result.push(component),
        }
    }
    result
}

pub(crate) fn enforce_sandbox(
    tool_name: &str,
    args: &Value,
    work_dir: &Path,
    protected_files: &[String],
) -> PermissionResult {
    // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 2 — see 03-guardrails-and-safety/exercises.md")
}

pub(crate) async fn analyze_shell_sandbox(
    provider: &dyn ChatProvider,
    command: &str,
    work_dir: &Path,
) -> Result<PermissionResult> {
    // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 2 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 3: Redact secrets
// ---------------------------------------------------------------------------

// Implementation: Apply configured patterns safely to text explicitly passed to this teaching primitive.
// Failure mode: Assuming an unwired helper protects output or logs leaves disclosure paths open.
// Agentic coding lesson: Treat every model and log channel as a surface that must be wired and verified.

pub(crate) fn redact_secrets(text: &str, patterns: &[String]) -> String {
    // TODO — Module 03, Exercise 3. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 3 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 4: Log audit events
// ---------------------------------------------------------------------------

// Implementation: Append structured JSONL events that reconstruct attempted and completed actions.
// Failure mode: A final agent narrative cannot prove what tools actually ran or changed.
// Agentic coding lesson: Auditable traces preserve operator accountability and support diagnosis.

pub(crate) fn log_audit_event(log_path: &Path, event: &AuditEvent) -> Result<()> {
    // TODO — Module 03, Exercise 4. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 4 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 5: Apply tool decorators
// ---------------------------------------------------------------------------

// Implementation: Wire ordered pre/post hooks, rewrites, denials, and feedback through the agent loop above.
// Failure mode: Prompt instructions can be bypassed at the exact moment an action is attempted.
// Agentic coding lesson: Enforce critical policy deterministically at tool boundaries.

/// Phase identifier handed to every decorator's `appliesTo` via
/// `AgentState::current_phase`.
///
/// Resolution order:
/// 1. `config.current_phase` — set explicitly by workflow callers.
///    ALWAYS prefer this when constructing `RunPhaseOptions` from a known
///    phase; the basename fallback below cannot recover the phase id from a
///    materialized inline prompt file like `<phase>.prompt.md` or
///    `phase-role-<pid>-<ns>.md`.
/// 2. Basename of `role_prompt` (legacy fallback) — works only when the
///    role prompt is a clean `<phase>.md`.
/// 3. `None` — no role prompt, no explicit phase (bare worker agent).
pub(crate) fn current_phase_from_config(config: &crate::config::AgentConfig) -> Option<String> {
    if let Some(p) = config.current_phase.as_ref() {
        return Some(p.clone());
    }
    config
        .role_prompt
        .as_ref()
        .and_then(|p| p.file_stem())
        .and_then(|s| s.to_str())
        .map(ToString::to_string)
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
// in the agent's work_dir; the first non-zero exit becomes a Deny carrying
// the gate's exit code + truncated stdout/stderr. The handle_turn loop
// surfaces that Deny back to the LLM as a fresh user-role turn, so the LLM
// sees the diagnostic and gets another iteration to fix the root cause.
// All-zero → Allow → message is delivered normally.

const EXIT_GATE_MAX_OUTPUT_BYTES: usize = 2048;

fn truncate_gate_output(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}\n…[truncated {} bytes]", &s[..max], s.len() - max)
    }
}

struct GateResult {
    code: i32,
    stdout: String,
    stderr: String,
}

fn run_gate(cmd: &str, cwd: &std::path::Path) -> GateResult {
    // sh -c "$cmd" gives users access to pipes / redirects / && etc.
    // We use the std blocking API; the deny path makes this synchronous
    // and the gate commands are short-lived (typecheckers, test runners).
    let mut child = match std::process::Command::new("sh")
        .arg("-c")
        .arg(cmd)
        .current_dir(cwd)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            return GateResult {
                code: 127,
                stdout: String::new(),
                stderr: format!("(spawn error) {e}"),
            };
        }
    };

    // 120s wall-clock cap.
    let start = std::time::Instant::now();
    let timeout = std::time::Duration::from_secs(120);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if start.elapsed() > timeout {
                    let _ = child.kill();
                    return GateResult {
                        code: 124,
                        stdout: String::new(),
                        stderr: format!("(gate timed out after 120s) `{}`", cmd),
                    };
                }
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(e) => {
                return GateResult {
                    code: 127,
                    stdout: String::new(),
                    stderr: format!("(wait error) {e}"),
                };
            }
        }
    }

    let output = match child.wait_with_output() {
        Ok(o) => o,
        Err(e) => {
            return GateResult {
                code: 127,
                stdout: String::new(),
                stderr: format!("(output error) {e}"),
            };
        }
    };
    GateResult {
        code: output.status.code().unwrap_or(1),
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
    }
}

pub fn exit_gate(commands: Vec<String>) -> crate::decorators::ToolDecorator {
    // TODO — Module 03, Exercise 6. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 6 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 7: Create and restore checkpoints
// ---------------------------------------------------------------------------

// Implementation: Copy checkpointed files back over current files; later-created files remain.
// Failure mode: Treating this partial restore as exact rollback leaves unwanted files behind.
// Agentic coding lesson: Recovery limits risk only when operators verify its actual semantics.

use std::sync::atomic::{AtomicU64, Ordering};
static CHECKPOINT_COUNTER: AtomicU64 = AtomicU64::new(0);

pub(crate) fn create_checkpoint(work_dir: &Path, checkpoints_dir: &Path) -> Result<CheckpointInfo> {
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")
}

fn copy_dir_all(src: &Path, dst: &Path) -> Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let dest = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_all(&entry.path(), &dest)?;
        } else {
            fs::copy(entry.path(), dest)?;
        }
    }
    Ok(())
}

pub(crate) fn restore_checkpoint(
    work_dir: &Path,
    checkpoint_id: &str,
    checkpoints_dir: &Path,
) -> bool {
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")
}

pub(crate) fn list_checkpoints(checkpoints_dir: &Path) -> Vec<CheckpointInfo> {
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")
}

// ---------------------------------------------------------------------------
// Module 03, Exercise 8: Sandbox in Lima VM
// ---------------------------------------------------------------------------

// Implementation: Adapt command execution to a separately provisioned Lima VM and return its result.
// Failure mode: A wrapper is mistaken for isolation despite missing provisioning or platform support.
// Agentic coding lesson: Real isolation is an operational boundary, not a stronger prompt warning.

pub(crate) fn execute_sandboxed_shell(command: &str, work_dir: &Path) -> ToolResult {
    // TODO — Module 03, Exercise 8. See 03-guardrails-and-safety/exercises.md and the
    // surrounding comments/docstring for the contract.
    todo!("Module 03, Exercise 8 — see 03-guardrails-and-safety/exercises.md")
}

pub(crate) fn execute_sandboxed_shell_with(
    limactl: &str,
    command: &str,
    work_dir: &Path,
) -> ToolResult {
    // TODO — Module 03, Exercise 8. Invoke the supplied Lima executable with
    // the exact adapter contract described in 03-guardrails-and-safety/exercises.md.
    // Return stdout on success and a useful ToolResult error for launch or exit failures.
    todo!("Module 03, Exercise 8 — see 03-guardrails-and-safety/exercises.md")
}
