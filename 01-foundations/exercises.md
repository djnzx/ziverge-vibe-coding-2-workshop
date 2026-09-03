# Exercises

Each exercise builds on the previous. The scaffolding is provided: types, schemas, project config, entry point, terminal UI, `write_file` and `message_user` tools. You implement the core logic to build a practical mental model for delegating to coding agents.

Exercise implementations live in a dedicated exercises file per language (`exercises.ts`, `exercises.rs`, `Exercises.scala`). Scaffolding is in separate config/tools/terminal/agent files. Search for `// Module 01, Exercise N:` comments to find each exercise's location:

| Language | Source file | Exercise test command |
|---|---|---|
| TypeScript | `agent/ts/src/exercises.ts` | `npx tsx --test src/module01.exercises.test.ts` |
| Rust | `agent/rust/src/exercises.rs` | `cargo test module01_exercises` |
| Scala | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module01*"` |

Run exercise tests from each language's directory (`agent/ts/`, `agent/rust/`, `agent/scala/`). Rust exercise tests live in `src/tests/module01_exercises.rs` and are wired via `src/tests/mod.rs`.

### Quick Reference

| Ex | Function (TS / Scala) | Function (Rust) | Verify |
|---|---|---|---|
| 1 | `buildSystemPrompt` | `build_system_prompt` | Unit tests: prompt contains tool names, protocol, schemas |
| 2 | `parseToolCalls` | `parse_tool_calls` | Unit tests: single, multiple, empty, malformed, missing name |
| 3 | `handleTurn` | `handle_turn` | Unit tests: tool execution, nudging, max iterations, multi-turn |
| 4 | `executeReadFile` | `execute_read_file` | Integration: "Read file and respond" |
| 5 | `executeShell` | `execute_shell` | Integration: "Shell command execution" |
| 6 | `agent/system-prompt.txt` | `agent/system-prompt.txt` | Harness experiment + unit comparison against the provided naive baseline |
| 7 | `truncateOutput` + loop hardening | `truncate_output` + loop hardening | Unit tests: flat format, truncation, history pruning |
| 9 | `verifySudoku` + `parseSudokuGrid` | `verify_sudoku` + `parse_sudoku_grid` | Unit tests + integration: head fails, tools succeed |
| 10 | `evaluateResponse` | `evaluate_response` | Unit tests: judge classifies fabrication vs refusal |
| 11 | `executeEditFile` | `execute_edit_file` | Unit tests + integration |
| 12 | `executeListFiles` | `execute_list_files` | Unit tests + integration: list_files tool |

Exercise 8 is manual/observational — no code to write, no tests to pass.

### Verification

Two methods to validate both implementation correctness and real agent behavior:

- **Exercise tests** — fast, no LLM needed. Use the commands in the table above to verify core mechanics.
- **Integration tests** — full end-to-end, calls OpenRouter. Run from `agent/`: `npx tsx test-harness/src/cli.ts --lang <ts|rust|scala>` (add `--verbose` to see full agent I/O) to observe how those mechanics affect real behavior.

---

## Infrastructure

Build the core loop to understand how agents execute work step by step. Verified with unit tests — the harness won't work yet.

### Exercise 1: Build the system prompt

Find `// Module 01, Exercise 1:` in your source file. Implement `buildSystemPrompt` (Rust: `build_system_prompt`).

The file `agent/system-prompt.txt` contains a template with a `{{TOOL_DESCRIPTIONS}}` placeholder. Your function should:

1. For each tool, format its name, description, and JSON Schema (derived from the parameter types) into a readable block.
2. Load the template file.
3. Replace the placeholder with the formatted tool descriptions.
4. Return the complete prompt.

This is what the model sees. If you get it wrong, the model won't know tools exist.

**Verify:** System prompt unit tests pass — the prompt contains all tool names, the `<tool_call>` protocol, and JSON schemas, confirming the model receives reliable tool affordances.

### Exercise 2: Parse tool calls from LLM text

The model responds with free-form text that may contain `<tool_call>...</tool_call>` blocks with JSON inside. Implement `parseToolCalls` to find these blocks, parse the JSON, and return structured tool call objects (name + arguments).

Malformed JSON and blocks missing the `name` field should be reported as errors — not silently skipped. The agent loop feeds these errors back to the model so it can fix its JSON and try again.

**Verify:** All parsing unit tests pass — single call, multiple calls, no calls, malformed JSON (reported as error), missing fields (reported as error), mixed valid + broken (valid extracted, broken reported), confirming the loop can recover from imperfect model output.

### Exercise 3: Implement the agent loop

This is the core exercise. Implement `handleTurn`:

1. Add the user's message to the conversation history.
2. Call the LLM via the provider.
3. Parse tool calls from the response.
4. If the tool is `message_user`, the task is done — return the message as the response.
5. If it's any other tool, execute it and add the result to the conversation as a user message. Continue the loop.
6. If there are no tool calls, nudge the model to use a tool.
7. Limit iterations to prevent runaway loops.

