# Exercises

Module 02 made the agent context-aware. Now you make delegation safer and more controllable. Every guardrail in this module is a function that can prevent, modify, or record an agent action — and every one is testable.

Exercise implementations live in a dedicated exercises file per language (`exercises.ts`, `exercises.rs`, `Exercises.scala`). Search for `// Module 03, Exercise N:` comments to find each exercise's location:

| Language | Source file | Exercise test command |
|---|---|---|
| TypeScript | `agent/ts/src/exercises.ts` | `npx tsx --test src/module03.exercises.test.ts` |
| Rust | `agent/rust/src/exercises.rs` | `cargo test module03_exercises` |
| Scala | `agent/scala/src/main/scala/Exercises.scala` | `sbt "testOnly workshop.agent.Module03*"` |

Run exercise tests from each language's directory (`agent/ts/`, `agent/rust/`, `agent/scala/`).

> **Runtime status:** The typed decorator loop and Exercise 6 exit gate are wired in all three agents. TypeScript also applies the Exercise 1 allow/deny policy in its loop. The remaining permission/sandbox, redaction, audit, checkpoint, and Lima functions are focused teaching primitives invoked by unit tests, not automatic end-to-end security controls. Exercise 8 documents the additional Lima provisioning needed before its adapter is useful.

### Quick Reference

| Ex | Function (TS / Scala) | Function (Rust) | Verify |
|---|---|---|---|
| 1 | `checkToolPermission` | `check_tool_permission` | Unit tests: allow/deny patterns, glob matching, message_user always allowed |
| 2 | `enforceSandbox` + `analyzeShellSandbox` | `enforce_sandbox` + `analyze_shell_sandbox` | Unit tests: path containment, protected files, LLM shell analysis |
| 3 | `redactSecrets` | `redact_secrets` | Unit tests: regex replacement and invalid-pattern handling |
| 4 | `logAuditEvent` | `log_audit_event` | Unit tests: JSON-lines format, event types, file creation |
| 5 | typed decorator loop wiring inside `handleTurn` | typed decorator loop wiring inside `handle_turn` | Unit tests: allow, deny, modify args, after-deny, short-circuit, empty-list no-op |
| 6 | `exitGate(commands)` (returns a `ToolDecorator`) | `exit_gate(commands)` | Unit tests: applies only on `message_user`, allow on all-zero, deny with exit code + truncated output, short-circuit, in-workDir |
| 7 | `createCheckpoint` + `restoreCheckpoint` + `listCheckpoints` | `create_checkpoint` + `restore_checkpoint` + `list_checkpoints` | Unit tests: copy, restore, list sorted |
| 8 | `executeSandboxedShell` | `execute_sandboxed_shell` | Unit tests: structured adapter result; Lima integration is operator-verified |

### Verification

Two methods to verify both policy mechanics and end-to-end enforcement:

- **Exercise tests** -- fast, no LLM needed. Use the commands in the table above to validate each guardrail in isolation.
- **Integration tests** -- full end-to-end, calls OpenRouter. Run from `agent/`: `npx tsx test-harness/src/cli.ts --lang <ts|rust|scala>`. These exercise the agent protocol; they are not a security certification.

---

## Access Control (Exercises 1--2)

Gate which tools the agent can use and which paths it can touch so risky actions stay constrained by policy.

### Exercise 1: Check tool permissions

Find `// Module 03, Exercise 1:` in your source file. Implement `checkToolPermission` (Rust: `check_tool_permission`).

The agent needs an operator-controlled allow/deny system for tools. Production agents like Claude Code support patterns like `allow Bash(*)` or `deny Bash(rm *)`. We implement a simplified version using glob patterns.

Your function should:

