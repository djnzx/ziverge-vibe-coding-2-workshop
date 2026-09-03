# Exercises

This module teaches context engineering — controlling what agents know, remember, and ignore — so your delegated work stays aligned with project needs.

Exercise implementations live in a dedicated exercises file per language (`exercises.ts`, `exercises.rs`, `Exercises.scala`). Scaffolding is in separate config/tools/terminal/agent files. Search for `// Module 02, Exercise N:` comments to find each exercise's location:

| Language | Source file | Exercise test command |
|---|---|---|
| TypeScript | `agent/ts/src/exercises.ts` | `npx tsx --test src/module02.exercises.test.ts` |
| Rust | `agent/rust/src/exercises.rs` | `cargo test module02_exercises` |
| Scala | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module02*"` |

Run exercise tests from each language's directory (`agent/ts/`, `agent/rust/`, `agent/scala/`). Rust exercise tests live in `src/tests/module02_exercises.rs` and are wired via `src/tests/mod.rs`.

### Quick Reference

| Ex | Function (TS / Scala) | Function (Rust) | Verify |
|---|---|---|---|
| 1 | `loadInstructions` | `load_instructions` | Unit tests: loads file, walks directory tree, concatenates in correct order |
| 2 | `discoverSkills` + `loadSkillContent` | `discover_skills` + `load_skill_content` | Unit tests: discovers skills from dir, parses frontmatter, loads content |
| 3 | `discoverCommands` + `executeCommand` | `discover_commands` + `execute_command` | Unit tests: discovers commands, substitutes $ARGUMENTS, returns prompt |
| 4 | `measureContext` | `measure_context` | Unit tests: correct character counts, percentage, categories |
| 5 | `formatConversationForCompaction` + `applyCompaction` | `format_conversation_for_compaction` + `apply_compaction` | Unit tests: formats transcript, replaces turns with summary |
| 6 | `shouldAutoCompact` | `should_auto_compact` | Unit tests: threshold check, null budget handling |
| 7 | `saveSession` + `loadSession` | `save_session` + `load_session` | Unit tests: round-trip save/load, missing file returns null |
| 8 | `loadPastSessionSummaries` | `load_past_session_summaries` | Unit tests: formats saved sessions for compaction prompt |
| 9 | `extractMemories` | `extract_memories` | Unit tests: LLM extracts facts, appends to memory file |
| 10 | `loadMemories` | `load_memories` | Unit tests: reads memory file, returns content for injection |

All 10 exercises are implementable with unit tests.

### Verification

Two methods to verify both correctness and context behavior in practice:

- **Exercise tests** -- fast, no LLM needed. Use the commands in the table above to validate core logic.
- **Integration tests** -- full end-to-end, calls OpenRouter. Run from `agent/`: `npx tsx test-harness/src/cli.ts --lang <ts|rust|scala>` (add `--verbose` to see full agent I/O) to observe context effects under real turns.

---

## Knowledge Loading (Exercises 1--3)

Teach the agent what it needs to know so delegated work aligns with project rules, domain knowledge, and reusable workflows.

### Exercise 1: Load persistent instructions

Find `// Module 02, Exercise 1:` in your source file. Implement `loadInstructions` (Rust: `load_instructions`).

The agent should load project-specific instructions and inject them into its context. This is how production agents read AGENTS.md or CLAUDE.md to adapt their behavior per-project.

Your function should:

1. If `config.instructions` is set, read that specific file and return its contents.
2. Otherwise, walk up the directory tree from `config.workDir` toward the filesystem root, collecting every file named `AGENTS.md`.
3. Concatenate collected files root-first, subdirectory-last (nearest directory gets final say).
4. Return the concatenated content, or null if nothing was found.

The scaffolding calls your function at startup and appends the result to the system prompt.

**Verify:** Unit tests pass -- loads explicit file, walks directory tree, concatenates in root-first order, returns null when no instructions exist, confirming instruction precedence behaves predictably.

### Exercise 2: Discover and load skills

Implement `discoverSkills` and `loadSkillContent` (Rust: `discover_skills` and `load_skill_content`).

Skills are markdown files with YAML frontmatter that provide domain knowledge. Production agents (Claude Code, OpenCode) use these to inject specialized instructions on demand.

