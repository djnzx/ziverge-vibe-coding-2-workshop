# Context Engineering

How to control what the agent knows — and what it forgets.

Module 01 gave you a working baseline for understanding agent internals. It follows a system prompt, uses tools, and loops until done. But it's static: same prompt every time, no awareness of the project it's working in, no way to manage its growing context. This module teaches you to control context so delegated work is more reliable.

## What You'll Learn

- How persistent instructions (AGENTS.md) configure agent behavior per-project without changing code
- Context management: what to include, what to exclude, measuring usage, detecting stale or conflicting content
- How to load and inject domain-specific knowledge (skills, reference docs) into context
- How path-specific rules with inheritance let the agent adapt to different parts of a codebase
- Compaction: compressing conversation history to free context space
- How to persist agent state across sessions so work survives a context window reset

## Setup

Most exercises modify one source file per language. Pick your language, install, and verify:

| Language | Install | Your file | Run exercise tests | Run all tests | Run agent |
|---|---|---|---|---|---|
| TypeScript | `cd agent/ts && npm install` | `agent/ts/src/exercises.ts` | `npx tsx --test src/module02.exercises.test.ts` | `npm test` | `npx tsx src/main.ts` |
| Rust | `cd agent/rust && cargo build` | `agent/rust/src/exercises.rs` | `cargo test module02_exercises` | `cargo test` | `cargo run --quiet` |
| Scala | `cd agent/scala && sbt compile` | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module02*"` | `sbt test` | `sbt run` |

Once built, run `./z` from the repo root to launch your agent — it auto-detects which language you built (or force with `--lang ts|rust|scala`). Agent flags pass through (e.g., `./z --lang ts --model anthropic/claude-haiku`). Scala users: `./z` requires the fat JAR (`sbt assembly`), not just `sbt compile`.

Search for `// Module 02, Exercise N:` comments in your file to find each exercise's location. Run the exercise test command to check your progress — tests for completed exercises pass, tests for unfinished exercises fail.

For Exercise 7, configure `--history-dir <dir>`. The session filename (without `.json`) is its ID; restart with `--history-dir <dir> --resume <session-id>` to continue it.

## Integration Tests

Integration tests use the YAML-driven test harness to verify the agent end-to-end (requires `OPENROUTER_API_KEY`). Run from the `agent/` directory:

```bash
cd agent/test-harness && npm install && cd ..   # one-time setup
npx tsx test-harness/src/cli.ts --lang ts       # or rust, or scala
npx tsx test-harness/src/cli.ts --lang ts --verbose  # full agent I/O
```

For Scala, the harness builds the fat JAR automatically — just ensure Java 11+ is on your PATH.

## Exercises

See [exercises.md](exercises.md) for detailed instructions and a quick-reference table.

**Knowledge Loading** — Teach the agent what it needs to know so it can adapt to your project:

1. Load persistent instructions to enforce project-specific operating rules
2. Discover and load skills to inject domain knowledge on demand
3. Discover and execute command prompts to turn reusable workflows into fast delegation

**Context Management** — Measure and control what fits so context limits don't silently degrade quality:
4. Measure context usage to make context pressure visible before failures compound
5. Compact conversation history to preserve key state while freeing budget
6. Auto-compact on budget so long sessions remain stable without manual intervention

**Persistence** — Survive context resets so work can continue across sessions:
7. Save and resume conversation to maintain continuity after restarts
8. Session-aware compaction to preserve prior-session signal during summarization

**Memory** — Extract and recall facts to improve future delegation quality:
9. Extract memories to capture durable user and project facts
10. Inject memories into context so prior decisions influence new work