1. Take a tool name, tool arguments (as a string), and two pattern lists (allow and deny).
2. **Always allow `message_user`** — if the tool is `message_user`, return allowed regardless of patterns.
3. **Pattern syntax**: `tool_name` matches all calls to that tool. `tool_name(glob)` matches calls where the arguments match the glob.
4. **Resolution logic**:
   - Both empty → all allowed.
   - Only allow is non-empty → only matching tools are permitted.
   - Only deny is non-empty → all tools except matching ones are permitted.
   - Both non-empty → allow filters first (tool must match an allow pattern), then deny vetoes (matching deny patterns block even if allowed).
5. Return a `PermissionResult` with `allowed: boolean` and `reason: string` explaining the decision.

**Verify:** Unit tests pass — empty patterns allow all, allow-only filters, deny-only blocks, combined allow+deny, glob matching on arguments, message_user always allowed, confirming operator policy precedence.

### Exercise 2: Enforce sandbox boundaries

Find `// Module 03, Exercise 2:` in your source file. Implement `enforceSandbox` and `analyzeShellSandbox` (Rust: `enforce_sandbox` and `analyze_shell_sandbox`).

File tools must stay inside `config.workDir`. The shell is harder — an LLM side-channel provides a best-effort analysis.

`enforceSandbox` should:

1. For file tools (`read_file`, `write_file`, `edit_file`, `list_files`): resolve the `path` argument, verify it's within `workDir`.
2. For write tools (`write_file`, `edit_file`): also check the path isn't in `config.protectedFiles`. Protected files may be read but never written.
3. Return a `PermissionResult` with the decision and reason.

`analyzeShellSandbox` should:

1. Send the shell command to the LLM with a prompt: "Analyze this shell command and tell me if it can read or write files outside of {workDir}. Answer with exactly 'yes', 'no', or 'unknown'."
2. If the answer is `no`, return allowed.
3. If the answer is `yes` or `unknown` (or anything else), return denied with reason.

Note: this leaves a massive hole — the LLM analysis is best-effort and easily fooled. Document this limitation. Stronger process isolation is discussed in Exercise 8.

**Verify:** Unit tests pass — path within workDir allowed, path escape denied, protected file write denied, protected file read allowed, shell analysis mock returns no/yes/unknown correctly, confirming boundary enforcement behavior.

### Exercise 3: Redact secrets

Find `// Module 03, Exercise 3:` in your source file. Implement `redactSecrets` (Rust: `redact_secrets`).

Prevent secrets from leaking — both into the agent's context (tool output) and out to the user's terminal (agent responses). This is redaction, not detection: silently replace matches with `[REDACTED]`.

Your function should:

1. Take a text string and a list of regex patterns (from `config.secretPatterns`).
2. Replace every match with `[REDACTED]`.
3. Return the cleaned text.

This exercise implements and unit-tests the redaction primitive. The current agent loops do not yet apply it automatically to every tool input and output, so configuring patterns alone is not a complete data-loss-prevention boundary.

Default patterns catch API keys (`sk-...`), tokens, passwords, and secrets in `KEY=value` format.

**Verify:** Unit tests pass — API key redacted, password redacted, no secrets unchanged, and multiple patterns all redacted, validating the primitive independently of loop integration.

---

## Observability (Exercise 4)

Record what the agent does for post-hoc review so you can audit and debug delegated work confidently.

### Exercise 4: Log audit events

Find `// Module 03, Exercise 4:` in your source file. Implement `logAuditEvent` (Rust: `log_audit_event`).

Every action the agent takes should be recorded. This is the foundation for debugging, compliance, and understanding agent behavior after the fact.

Your function should:

1. Take a log file path and an `AuditEvent` (a structured record with timestamp, event type, and details).
2. Serialize the event as a single JSON line.
3. Append it to the log file. Create the file if it doesn't exist.

Event types: `tool_call`, `tool_result`, `permission_denied`, `sandbox_violation`, `secret_redacted`, `checkpoint_created`, `checkpoint_restored`, `decorator_applied`.

This exercise implements the JSONL append primitive; it does not wire every loop action into `auditLog`. Treat it as a focused lesson in structured evidence, not as a complete compliance trail.

**Verify:** Unit tests pass — appends JSON line, creates file if missing, multiple events produce multiple valid JSON lines, and events contain the supplied type and timestamp.