`discoverSkills` should:

1. If `config.skillsDir` is null, return an empty list.
2. Scan the directory for subdirectories containing a `SKILL.md` file.
3. Parse the YAML frontmatter to extract `name` and `description`.
4. Return a list of `{ name, description, path }` objects.

`loadSkillContent` should:

1. Read the full content of a skill's `SKILL.md` file.
2. Return the entire file content (frontmatter + body).

The scaffolding injects skill descriptions (name + description only) into the system prompt at startup, keeping full content for on-demand loading.

**Verify:** Unit tests pass -- discovers skills from directory, parses frontmatter, handles empty/missing directory, loads full content, confirming the agent can ingest structured external knowledge.

### Exercise 3: Discover and execute command prompts

Implement `discoverCommands` and `executeCommand` (Rust: `discover_commands` and `execute_command`).

Command prompts are parameterized prompt templates stored as markdown files. Unlike skills (passive knowledge), commands are active: the user invokes them with `/command-name arguments`, and the template is sent to the LLM with `$ARGUMENTS` substituted.

`discoverCommands` should:

1. If `config.commandsDir` is null, return an empty list.
2. Scan the directory for `*.md` files.
3. Parse YAML frontmatter to extract `description` and optionally `argument-hint`.
4. Derive the command name from the filename (e.g., `code-review.md` becomes `code-review`).
5. Return a list of `{ name, description, argumentHint, path }` objects.

`executeCommand` should:

1. Read the markdown file for the matched command.
2. Strip the YAML frontmatter, keeping only the body.
3. Replace all occurrences of `$ARGUMENTS` with the provided arguments string.
4. Return the resulting prompt text.

The scaffolding extends `handleCommand` to check discovered commands before falling through to "unknown."

**Verify:** Unit tests pass -- discovers commands from directory, parses frontmatter, substitutes $ARGUMENTS, strips frontmatter from output, confirming slash commands can drive consistent delegation prompts.

---

## Context Management (Exercises 4--6)

Measure, compress, and manage the agent's finite context window so long-running delegated work stays coherent.

### Exercise 4: Measure context usage

Implement `measureContext` (Rust: `measure_context`).

Context windows are finite. Before you can manage them, you need to measure them. This function counts characters across the agent's context categories and reports usage.

Your function should:

1. Count characters in the system prompt (includes injected instructions, skills, memories).
2. Count characters across all conversation turns.
3. Count characters in tool schema definitions.
4. Sum the total. Compute percentage against `maxContextChars` (if set).
5. Return a structured breakdown: `{ system, conversation, tools, total, limit, percentage }`.

Character counting is a deliberate simplification. Production agents count tokens (roughly chars/4 for English), but character counting is deterministic and testable without a tokenizer.

**Verify:** Unit tests pass -- correct counts for known inputs, percentage computation, null limit returns null percentage, giving you reliable context telemetry for decision-making.

### Exercise 5: Compact conversation history

Implement `formatConversationForCompaction` and `applyCompaction` (Rust: `format_conversation_for_compaction` and `apply_compaction`).

When the context window fills up, the agent needs to compress its conversation history. Compaction works by sending the conversation to the LLM with a summarization prompt, then replacing the turns with the summary.

`formatConversationForCompaction` should:

