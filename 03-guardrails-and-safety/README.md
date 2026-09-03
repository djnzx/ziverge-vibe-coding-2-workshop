# Guardrails and Safety

How to constrain what the agent can do — and make it ask before acting.

Context shapes what the agent knows. Guardrails shape what it's allowed to do. Module 02 made the agent context-aware. This module makes it safe to operate.

## What You'll Learn

- Sandboxing: directory restrictions and the limits of a Lima command adapter
- Permissions: operator control over destructive actions
- Tool decorators: wrapping tools with validation, confirmation, and policy enforcement
- Secret scanning: preventing credential leakage into files
- Output validation: typed before/after decorators around tool execution
- Audit logging: recording what the agent did for post-hoc review
- Rollback and recovery: filesystem checkpoints when guardrails fail

## Setup

Most exercises modify one source file per language. Pick your language, install, and verify:

| Language | Install | Your file | Run exercise tests | Run all tests | Run agent |
|---|---|---|---|---|---|
| TypeScript | `cd agent/ts && npm install` | `agent/ts/src/exercises.ts` | `npx tsx --test src/module03.exercises.test.ts` | `npm test` | `npx tsx src/main.ts` |
| Rust | `cd agent/rust && cargo build` | `agent/rust/src/exercises.rs` | `cargo test module03_exercises` | `cargo test` | `cargo run --quiet` |
| Scala | `cd agent/scala && sbt compile` | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module03*"` | `sbt test` | `sbt run` |

Once built, run `./z` from the repo root to launch your agent — it auto-detects which language you built (or force with `--lang ts|rust|scala`). Agent flags pass through (e.g., `./z --lang ts --model anthropic/claude-haiku`). Scala users: `./z` requires the fat JAR (`sbt assembly`), not just `sbt compile`.

Search for `// Module 03, Exercise N:` comments in your file to find each exercise's location. Run the exercise test command to check your progress — tests for completed exercises pass, tests for unfinished exercises fail.

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

**Access Control** — Build and test the primitives that gate tools and paths;
Exercise 5 shows how deterministic policy is wired into the loop:
1. Check tool permissions to model explicit allow/deny policy at call time
2. Classify filesystem escapes and protected-file writes at a sandbox boundary

**Content Safety** — Build the redaction primitive required to keep credentials
out of context, logs, and user-visible responses when integrated at a boundary:
3. Redact configured secret patterns from text

**Observability** — Record what the agent does so decisions are reviewable:
4. Log audit events to support debugging, compliance, and post-hoc analysis

**Policy Enforcement** — Generalize guardrails into configurable decorators you can evolve without rewriting core logic:
5. Apply tool decorators to allow, deny, or transform behavior with typed before/after hooks
6. Build an exit-gate decorator to enforce termination invariants (the loop rejects `message_user` until shell-level gates pass)

**Recovery** — When guardrails fail, recover gracefully and contain blast radius:
7. Create and restore checkpoints to undo bad changes quickly
8. Build a Lima shell adapter for an operator-provisioned VM (the current loop does not select it automatically)
