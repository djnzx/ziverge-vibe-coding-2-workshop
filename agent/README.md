# Agent

The toy coding agent used in Modules 01–03. Each module's exercises augment this code. The same codebase can run different agents (worker, judge, orchestrator) by varying the configuration.

## Prerequisites

- `OPENROUTER_API_KEY` environment variable set (or in `.env` at the repo root;
  the `./z` launcher loads it before changing into a language directory)
- Node.js (required for the test harness)
- Java 11+ on `PATH` (required for the Scala fat JAR)

## Quick Start

From the repo root, `./z` auto-detects which language you built and launches it (or force one with `--lang ts|rust|scala`). Agent flags pass through after launcher args: `./z --lang ts --model anthropic/claude-haiku`. The per-language commands below are equivalent:

### TypeScript

```bash
cd agent/ts
npm install
npx tsx src/main.ts                       # interactive mode
echo '{"content":"hello"}' | npx tsx src/main.ts  # protocol mode
npx tsx src/main.ts --file /path/to/prompts.txt   # file mode
```

### Rust

```bash
cd agent/rust
cargo build
cargo run --quiet                          # interactive mode
echo '{"content":"hello"}' | cargo run --quiet  # protocol mode
cargo run --quiet -- --file /path/to/prompts.txt   # file mode
```

### Scala

```bash
cd agent/scala
sbt compile
sbt run                                    # interactive (all modes work via sbt)
```

## Configuration

The agent's behavior is controlled by `AgentConfig`. All fields have sensible defaults; override via CLI flags or a JSON config file:

```bash
# Load all settings from a file
npx tsx src/main.ts --config agent.json

# Config file + CLI overrides (CLI wins)
npx tsx src/main.ts --config agent.json --model anthropic/claude-haiku
```

**Foundations (Module 01):**

| Flag | Default | Purpose |
|---|---|---|
| `--model <model>` | `anthropic/claude-sonnet-4` | LLM model (also `MODEL` env var) |
| `--prompt <path>` | `../system-prompt.txt` | System prompt template file |
| `--tools <t1,t2,...>` | all tools | Comma-separated tool names to enable |
| `--work-dir <path>` | current directory | Working directory for file/shell tools |
| `--max-iterations <n>` | `20` | Turn budget before forced stop |
| `--temperature <n>` | `0` | Sampling temperature |
| `--verbose` | off | Show agent-loop debug output on stderr |

**Context Engineering (Module 02):**

| Flag | Default | Purpose |
|---|---|---|
| `--instructions <path>` | none | Path to AGENTS.md or instructions file |
| `--skills-dir <path>` | none | Directory containing skill/knowledge files |
| `--commands-dir <path>` | none | Directory with command prompt templates |
| `--history-dir <path>` | none | Directory for conversation state persistence |
| `--resume <session-id>` | none | Restore saved user/assistant turns from `--history-dir`; rebuild the system prompt from current configuration |
| `--max-context-chars <n>` | none | Context budget; auto-compact when exceeded |

**Guardrails and Safety (Module 03):**

| Flag | Default | Purpose |
|---|---|---|
| `--allow-tools <patterns>` | none | Tool patterns that run without confirmation |
| `--deny-tools <patterns>` | none | Tool patterns that are blocked entirely |
| `--protected-files <patterns>` | none | File patterns the agent cannot modify |
| `--secret-patterns <patterns>` | common API key formats | Patterns to detect credential leakage |
| `--audit-log <path>` | none | Path to audit log file |
| `--sandbox-vm` | off | Request Lima sandbox mode; currently parsed but not dispatched by the agent loop (see Module 03, Exercise 8) |
| `--exit-gate <cmd>` (repeatable) | none | Shell commands that must each exit 0 before the agent may terminate via `message_user`. The exit-gate decorator denies premature `message_user` calls and feeds the gate's exit code + stdout/stderr back as a turn so the LLM can fix and retry. |

**Workflow composition:**

| Flag | Default | Purpose |
|---|---|---|
| `--role-prompt <path>` | none | Role prompt file (layered after base system prompt) |
| `--input-files <paths>` | none | Comma-separated files (glob supported) to prepend to user message as context |
| `--output-file <path>` | none | Write response content to this file |
| `--input-format <fmt>` | `json` | Input format: `json` (protocol) or `text` (plain text) |
| `--output-format <fmt>` | `json` | Output format: `json` (protocol) or `text` (plain text) |

Examples:

```bash
# Default worker agent
npx tsx src/main.ts

# Judge agent: cheap model, no tools, 1 iteration
npx tsx src/main.ts --model anthropic/claude-haiku --tools "" --max-iterations 1 --prompt judge-prompt.txt

# Restricted worker: only file tools, lower budget
npx tsx src/main.ts --tools read_file,write_file,edit_file --max-iterations 10

# Worker with guardrails: protected files, audit log
npx tsx src/main.ts --protected-files "*.env,secrets/*" --audit-log ./audit.log --deny-tools shell
```

Mode flags (`--interactive`, `--protocol`, `--file <path>`) control I/O, not agent identity.

## Unit Tests

```bash
# TypeScript
cd agent/ts && npx tsx --test src/*.test.ts

# Rust
cd agent/rust && cargo test

# Scala
cd agent/scala && sbt test
```

## Integration Tests (Harness)

```bash
cd agent

# Install harness dependencies (once)
cd test-harness && npm install && cd ..

# Run against your language
npx tsx test-harness/src/cli.ts --lang ts
npx tsx test-harness/src/cli.ts --lang rust
npx tsx test-harness/src/cli.ts --lang scala
```

Add `--verbose` to see full agent stdin/stdout/stderr per test step.

## Interactive Mode

Detects TTY automatically. Flags: `--interactive`, `--protocol`, `--file <path>`.

Backslash continuation: lines ending with `\` are concatenated for multiline input.

Slash commands: `/quit`, `/exit`, `/compact`.

## File Structure

Each implementation is split by concern rather than forced into an identical
file count. TypeScript and Rust generally use one lowercase file per concern;
Scala uses focused PascalCase files where its types benefit from separate
homes. In every language, look for these ownership boundaries:

1. configuration and CLI parsing — `config.ts`, `config.rs`, or Scala's `AgentConfig.scala`, `AgentConfigOverrides.scala`, and `CliConfig.scala`
2. tool ADTs, schemas, implementations, and selection — `tools.ts`, `tools.rs`, or Scala's `Tool*.scala` files
3. terminal and recording test terminal — `terminal.ts`, `terminal.rs`, or Scala's `TerminalOutput.scala` and `TerminalEvent.scala`
4. conversation/provider types and session persistence — the conversation and session modules for that language
5. `exercises.ts` / `exercises.rs` / `Exercises.scala` — **attendee exercise implementations** (all `// Module NN, Exercise N:` functions):
   - `buildSystemPrompt` / `build_system_prompt` (M01 Exercise 1)
   - `parseToolCalls` / `parse_tool_calls` (M01 Exercise 2)
   - `handleTurn` / `handle_turn` (M01 Exercise 3)
   - `executeReadFile` / `execute_read_file` (M01 Exercise 4)
   - `executeShell` / `execute_shell` (M01 Exercise 5)
   - `truncateOutput` / `truncate_output` (M01 Exercise 7)
   - `executeEditFile` / `execute_edit_file` (M01 Exercise 11)
   - Plus all Module 02 and Module 03 exercise functions
6. agent orchestration, protocol/interactive drivers, and command handling — `agent.ts`, `agent.rs`, or Scala's `Agent.scala` plus driver modules
7. `main.ts` / `main.rs` / `Main.scala` — thin entry point