---

## Policy Enforcement (Exercises 5--6)

Generalize guardrails into a typed code decorator so policy can evolve without rewriting core execution — and so denials/rewrites stay deterministic and unit-testable.

### Exercise 5: Apply tool decorators

Find `// Module 03, Exercise 5:` in your source file. Wire the code-decorator passes into the `handleTurn` tool-execution loop using the types from `decorators.{ts,rs,scala}`:

```
ToolInvocation = { name, arguments }
DecoratorOutcome = Allow{ invocation } | Deny{ reason }
AgentState      = { conversation, enabledTools, tokensUsed, config, currentPhase, workDir }   // immutable snapshot
ToolDecorator   = {
  name,
  appliesTo: (ToolInvocation, AgentState) => bool,        // sync pre-filter
  before?:   (ToolInvocation, AgentState) => Future<DecoratorOutcome>,
  after?:    (ToolInvocation, ToolResult, AgentState) => Future<DecoratorOutcome>,
}
```

The agent core and any custom workflow hand `runPhase` a list of `ToolDecorator` values via the `decorators` option. There are no `.md` files and no LLM sub-agents — decorators are pure async functions of `(invocation, state)`.

Your wiring inside `handleTurn` should, **per tool call**:

1. Build an `AgentState` snapshot once via `snapshotAgentState` (`snapshot_agent_state` in Rust). Pass the same snapshot to every decorator that fires this turn.
2. **Before pass.** Iterate `codeDecorators` in registration order. For each decorator whose `appliesTo` returns true and whose `before` is defined, `await` the outcome:
   - `Allow{invocation}` → take the (possibly-rewritten) invocation and feed it to the next decorator. Chain composition: subsequent decorators see the updated args.
   - `Deny{reason}` → record the deny message, **break** the loop. Skip tool execution; push the deny reason into the conversation as a user-role turn so the LLM gets the feedback on the next iteration.
3. **Execute the tool** with the final invocation.
4. **After pass.** Iterate decorators again; for each with matching `appliesTo` and an `after`, pass the `ToolResult`. On `Deny{reason}` replace the tool output with the deny reason (so secrets, malformed artifacts, etc. never reach the LLM) and break.
5. `message_user` / `ask_user` are special: they end the turn, so run the after pass against a synthetic `ToolResult.Ok(content)` before returning, so artifact-validating decorators can force a revision.

The empty-list case (no code decorators registered) must be a pure no-op — behavior should be identical to a turn without the decorator option at all. Tests assert this explicitly.

**Verify:** Unit tests pass — allow path runs tool unchanged, before-deny blocks execution and surfaces reason, before-allow with modified invocation feeds rewritten args to the tool, after-deny replaces tool output, first deny in a chain short-circuits subsequent decorators, `appliesTo=false` skips both before and after, empty decorator list is a no-op.

### Exercise 6: Build an exit-gate decorator

Find `// Module 03, Exercise 6:` in your source file. Implement `exitGate(commands)` (Rust: `exit_gate(commands)`).

This exercise is the canonical application of the M03 doctrine: **prompts advise, decorators enforce.** A prompt instruction like "verify your work before calling message_user" can be ignored by the LLM — and will be ignored under enough pressure (long conversation, conflicting signals, partial success). The same enforcement expressed as a code decorator on `message_user` CANNOT be ignored: the agent loop refuses to let the call through until every gate command exits 0.

`exitGate(commands)` should return a `ToolDecorator` value with:

1. `appliesTo`: returns true only when the invocation is `message_user` AND `commands` is non-empty. Empty `commands` means "no gates configured" → the decorator should be a no-op (return false from `appliesTo` so it never fires).
2. `before`: `None` — exit-gate is purely a termination check.
3. `after`: for each command in `commands`, run `sh -c <cmd>` in `state.workDir` and capture exit code + stdout + stderr. On the **first** non-zero exit, return `Deny{reason}` where the reason includes:
   - Which gate command failed
   - The exit code
   - The captured stdout + stderr (combined, trimmed)
   - Truncated to ~2KB so the deny reason stays readable in the LLM's conversation
   - An instruction telling the LLM it cannot terminate until every gate passes

   If all gates exit 0, return `Allow{ToolInvocation("message_user", {})}`.

