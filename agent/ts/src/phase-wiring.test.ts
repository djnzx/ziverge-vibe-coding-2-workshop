// Regression: phase-scoped decorators silently never fire when the role-prompt
// path doesn't match the phase id. This happened because workflow orchestrators
// materialized inline role prompts to temp files
// (`phase-role-<pid>-<n>.md`, `<phase>.prompt.md`) — and `currentPhaseFromConfig`
// derived the phase by stripping ONE extension from the basename. So the
// decorator saw `state.currentPhase = "phase-role-12345-9"` and its
// `appliesTo` check against the expected phase returned
// false. The fix introduced an explicit `config.currentPhase` field that
// callers set and `currentPhaseFromConfig` prefers.
//
// These tests cover the wiring layer: the helper itself and the integration
// through `handleTurn` (so the regression is caught even if someone adds a
// new temp-file naming scheme that defeats the basename heuristic).
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

import { defaultConfig, type AgentConfig } from "./config.js";
import { createConversation, type ChatProvider } from "./conversation.js";
import {
  handleTurn,
  currentPhaseFromConfig,
  buildSystemPrompt,
} from "./exercises.js";
import { tools as allTools } from "./agent.js";
import {
  allow, deny, snapshotAgentState,
  type ToolDecorator, type ToolInvocation, type AgentState,
} from "./decorators.js";

// ─────────────────────────── currentPhaseFromConfig ────────────────────────

test("currentPhaseFromConfig prefers explicit currentPhase over rolePrompt basename", () => {
  const cfg: AgentConfig = {
    ...defaultConfig(),
    // The temp file name an orchestrator would write to. Basename-derivation
    // would produce "phase-role-12345-9" — a phase no decorator recognises.
    rolePrompt: path.join(os.tmpdir(), "phase-role-12345-9.md"),
    currentPhase: "codebase",
  };
  assert.equal(currentPhaseFromConfig(cfg), "codebase");
});

test("currentPhaseFromConfig falls back to rolePrompt basename when explicit phase is null", () => {
  // Legacy callers that haven't been updated to pass the explicit field.
  // For a clean filename like `spec.md` the basename heuristic still works,
  // which is why nothing burned before workflows started materializing
  // inline prompts.
  const cfg: AgentConfig = {
    ...defaultConfig(),
    rolePrompt: "/some/dir/spec.md",
    currentPhase: null,
  };
  assert.equal(currentPhaseFromConfig(cfg), "spec");
});

test("currentPhaseFromConfig demonstrates the legacy basename failure mode for materialized inline prompts", () => {
  // This is the bug-as-observed: without the explicit field, the workflow's
  // temp file produces a phase id no decorator matches.
  const cfg: AgentConfig = {
    ...defaultConfig(),
    rolePrompt: path.join(os.tmpdir(), "phase-role-12345-9.md"),
    currentPhase: null,
  };
  const derived = currentPhaseFromConfig(cfg);
  assert.notEqual(derived, "codebase");
  assert.notEqual(derived, "discovery");
  assert.notEqual(derived, "implementation");
  assert.notEqual(derived, "verification");
});

test("currentPhaseFromConfig returns null when neither field is set", () => {
  const cfg: AgentConfig = { ...defaultConfig(), rolePrompt: null, currentPhase: null };
  assert.equal(currentPhaseFromConfig(cfg), null);
});

// ─────────────────────── handleTurn integration ────────────────────────────
//
// End-to-end repro: a decorator that only applies in `codebase` phase
// must observe `state.currentPhase === "codebase"` even when `config.rolePrompt`
// points at a temp file whose basename doesn't carry that name. The
// pre-fix behaviour was: decorator's appliesTo returned false, before-hook
// never ran, and the tool went through.

function mockProvider(responses: string[]): ChatProvider {
  let i = 0;
  return async (_) => responses[Math.min(i++, responses.length - 1)];
}

