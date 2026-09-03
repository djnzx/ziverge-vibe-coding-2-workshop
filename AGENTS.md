# AGENTS.md

## Repository Structure

This is a multi-language training workshop. Participants build the same toy
coding agent in TypeScript, Rust, or Scala through Modules 01–03, apply modern
direction and evaluation practices in Module 04, and complete one of two
capstone projects.

```
agent/
  ts/                    # TypeScript implementation
  rust/                  # Rust implementation
  scala/                 # Scala implementation
  system-prompt.txt      # Shared system prompt template
  compaction-prompt.txt  # Shared compaction prompt
  test-harness/          # YAML-driven integration harness
  tests/                 # YAML integration cases

01-foundations/          # Build and understand the agent loop
02-context-engineering/  # Control instructions, memory, and context
03-guardrails-and-safety/# Bound tools and validate behavior
04-directing-and-evaluating/
                        # README-only outline; exercises not yet designed
05-capstone/             # Shared choose-and-deliver workflow
capstones/               # TabbyShell and Snap projects, tests, and language workspaces

z                       # Run the built agent
```

Supported languages: **TypeScript**, **Rust**, and **Scala**.

## Cross-Language Consistency

1. Changes to agent behavior or exercises must be made in all three languages.
2. Keep the logical structure and observable behavior aligned while remaining
   idiomatic in each language.
3. Add equivalent tests in all three languages for shared behavior.
4. Build and test every affected implementation before considering a change
   complete.

Capstone participants implement and test the language present in their project
workspace.


## Code Quality

- Prefer strong static types and algebraic data types for closed alternatives.
- Make illegal states unrepresentable where practical.
- Keep a tool's schema, execution, and description close together.
- Extract shared logic when it removes meaningful duplication, not merely to
  create another layer.
- Preserve each language's conventions; do not write Java in Scala or C++ in
  Rust.

## Language Conventions

### TypeScript (`agent/ts/`)

- Node.js, npm, and the official `openai` SDK pointed at OpenRouter
- Zod and `zod-to-json-schema` for schemas
- Build/run: `npm install && npx tsx src/main.ts`

### Rust (`agent/rust/`)

- Rust 2021 and `async-openai` with an OpenRouter configuration
- Serde/serde_json and schemars
- Build/run: `cargo build && cargo run`

### Scala (`agent/scala/`)

- Scala 3 and sbt
- `sttp-openai` pointed at OpenRouter; ZIO Blocks for JSON and schemas
- Build/run: `sbt compile && sbt run`

## Agent Configuration

`AgentConfig` controls model, tools, prompts, iteration budget, working
directory, context engineering, workflow composition, and guardrails. The same
agent core can be embedded in custom workflows through `runPhase` / `run_phase`
without requiring a dedicated orchestration subsystem.

The agent provides ten tools: `read_file`, `write_file`, `edit_file`, `shell`,
`list_files`, `web_fetch`, `message_user`, `ask_user`, `read_board`, and
`update_board`. The last three support user-defined interactive and multi-phase
workflows; they are not tied to a workshop module.

All implementations expect `OPENROUTER_API_KEY`. Never hardcode credentials.

## Decorators and Output Validation

Module 03 decorators are typed code values that intercept tool calls before or
after execution. Each custom workflow assembles its own list and passes it to
`runPhase` / `handleTurn`.

A `ToolDecorator` has:

- `name`
- `appliesTo(invocation, state) -> bool`
- optional `before(invocation, state) -> Allow | Deny`
- optional `after(invocation, result, state) -> Allow | Deny`

Module 03 Exercise 5 wires decorators into the loop; Exercise 6 builds the exit
gate that prevents `message_user` until verification commands succeed.

## Agent Protocol

The agent reads JSON lines from stdin and writes one JSON result per message:

```json
{"content": "Create hello.txt"}
{"content": "Created hello.txt.", "tool_calls": ["write_file"]}
```

Human-readable logs go to stderr. `/quit`, `/exit`, and `/compact` are available
in every mode. Interactive mode adds terminal rendering and multiline input;
`--file <path>` processes sections separated by `---`.

Tests assert outcomes and observable tool use, not exact wording or internal
iteration counts.

## Test Organization

Exercises in Modules 01–03 share the agent exercise files:

- `agent/ts/src/exercises.ts`
- `agent/rust/src/exercises.rs`
- `agent/scala/src/main/scala/Exercises.scala`

Each module has one exercise test file per language:

| Language | Module 01 | Module 02 | Module 03 |
|---|---|---|---|
| TypeScript | `src/module01.exercises.test.ts` | `src/module02.exercises.test.ts` | `src/module03.exercises.test.ts` |
| Rust | `src/tests/module01_exercises.rs` | `src/tests/module02_exercises.rs` | `src/tests/module03_exercises.rs` |
| Scala | `src/test/scala/Module01ExercisesTest.scala` | `src/test/scala/Module02ExercisesTest.scala` | `src/test/scala/Module03ExercisesTest.scala` |

Module 04 currently has no exercises or implementation. Do not invent exercise
markers or test files until its exercise design is approved.

Scaffolding tests remain separate from attendee exercise tests:

- TypeScript: `config.test.ts`, `commands.test.ts`, `terminal.test.ts`,
  `scaffolding.test.ts`, and concern-specific tests
- Rust: `src/tests/scaffolding.rs` and concern-specific unit tests
- Scala: concern-specific MUnit suites under `src/test/scala/`

Manual exercises intentionally have no empty placeholder tests. Test utilities
live alongside the interfaces they mock so attendees can see the production
boundary and its test double together.
