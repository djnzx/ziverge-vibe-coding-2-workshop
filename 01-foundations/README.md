# Foundations

Build a coding agent from scratch to develop a mental model of how LLM-based agents work at the lowest level.

## What You'll Learn

- **Agent internals**: How an agent loop works — system prompt → LLM call → parse tool calls → execute → feed results back → repeat
- **System prompts**: Why prompt design determines agent behavior, and how to iterate on it
- **Tool grounding**: How tools bridge the gap between LLM text and real-world actions — and why they're essential for correctness
- **Fluency ≠ correctness**: LLM output looks right but often isn't — flat-format tool calls, hallucinated results, multi-action responses
- **Context windows**: How finite context constrains behavior, why you must truncate tool output and manage conversation history
- **Model selection and tuning**: Choosing models for capability, cost, and speed; temperature and sampling parameters
- **LLMs don't think**: They mine training data for patterns. Auto-regressive generation breaks on constraint satisfaction (Sudoku). Without tools, they fabricate confidently (hallucinated APIs)

## Setup

Most exercises modify one source file per language (Exercise 6 also edits `agent/system-prompt.txt`). Pick your language, install, and verify:

| Language | Install | Your file | Run exercise tests | Run all tests | Run agent |
|---|---|---|---|---|---|
| TypeScript | `cd agent/ts && npm install` | `agent/ts/src/exercises.ts` | `npx tsx --test src/module01.exercises.test.ts` | `npm test` | `npx tsx src/main.ts` |
| Rust | `cd agent/rust && cargo build` | `agent/rust/src/exercises.rs` | `cargo test module01_exercises` | `cargo test` | `cargo run --quiet` |
| Scala | `cd agent/scala && sbt compile` | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module01*"` | `sbt test` | `sbt run` |

Once built, run `./z` from the repo root to launch your agent — it auto-detects which language you built (or force with `--lang ts|rust|scala`). Agent flags pass through (e.g., `./z --lang ts --model anthropic/claude-haiku`). Scala users: `./z` requires the fat JAR (`sbt assembly`), not just `sbt compile`.

Search for `// Module 01, Exercise N:` comments in your file to find each exercise's location. Run the exercise test command to check your progress — tests for completed exercises pass, tests for unfinished exercises fail.

You'll also need `OPENROUTER_API_KEY` set for exercises that call the LLM (starting at Exercise 4). See the [agent README](../agent/README.md) for file mode and full configuration.

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

**Infrastructure** — Build the machine so you can see how agent internals actually work (verified with unit tests):
1. Build the system prompt to see how prompt design shapes agent behavior
2. Parse tool calls from LLM text to understand how models translate intent into actions
3. Implement the agent loop to internalize the step-by-step execution model

**Capabilities** — Give it tools so outputs can be grounded in real system state (harness tests light up):
4. Implement `read_file` to ground answers in actual project files
5. Implement `shell` to let the agent verify claims by executing commands

**Behavior** — Control what the agent does so delegation stays reliable:
6. Iterate on the system prompt to reduce hallucinations and multi-action drift
7. Harden the loop to prevent common failure modes under load

**Exploration** — Observe, experiment, and build judgment about model limits:
8. Model selection and tuning to learn capability/cost/reliability tradeoffs
9. Sudoku — the reasoning illusion to see where pure language reasoning breaks
10. The fabrication test to recognize when ungrounded outputs look confident but false

**Mastery** — Deeper tools and independent design for stronger delegation instincts:
11. Implement `edit_file` to practice precise, safe file mutations
12. Implement the `list_files` tool to apply the full schema→execution→registration workflow
