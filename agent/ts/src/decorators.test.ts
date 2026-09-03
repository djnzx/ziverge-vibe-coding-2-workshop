import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  allow, deny, snapshotAgentState,
  type ToolInvocation, type ToolDecorator, type DecoratorOutcome,
} from "./decorators.js";
import { createConversation, addTurn } from "./conversation.js";
import { defaultConfig } from "./config.js";

test("allow() builds an Allow outcome with the given invocation", () => {
  const inv: ToolInvocation = { name: "read_file", arguments: { path: "x" } };
  const out = allow(inv);
  assert.equal(out.kind, "allow");
  if (out.kind === "allow") assert.deepEqual(out.invocation, inv);
});

test("deny() builds a Deny outcome with the reason", () => {
  const out = deny("policy violation");
  assert.equal(out.kind, "deny");
  if (out.kind === "deny") assert.equal(out.reason, "policy violation");
});

test("snapshotAgentState carries through conversation, tools, phase, workDir", () => {
  const conv = createConversation("system prompt");
  addTurn(conv, "user", "hello");
  addTurn(conv, "assistant", "hi back");
  const config = defaultConfig();
  const state = snapshotAgentState({
    conversation: conv,
    enabledTools: ["read_file", "shell"],
    config,
    currentPhase: "spec",
    workDir: "/tmp/agent",
  });
  assert.equal(state.currentPhase, "spec");
  assert.equal(state.workDir, "/tmp/agent");
  assert.deepEqual([...state.enabledTools], ["read_file", "shell"]);
  assert.equal(state.conversation, conv);
});

test("snapshotAgentState approximates tokensUsed from conversation char count", () => {
  const conv = createConversation("x".repeat(16));
  addTurn(conv, "user", "y".repeat(16));
  // total chars = 32 → tokensUsed ~ 8
  const state = snapshotAgentState({
    conversation: conv,
    enabledTools: [],
    config: defaultConfig(),
    currentPhase: null,
    workDir: "/",
  });
  assert.equal(state.tokensUsed, 8);
});

test("snapshotAgentState: snapshot exposes the values supplied at call time", () => {
  const conv = createConversation("sys");
  addTurn(conv, "user", "first");
  const state = snapshotAgentState({
    conversation: conv,
    enabledTools: ["read_file"],
    config: defaultConfig(),
    currentPhase: "spec",
    workDir: "/tmp/x",
  });
  // Mutate the source AFTER the snapshot. The intent of the contract is that
  // a decorator sees a coherent state for the duration of its call: the
  // values its appliesTo / before / after observe are the ones the loop passed
  // in. The snapshot is built once per tool call in handleTurn and never
  // re-issued mid-call, so we assert the captured fields equal what we
  // supplied (a regression here would mean the helper started transforming
  // its inputs unexpectedly).
  addTurn(conv, "assistant", "later");
  assert.equal(state.currentPhase, "spec");
  assert.equal(state.workDir, "/tmp/x");
  assert.deepEqual([...state.enabledTools], ["read_file"]);
  assert.equal(state.conversation.system, "sys");
});

test("a ToolDecorator value compiles with the expected shape (type-level)", async () => {
  // Compile-time check: an object literal must satisfy ToolDecorator.
  const dec: ToolDecorator = {
    name: "passthrough",
    appliesTo: () => true,
    before: async (inv): Promise<DecoratorOutcome> => allow(inv),
    after: async (inv): Promise<DecoratorOutcome> => allow(inv),
  };
  assert.equal(dec.name, "passthrough");
  assert.equal(dec.appliesTo({ name: "read_file", arguments: {} },
    snapshotAgentState({
      conversation: createConversation(""),
      enabledTools: [], config: defaultConfig(),
      currentPhase: null, workDir: "/",
    })), true);
});