function makeTracingDecorator(
  expectedPhase: string,
  beforeCount: { n: number; lastPhase: string | null },
): ToolDecorator {
  return {
    name: "phase-probe",
    appliesTo: (inv: ToolInvocation, state: AgentState) =>
      inv.name === "read_file" && state.currentPhase === expectedPhase,
    before: async (inv, state) => {
      beforeCount.n += 1;
      beforeCount.lastPhase = state.currentPhase;
      // Deny so the test exits the loop on the first hit (mirrors how
      // budget-enforcement protects against budget overrun).
      return deny("test-only sentinel: decorator fired");
    },
  };
}

test("handleTurn: phase-scoped decorator fires when config.currentPhase is set, even if rolePrompt basename does not match the phase", async () => {
  // The killer regression test. Pre-fix: counter stays at 0 because the
  // decorator's appliesTo check fails against the bogus basename. Post-fix:
  // counter is 1 because the explicit field flows through to AgentState.
  const counter = { n: 0, lastPhase: null as string | null };
  const decorator = makeTracingDecorator("codebase", counter);

  const conversation = createConversation("system");
  const provider = mockProvider([
    `<tool_call>{"name": "read_file", "arguments": {"path": "x.txt"}}</tool_call>`,
    `<tool_call>{"name": "message_user", "arguments": {"message": "done"}}</tool_call>`,
  ]);

  const cfg: AgentConfig = {
    ...defaultConfig(),
    rolePrompt: path.join(os.tmpdir(), "phase-role-99999-42.md"),
    currentPhase: "codebase",
    maxIterations: 3,
  };
  // Pre-build the system prompt so we don't read rolePrompt off disk
  // (it's a fake path).
  conversation.system = buildSystemPrompt({ ...cfg, rolePrompt: null }, allTools);

  await handleTurn(provider, conversation, "do the thing", cfg, allTools, undefined, [decorator]);

  assert.equal(counter.n, 1, "decorator must fire once when currentPhase matches");
  assert.equal(counter.lastPhase, "codebase");
});

test("handleTurn: phase-scoped decorator does NOT fire when rolePrompt basename would imply a different phase and currentPhase is unset (pre-fix behaviour)", async () => {
  // Without the explicit field, basename of `phase-role-…md` is the
  // derived "phase" — appliesTo returns false, decorator never runs.
  const counter = { n: 0, lastPhase: null as string | null };
  const decorator = makeTracingDecorator("codebase", counter);

  const conversation = createConversation("system");
  const provider = mockProvider([
    `<tool_call>{"name": "read_file", "arguments": {"path": "x.txt"}}</tool_call>`,
    `<tool_call>{"name": "message_user", "arguments": {"message": "done"}}</tool_call>`,
  ]);

  const cfg: AgentConfig = {
    ...defaultConfig(),
    rolePrompt: path.join(os.tmpdir(), "phase-role-99999-42.md"),
    currentPhase: null, // ← legacy path, basename of rolePrompt wins
    maxIterations: 3,
  };
  conversation.system = buildSystemPrompt({ ...cfg, rolePrompt: null }, allTools);

  await handleTurn(provider, conversation, "do the thing", cfg, allTools, undefined, [decorator]);

  assert.equal(counter.n, 0, "decorator must NOT fire when basename != expected phase and no explicit currentPhase");
});

test("snapshotAgentState reflects config.currentPhase end-to-end", () => {
  // Direct snapshot smoke check (covers the AgentConfig → state plumbing
  // in case someone bypasses runPhase but uses snapshotAgentState).
  const cfg = { ...defaultConfig(), currentPhase: "impact" };
  const state = snapshotAgentState({
    conversation: createConversation("sys"),
    enabledTools: ["read_file"],
    config: cfg,
    currentPhase: currentPhaseFromConfig(cfg),
    workDir: "/tmp/x",
  });
  assert.equal(state.currentPhase, "impact");
});