Only execute the first tool call per response. The model should call one tool at a time.

**Verify:** All e2e mock tests pass (these use a fake provider, no LLM needed). At this point, the scaffolded `write_file` tool works — try the harness's "Basic file creation" test with `--verbose` to watch the loop in action and connect tests to runtime behavior.

---

## Capabilities

Give the agent tools. The loop works — now ground outputs in real system state and light up the harness tests.

### Exercise 4: Implement `read_file`

Study the provided `write_file` tool. Note how the schema defines parameters, how `execute` validates arguments and returns a string, and how errors are returned as strings rather than thrown.

Implement `read_file`: read the file at the given path and return its contents. Return an error string if the file doesn't exist.

**Verify:** The "Read file and respond" harness test passes — the agent creates a file, then reads it back in a follow-up turn, demonstrating evidence-based responses.

### Exercise 5: Implement `shell`

Implement the `shell` tool. Run the given command via a shell subprocess. Return stdout on success. On failure, return both stdout and stderr — the model needs both to understand what went wrong. Enforce a 30-second timeout.

**Verify:** The "Shell command execution" harness test passes. Run with `--verbose` and observe: does the model use the shell correctly? Does it get the right answer about which OS this is? This validates tool-grounded reasoning instead of guessing.

---

## Behavior

Same code, different outcomes. Control what the agent does through prompt design and loop hardening so delegation stays predictable.

### Exercise 6: Iterate on the system prompt

Unlike the surrounding implementation exercises, this exercise edits the shared
`agent/system-prompt.txt`, not the language-specific exercises file. The
`NAIVE_SYSTEM_PROMPT` constant beside the Exercise 6 source marker is a provided
test fixture: unit tests use it to make the before/after comparison explicit.

Replace `agent/system-prompt.txt` with this naive prompt:

```
You are a coding agent. You can use tools to interact with the filesystem
and run shell commands.

To use a tool, respond with a JSON object wrapped in <tool_call> tags:

<tool_call>
{"name": "tool_name", "arguments": {"param": "value"}}
</tool_call>

You may include reasoning or commentary before or after the tool call.
When you have completed the task, use the message_user tool.
```

Run the harness with `--verbose`. Observe:
- The model writes text after tool calls, including hallucinated tool results.
- The model claims to have performed actions it hasn't actually performed.
- The model tries to do everything in one response instead of one step at a time.

Now iterate. Your goal: get all harness tests passing consistently. Consider:
- Does the model know it's in a multi-turn loop?
- Does it know to stop after a tool call?
- Does it know not to guess what a tool will return?

Compare your final prompt against the defensive goals above and the unit-test
expectations. The same `agent/system-prompt.txt` remains your editable prompt.

**Verify:** All harness tests pass consistently with your prompt. Run the "Shell command execution" test `--verbose` — the model should report the actual OS, not a hallucinated one, showing how prompt constraints shape reliability.

### Exercise 7: Harden the loop

Three fixes, one exercise. Run `--verbose` on several harness runs and observe these failure modes:

**Flat-format tool calls.** The model sometimes omits the `arguments` wrapper:
```json
{"name": "write_file", "path": "hello.txt", "content": "hello"}
```
Fix the parser: if `arguments` is missing, collect all non-`name` fields as the arguments.

**Output blowup.** Have the agent read a large file. Subsequent turns degrade because the conversation history is too large. Implement `truncateOutput`: if output exceeds 10,000 characters, truncate and append `[truncated — N more chars]`.

**History pollution.** The model sometimes emits multiple tool calls in one response. Only the first executes, but the full response stays in history — teaching the model that multi-call responses are accepted. Fix: truncate the assistant message at the first `</tool_call>` before adding it to history.

**Verify:** Flat-format, truncation, and history truncation unit tests all pass. The harness passes consistently, showing the loop remains robust under common model failure modes.

---

## Exploration

Observe, experiment, and develop intuition for where models are strong, weak, and risky to trust blindly.

### Exercise 8: Model selection and tuning

Run the same task with different models and temperatures. Use the `--model` and `--temperature` flags:

```bash
# Two model IDs available through your OpenRouter account
npx tsx src/main.ts --model <capable-model-id> --temperature 0
npx tsx src/main.ts --model <cheaper-model-id> --temperature 0

# Same capable model, higher temperature
npx tsx src/main.ts --model <capable-model-id> --temperature 0.7
```

Try a multi-step task (e.g., "Create a Python script that generates fibonacci numbers, then run it and show the first 20") with each configuration. Compare:

- **Quality**: Does the cheaper model make more mistakes? Does it follow the one-tool-call protocol as reliably?
- **Speed**: How much faster is the cheaper model?
- **Temperature**: Does temperature 0 produce identical results across runs? Does 0.7 produce more varied but sometimes better solutions?