**Short-circuit semantics.** As soon as one gate fails, **stop running subsequent gates** — they cannot turn the verdict around, and running them wastes time + would clutter the deny reason. Tests assert this with a sentinel: gate 1 is `exit 1`, gate 2 is `touch /tmp/sentinel`. The sentinel file must not exist after the decorator runs.

**No-shell-magic.** Each gate runs in `state.workDir` via `sh -c "$cmd"`, so users can use pipes, redirects, `&&`, etc. without you re-implementing a shell. Add a wall-clock cap (~120s) so a hung gate can't lock the agent.

**Wiring.** The agent's CLI already plumbs `exitGates: string[]` through `AgentConfig` and the `--exit-gate <cmd>` flag (repeatable). When the agent runs `handleTurn`, it constructs a decorator list: if `config.exitGates` is non-empty, push `exitGate(config.exitGates)` onto the list. The Ex 5 wiring then drives the after-pass.

**Verify:** Unit tests pass — applies only to `message_user`, allow when all gates exit 0, deny carries exit code + truncated output, short-circuit on first failure (sentinel never created), gates run in `workDir` (not the agent's parent process cwd), missing command (sh -c can't find it) denies rather than silently passing.

---

## Recovery (Exercises 7--8)

When guardrails fail, recover gracefully and restore control quickly.

### Exercise 7: Create and restore checkpoints

Find `// Module 03, Exercise 7:` in your source file. Implement `createCheckpoint`, `restoreCheckpoint`, and `listCheckpoints` (Rust: `create_checkpoint`, `restore_checkpoint`, `list_checkpoints`).

Sometimes guardrails aren't enough — the agent makes a mess and you need to undo it. File-system checkpoints provide a simple undo mechanism.

`createCheckpoint` should:

1. Generate a checkpoint ID (timestamp-based).
2. Copy all files from `workDir` into `{checkpointsDir}/{id}/`.
3. Return a `CheckpointInfo` with id, timestamp, and path.

`restoreCheckpoint` should:

1. Find the checkpoint by ID in `checkpointsDir`.
2. Copy all files from the checkpoint back to `workDir`, overwriting current files.
3. Return true on success, false if checkpoint doesn't exist.

`listCheckpoints` should:

1. Scan `checkpointsDir` for checkpoint directories.
2. Return sorted by timestamp (newest first).

These functions are explicit recovery primitives exercised directly by unit tests. They are not registered as agent tools and the agent does not auto-checkpoint destructive calls.

**Verify:** Unit tests pass — create copies files, restore overwrites, list returns sorted, restore nonexistent returns false, validating practical rollback capability.

### Exercise 8: Sandbox in Lima VM

Find `// Module 03, Exercise 8:` in your source file. Implement `executeSandboxedShell` (Rust: `execute_sandboxed_shell`).

Exercise 2's LLM-based shell analysis is best-effort. Real sandboxing requires running commands in an isolated environment. Lima provides lightweight Linux VMs on macOS (and Linux).

Your function should:

1. Execute the shell command via `limactl shell <vm-name> -- bash -c "<command>"`.
2. Set the working directory to `workDir` inside the VM.
3. Return the tool result (stdout on success, stderr on failure).

The repository intentionally does not install Lima, create a VM, provide a VM template, or manage mounts. `executeSandboxedShell` is a small adapter for an already configured `default` Lima instance; `--sandbox-vm` is parsed but is not currently selected by the agent loop.

**Verify:** Unit tests use a fake `limactl` executable to check the exact
argument vector, quoted working directory, stdout success, non-zero exit, and
process-start failure. Running inside a real VM still requires an
operator-provisioned Lima instance and separate integration verification.