1. Take the conversation history.
2. Strip system messages (they're in the system prompt, not the conversation).
3. Format remaining turns as a `role: content` transcript, one per line, separated by blank lines.

`applyCompaction` should:

1. Take the conversation and a summary string (produced by the LLM).
2. Replace all conversation turns with a single user message containing the summary.
3. Add a synthetic assistant response acknowledging the compaction (so the conversation alternates user/assistant correctly).
4. Preserve the original system prompt.

The scaffolding handles the `/compact` command dispatch and the LLM call. Your functions do the formatting and state mutation.

**Verify:** Unit tests pass -- formats transcript correctly, applies summary, preserves system prompt, maintains user/assistant alternation, validating controlled history compression.

### Exercise 6: Auto-compact on budget

Implement `shouldAutoCompact` (Rust: `should_auto_compact`).

Manual compaction via `/compact` works, but the agent should manage its own context. After each turn, check whether context usage exceeds the budget and compact automatically.

Your function should:

1. If `contextUsage.limit` is null, return false (no budget set).
2. If `contextUsage.total` exceeds `contextUsage.limit`, return true.
3. Otherwise return false.

The scaffolding calls this after each turn in the agent loop. When it returns true, the scaffolding triggers compaction using your Exercise 5 functions.

**Verify:** Unit tests pass -- never compacts without budget, compacts when over limit, does not compact when under limit, confirming automatic context-budget enforcement.

---

## Persistence (Exercises 7--8)

Save state so delegated work survives across sessions instead of resetting every run.

### Exercise 7: Save and resume conversation

Implement `saveSession` and `loadSession` (Rust: `save_session` and `load_session`).

When the user exits, the agent should save its conversation. When the user returns, the agent should resume where it left off.

`saveSession` should:

1. Serialize the conversation turns to JSON.
2. Include metadata: timestamp, working directory, model name.
3. Write to `{historyDir}/{sessionId}.json`.

`loadSession` should:

1. Read the session file from `{historyDir}/{sessionId}.json`.
2. Deserialize and reconstruct the conversation turns.
3. Return null if the file doesn't exist.

With `--history-dir` configured, the production turn path calls `saveSession` after each completed LLM turn. Restart with `--history-dir <dir> --resume <sessionId>` to restore the user/assistant turns before processing new input. The system prompt and injected project context are rebuilt from the current configuration rather than trusted from the saved file. Resuming without a history directory or with a missing session fails explicitly.

**Verify:** Unit tests pass -- save creates valid JSON, load restores turns, round-trip preserves content, and missing files return null. Then start the CLI with `--history-dir`, complete a turn, note the session ID written under that directory, and start a second process with `--resume <sessionId>` to confirm continuity across the process boundary.

### Exercise 8: Session-aware compaction

Implement `loadPastSessionSummaries` (Rust: `load_past_session_summaries`).

When compacting, the agent should know about previous sessions so the summary can reference prior work instead of losing that context entirely.

Your function should:

1. Scan `historyDir` for session JSON files (excluding the current session).
2. For each, read the metadata (timestamp, model) and the first user message (as a topic summary).
3. Format each as a one-line summary: `[timestamp] topic: first-user-message-truncated`.
4. Return the formatted text, or an empty string if no prior sessions exist.

The scaffolding injects this into the compaction prompt alongside the conversation transcript.

**Verify:** Unit tests pass -- finds prior sessions, formats summaries, excludes current session, returns empty string when none exist, preserving multi-session context during compaction.

---

## Memory (Exercises 9--10)

Learn durably from interactions so useful facts carry forward into future delegated tasks.

### Exercise 9: Extract memories

Implement `extractMemories` (Rust: `extract_memories`).

After each turn, the agent should extract durable facts from the conversation -- user preferences, project conventions, decisions made -- and store them in a memory file. This is a side-channel: it doesn't affect the current conversation, but future conversations will benefit.

Your function should:

1. Build a prompt asking the LLM to extract facts from the user message and agent response.
2. The prompt should instruct the LLM to return one fact per line, or "NONE" if nothing is worth remembering.
3. Call the provider with this prompt (a separate LLM call from the main conversation).
4. Parse the response into individual fact strings.
5. Append new facts to a memory file at `{workDir}/.agent/memories.md`, one per line, prefixed with `- `.

Not every turn produces memories. The LLM judges what's worth remembering.

**Verify:** Unit tests pass -- extracts facts from mock provider response, appends to file, handles "NONE" response, creates file if it doesn't exist, confirming durable memory capture.

### Exercise 10: Inject memories into context

Implement `loadMemories` (Rust: `load_memories`).

The flip side of extraction: load stored memories and make them available to the agent.

Your function should:

1. Look for a memory file at `{workDir}/.agent/memories.md`.
2. If it exists, read and return its contents.
3. If it doesn't exist, return null.

The scaffolding appends loaded memories to the system prompt under a "Things I remember from previous conversations:" header.

**Verify:** Unit tests pass -- reads existing memory file, returns null when missing, content matches file, confirming memory reinjection into future context.