**What you're seeing:** Model selection is a tradeoff — capability vs cost vs speed. Temperature controls the randomness/creativity tradeoff. These are knobs you'll use throughout the workshop when matching an agent to a role, such as a cheap judge or a capable worker.

### Exercise 9: Sudoku — the reasoning illusion

Find `// Module 01, Exercise 9:` in your source file. Implement `verifySudoku` and `parseSudokuGrid` (Rust: `verify_sudoku` and `parse_sudoku_grid`).

Build a Sudoku verifier that checks a 9×9 grid against all 27 constraints (9 rows, 9 columns, 9 boxes). This is a *deterministic eval* — a programmatic check that scores structured LLM output with zero ambiguity. It demonstrates the auto-regressive bound — models can't do constraint satisfaction in one shot.

`verifySudoku` should:

1. Take a 9×9 grid of integers.
2. Check each row contains exactly the digits 1–9.
3. Check each column contains exactly the digits 1–9.
4. Check each 3×3 box contains exactly the digits 1–9.
5. Return a list of violation descriptions (e.g., "Row 1: [5, 3, 3, ...]").

`parseSudokuGrid` should:

1. Take free-form text (the model's response).
2. Extract lines with 9 space-separated digits.
3. Return a 9×9 grid, or null if the text doesn't contain a valid grid.

The integration tests demonstrate the insight: ask the model to solve a puzzle in its head (no tools) — the verifier finds violations. Ask it to write a solver script and run it (with tools) — the verifier confirms a valid solution. Tools push past the auto-regressive bound.

**Verify:** Sudoku unit tests pass. Integration tests: "Sudoku - solving in head fails verification" passes (model's grid is INVALID), "Sudoku - solving with tools succeeds" passes (scripted solution is VALID), reinforcing when to delegate computation to tools.

### Exercise 10: The fabrication test

Find `// Module 01, Exercise 10:` in your source file. Implement `evaluateResponse` (Rust: `evaluate_response`).

Build a fabrication detector that uses the LLM itself as a judge — an *LLM-as-judge eval*. When an agent responds to a question, a second LLM call evaluates whether the response provided specific data (fabrication) or refused to answer (honest). This is the same pattern used in production eval suites to measure output quality at scale.

Your function should:

1. Take a ChatProvider and the agent's response text.
2. Build a judge prompt asking: "Did the agent (a) provide a specific answer with concrete data, or (b) refuse to answer due to lack of information?"
3. Call the provider with the judge prompt.
4. Return "SPECIFIED" or "UNSPECIFIED" based on the verdict.

The integration test demonstrates fabrication: ask the agent to run a fake command (`zephyrdb query`) using the shell tool. The shell returns "not found" and the agent honestly reports the error — tools ground the agent in reality. Without tools, models fabricate confident answers about things that don't exist.

**Verify:** Fabrication detection unit tests pass. Integration test: "Fabrication - tools ground the agent" passes (agent uses shell, reports the error), helping you recognize and prevent confident fabrication.

---

## Mastery

Deeper tools and independent design to strengthen your judgment about safe agent capabilities.

### Exercise 11: Implement `edit_file`

Implement the `edit_file` tool. Given a file path, a line range (1-indexed start and end), and old/new text:

1. Read the file and split into lines.
2. Validate the line range (start ≥ 1, end ≥ start, start ≤ line count).
3. Extract the text within the line range.
4. If `old_text` is not found in the range, return an error. The file is unmodified.
5. Replace the first occurrence of `old_text` with `new_text`.
6. Reassemble and write the file back.

This tool has real design decisions — unlike `read_file` and `shell`, which are straightforward. What happens when `old_text` appears multiple times? What if the replacement changes the line count?

**Verify:** The "Edit file" harness test passes. The edit_file unit tests pass, confirming precise edits and explicit failure behavior.

### Exercise 12: Implement the `list_files` tool

Find `// Module 01, Exercise 12:` in your source file. Implement `executeListFiles` (Rust: `execute_list_files`). The parameter schema and registry entry are provided as scaffolding so this exercise has the same single-file ownership as the others.

Implement the execution behavior for `list_files` — a recursive directory lister with a depth limit. Study the provided schema, description, and registration to understand the complete tool shape.

1. Inspect the provided `ListFilesParams`, which has `path` (string) and `max_depth` (integer).
2. Implement the execute function:
   - Resolve the directory path.
   - Return an error if the directory doesn't exist.
   - Walk the directory recursively up to `max_depth` levels (1 = immediate children only).
   - Return paths relative to the given directory, sorted alphabetically.
   - Directories end with `/`.
3. Inspect the provided tool description and confirm it tells the LLM when and how to use the tool.
4. Confirm the scaffolded tools array registers it as `list_files`.

**Verify:** list_files unit tests pass (depth limiting, sorting, missing directory). Integration test: "list_files tool" passes — the agent uses the tool to list a directory, validating your end-to-end tool design workflow.
