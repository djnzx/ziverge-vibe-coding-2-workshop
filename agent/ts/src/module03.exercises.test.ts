import { describe, it, before, after } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import type { ChatProvider } from "./conversation.js";
import {
  checkToolPermission,
  enforceSandbox,
  analyzeShellSandbox,
  redactSecrets,
  logAuditEvent,
  createCheckpoint,
  restoreCheckpoint,
  listCheckpoints,
  executeSandboxedShell,
  exitGate,
  handleTurn,
} from "./exercises.js";
import { tools } from "./agent.js";
import { defaultConfig } from "./config.js";
import { createConversation } from "./conversation.js";
import type { AuditEvent, ToolResult } from "./tools.js";
import { allow, deny, snapshotAgentState, type ToolDecorator, type ToolInvocation } from "./decorators.js";

function withTempDir(fn: (dir: string) => void | Promise<void>): Promise<void> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "m03-"));
  return Promise.resolve(fn(dir)).finally(() => fs.rmSync(dir, { recursive: true, force: true }));
}

function writeTextFile(filePath: string, content: string): void {
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(filePath, content);
}

describe("Exercise 1: Check tool permissions", () => {
  it("allows all tools when both lists empty", () => {
    const result = checkToolPermission("shell", "ls", [], []);
    assert.ok(result.allowed);
  });

  it("allows only listed tools when allow is non-empty", () => {
    const allowed = checkToolPermission("shell", "ls", ["shell"], []);
    assert.ok(allowed.allowed);
    const denied = checkToolPermission("read_file", "x", ["shell"], []);
    assert.ok(!denied.allowed);
  });

  it("denies matching tools when deny is non-empty", () => {
    const allowed = checkToolPermission("shell", "ls", [], ["shell(rm *)"]);
    assert.ok(allowed.allowed);
    const denied = checkToolPermission("shell", "rm -rf /", [], ["shell(rm *)"]);
    assert.ok(!denied.allowed);
  });

  it("deny vetoes allow when both non-empty", () => {
    const result = checkToolPermission("shell", "rm -rf /", ["shell"], ["shell(rm *)"]);
    assert.ok(!result.allowed);
  });

  it("always allows message_user", () => {
    const result = checkToolPermission("message_user", "hi", [], ["message_user"]);
    assert.ok(result.allowed);
  });

  it("returns a reason string", () => {
    const result = checkToolPermission("shell", "rm -rf", [], ["shell(rm *)"]);
    assert.ok(result.reason.length > 0);
  });
});

describe("Exercise 2: Enforce sandbox boundaries", () => {
  it("allows paths within workDir", () => {
    const result = enforceSandbox("read_file", { path: "foo.txt" }, "/home/user/project", []);
    assert.ok(result.allowed);
  });

  it("denies paths escaping workDir", () => {
    const result = enforceSandbox("read_file", { path: "../../etc/passwd" }, "/home/user/project", []);
    assert.ok(!result.allowed);
  });

  it("denies writes to protected files", () => {
    const result = enforceSandbox("write_file", { path: "secret.key" }, "/home/user/project", ["secret.key"]);
    assert.ok(!result.allowed);
  });

  it("allows reads of protected files", () => {
    const result = enforceSandbox("read_file", { path: "secret.key" }, "/home/user/project", ["secret.key"]);
    assert.ok(result.allowed);
  });

  it("allows non-file tools", () => {
    const result = enforceSandbox("shell", { command: "rm -rf /" }, "/home/user/project", []);
    assert.ok(result.allowed);
  });

  it("analyzeShellSandbox returns allowed when LLM says no", async () => {
    const provider: ChatProvider = async () => "no";
    const result = await analyzeShellSandbox(provider, "ls", "/home/user");
    assert.ok(result.allowed);
  });

  it("analyzeShellSandbox returns denied when LLM says yes", async () => {
    const provider: ChatProvider = async () => "yes";
    const result = await analyzeShellSandbox(provider, "cat /etc/passwd", "/home/user");
    assert.ok(!result.allowed);
  });

  it("analyzeShellSandbox returns denied when LLM says unknown", async () => {
    const provider: ChatProvider = async () => "unknown";
    const result = await analyzeShellSandbox(provider, "eval $(base64 -d <<< ...)", "/home/user");
    assert.ok(!result.allowed);
  });

  it("analyzeShellSandbox returns denied for unexpected responses", async () => {
    const provider: ChatProvider = async () => "maybe";
    const result = await analyzeShellSandbox(provider, "ls", "/home/user");
    assert.ok(!result.allowed);
  });
});

describe("Exercise 3: Redact secrets", () => {
  it("redacts API keys", () => {
    const result = redactSecrets("key=sk-abc123def456ghi789", ["sk-[a-zA-Z0-9]{10,}"]);
    assert.ok(result.includes("[REDACTED]"));
    assert.ok(!result.includes("sk-abc123"));
  });

  it("leaves clean text unchanged", () => {
    const text = "hello world, no secrets here";
    assert.equal(redactSecrets(text, ["sk-[a-zA-Z0-9]{10,}"]), text);
  });

  it("redacts multiple patterns", () => {
    const text = "api_key=sk-abc123def456 password=hunter2";
    const result = redactSecrets(text, ["sk-[a-zA-Z0-9]{10,}", "password\\s*=\\s*\\S+"]);
    assert.ok(!result.includes("sk-abc123"));
    assert.ok(!result.includes("hunter2"));
  });

  it("handles invalid regex gracefully", () => {
    const text = "safe text";
    assert.equal(redactSecrets(text, ["[invalid"]), text);
  });
});

describe("Exercise 4: Log audit events", () => {
  it("appends JSON line to file", async () => {
    await withTempDir((dir) => {
      const logPath = path.join(dir, "audit.jsonl");
      const event: AuditEvent = { timestamp: "2024-01-01T00:00:00Z", event: "tool_call", details: { tool: "shell" } };
      logAuditEvent(logPath, event);
      const content = fs.readFileSync(logPath, "utf-8");
      const parsed = JSON.parse(content.trim());
      assert.equal(parsed.event, "tool_call");
    });
  });

  it("creates file if missing", async () => {
    await withTempDir((dir) => {
      const logPath = path.join(dir, "sub", "audit.jsonl");
      logAuditEvent(logPath, { timestamp: "t", event: "tool_call", details: {} });
      assert.ok(fs.existsSync(logPath));
    });
  });

  it("appends multiple events as separate lines", async () => {
    await withTempDir((dir) => {
      const logPath = path.join(dir, "audit.jsonl");
      logAuditEvent(logPath, { timestamp: "t1", event: "tool_call", details: {} });
      logAuditEvent(logPath, { timestamp: "t2", event: "tool_result", details: {} });
      const lines = fs.readFileSync(logPath, "utf-8").trim().split("\n");
      assert.equal(lines.length, 2);
      assert.equal(JSON.parse(lines[0]).timestamp, "t1");
      assert.equal(JSON.parse(lines[1]).timestamp, "t2");
    });
  });
});

describe("Exercise 5: Apply tool decorators", () => {
  it("a before-deny prevents execution and feeds its reason back", async () => {
    await withTempDir(async (workDir) => {
      let response = 0;
      const provider = async () => [
        `<tool_call>{"name":"shell","arguments":{"command":"touch must-not-exist"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"blocked"}}</tool_call>`,
      ][response++]!;
      const noShell: ToolDecorator = {
        name: "no-shell",
        appliesTo: (invocation) => invocation.name === "shell",
        before: async () => deny("shell forbidden in tests"),
      };
      const conversation = createConversation("system");
      await handleTurn(
        provider,
        conversation,
        "use shell",
        { ...defaultConfig(), workDir },
        tools,
        undefined,
        [noShell],
      );
      assert.equal(fs.existsSync(path.join(workDir, "must-not-exist")), false);
      assert.match(conversation.turns.map((turn) => turn.content).join("\n"), /shell forbidden in tests/);
    });
  });
});

describe("Exercise 6: Build an exit-gate decorator", () => {
  function stateAt(workDir: string) {
    return snapshotAgentState({
      conversation: createConversation("system"),
      enabledTools: ["message_user"],
      config: { ...defaultConfig(), workDir },
      currentPhase: null,
      workDir,
    });
  }

  const inv: ToolInvocation = { name: "message_user", arguments: { message: "done" } };
  const okResult: ToolResult = { ok: true, output: "done" };

  it("appliesTo only fires on message_user when commands non-empty", () => {
    const dec = exitGate(["true"]);
    assert.equal(dec.appliesTo({ name: "message_user", arguments: {} }, stateAt("/tmp")), true);
    assert.equal(dec.appliesTo({ name: "shell", arguments: {} }, stateAt("/tmp")), false);
    assert.equal(dec.appliesTo({ name: "ask_user", arguments: {} }, stateAt("/tmp")), false);
    const noGates = exitGate([]);
    assert.equal(noGates.appliesTo({ name: "message_user", arguments: {} }, stateAt("/tmp")), false);
  });

  it("allows when every gate exits 0", async () => {
    const dec = exitGate(["true", "echo ok"]);
    const r = await dec.after!(inv, okResult, stateAt("/tmp"));
    assert.equal(r.kind, "allow");
  });

  it("denies on first non-zero exit, with exit code + output in reason", async () => {
    const dec = exitGate(["echo to-stderr >&2 && exit 7"]);
    const r = await dec.after!(inv, okResult, stateAt("/tmp"));
    assert.equal(r.kind, "deny");
    if (r.kind === "deny") {
      assert.match(r.reason, /Exit-gate failed/);
      assert.match(r.reason, /exited 7/);
      assert.match(r.reason, /to-stderr/);
    }
  });

  it("short-circuits on first failing gate (subsequent gates do not run)", async () => {
    await withTempDir(async (dir) => {
      const sentinel = path.join(dir, "should-not-exist");
      const dec = exitGate(["exit 1", `touch ${sentinel}`]);
      const r = await dec.after!(inv, okResult, stateAt(dir));
      assert.equal(r.kind, "deny");
      assert.equal(fs.existsSync(sentinel), false);
    });
  });

  it("gates run in workDir, not process cwd", async () => {
    await withTempDir(async (dir) => {
      fs.writeFileSync(path.join(dir, "marker"), "x");
      const dec = exitGate(["test -f marker"]);
      const r = await dec.after!(inv, okResult, stateAt(dir));
      assert.equal(r.kind, "allow");
    });
  });

  it("truncates very long output to keep the deny reason readable", async () => {
    const dec = exitGate(["yes A | head -c 10000; exit 1"]);
    const r = await dec.after!(inv, okResult, stateAt("/tmp"));
    assert.equal(r.kind, "deny");
    if (r.kind === "deny") {
      assert.match(r.reason, /truncated/);
      assert.ok(r.reason.length < 4000, `reason too long: ${r.reason.length}`);
    }
  });

  it("missing command denies with non-zero exit (not allow)", async () => {
    const dec = exitGate(["this-command-does-not-exist-xyz123"]);
    const r = await dec.after!(inv, okResult, stateAt("/tmp"));
    assert.equal(r.kind, "deny");
  });
});

describe("Exercise 7: Create and restore checkpoints", () => {
  it("creates a checkpoint that copies files", async () => {
    await withTempDir((dir) => {
      const workDir = path.join(dir, "work");
      const cpDir = path.join(dir, "checkpoints");
      fs.mkdirSync(workDir);
      fs.writeFileSync(path.join(workDir, "file.txt"), "original");
      const info = createCheckpoint(workDir, cpDir);
      assert.ok(info.id.startsWith("cp-"));
      assert.ok(fs.existsSync(path.join(info.path, "file.txt")));
    });
  });

  it("restores checkpoint overwrites workDir", async () => {
    await withTempDir((dir) => {
      const workDir = path.join(dir, "work");
      const cpDir = path.join(dir, "checkpoints");
      fs.mkdirSync(workDir);
      fs.writeFileSync(path.join(workDir, "file.txt"), "original");
      const info = createCheckpoint(workDir, cpDir);
      fs.writeFileSync(path.join(workDir, "file.txt"), "modified");
      assert.ok(restoreCheckpoint(workDir, info.id, cpDir));
      assert.equal(fs.readFileSync(path.join(workDir, "file.txt"), "utf-8"), "original");
    });
  });

  it("listCheckpoints returns checkpoints", async () => {
    await withTempDir((dir) => {
      const workDir = path.join(dir, "work");
      const cpDir = path.join(dir, "checkpoints");
      fs.mkdirSync(workDir);
      fs.writeFileSync(path.join(workDir, "a.txt"), "a");
      const cp1 = createCheckpoint(workDir, cpDir);
      const cp2 = createCheckpoint(workDir, cpDir);
      const list = listCheckpoints(cpDir);
      assert.ok(list.length >= 2);
      assert.ok(list.some(cp => cp.id === cp1.id));
      assert.ok(list.some(cp => cp.id === cp2.id));
      assert.deepEqual(
        list.slice(0, 2).map(cp => cp.id),
        [cp2.id, cp1.id],
      );
    });
  });

  it("restoreCheckpoint returns false for nonexistent", async () => {
    await withTempDir((dir) => {
      assert.ok(!restoreCheckpoint(dir, "nonexistent", dir));
    });
  });
});

describe("Exercise 8: Sandbox in Lima VM", () => {
  function fakeLimactl(dir: string, body: string): string {
    const executable = path.join(dir, "limactl");
    fs.writeFileSync(executable, `#!/bin/sh\n${body}\n`);
    fs.chmodSync(executable, 0o755);
    return executable;
  }

  it("passes the exact Lima invocation and returns stdout", async () => {
    await withTempDir((dir) => {
      const argsFile = path.join(dir, "args");
      const executable = fakeLimactl(
        dir,
        `printf '%s\\n' "$@" > ${JSON.stringify(argsFile)}\nprintf 'sandbox ok\\n'`,
      );
      const result = executeSandboxedShell("printf '%s' done", "/tmp/work dir", executable);
      assert.deepEqual(result, { ok: true, output: "sandbox ok\n" });
      assert.deepEqual(fs.readFileSync(argsFile, "utf-8").trimEnd().split("\n"), [
        "shell", "default", "--", "bash", "-c",
        "cd -- '/tmp/work dir' && printf '%s' done",
      ]);
    });
  });

  it("returns a structured error when limactl exits non-zero", async () => {
    await withTempDir((dir) => {
      const executable = fakeLimactl(dir, "printf 'remote failed\\n' >&2\nexit 7");
      const result = executeSandboxedShell("false", "/tmp/work", executable);
      assert.equal(result.ok, false);
      if (!result.ok) assert.match(result.error, /sandboxed shell error:.*remote failed/s);
    });
  });

  it("returns a structured error when limactl cannot start", () => {
    const result = executeSandboxedShell("true", "/tmp/work", "/definitely/missing/limactl");
    assert.equal(result.ok, false);
    if (!result.ok) assert.match(result.error, /^sandboxed shell error:/);
  });
});


describe("Additional decorator integration regressions", () => {
  function fixedProvider(responses: string[]) {
    let i = 0;
    return async () =>
      i < responses.length
        ? responses[i++]
        : `<tool_call>{"name":"message_user","arguments":{"message":"done"}}</tool_call>`;
  }

  it("empty decorator list: behaviour is unchanged", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      fs.writeFileSync(path.join(work, "a.txt"), "hello");
      const provider = fixedProvider([
        `<tool_call>{"name":"read_file","arguments":{"path":"a.txt"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"hi"}}</tool_call>`,
      ]);
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      const result = await handleTurn(provider, conversation, "read it", config, tools, undefined, []);
      assert.equal(result.content, "hi");
    });
  });

  it("before-decorator can deny: tool not executed and reason fed back", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      let executed = 0;
      // Custom tool to detect execution. We'll use shell, which we deny.
      const provider = fixedProvider([
        `<tool_call>{"name":"shell","arguments":{"command":"echo hi"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"aborted"}}</tool_call>`,
      ]);
      const deniedShell: ToolDecorator = {
        name: "no-shell",
        appliesTo: (inv) => inv.name === "shell",
        before: async () => deny("shell forbidden in tests"),
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "use shell", config, tools, undefined, [deniedShell]);
      const allText = conversation.turns.map((t) => t.content).join("\n");
      assert.match(allText, /blocked by decorator no-shell/);
      assert.match(allText, /shell forbidden in tests/);
      // The shell tool was never run — verify by ensuring "Tool shell returned:" never appears.
      assert.ok(!allText.includes("Tool shell returned:"), "shell tool must not have executed");
      void executed;
    });
  });

  it("before-decorator can rewrite arguments: tool executes with modified args", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      fs.writeFileSync(path.join(work, "real.txt"), "real-content");
      fs.writeFileSync(path.join(work, "fake.txt"), "fake-content");
      const provider = fixedProvider([
        `<tool_call>{"name":"read_file","arguments":{"path":"fake.txt"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"done"}}</tool_call>`,
      ]);
      const swap: ToolDecorator = {
        name: "swap-path",
        appliesTo: (inv) => inv.name === "read_file",
        before: async (inv) => allow({ name: inv.name, arguments: { ...inv.arguments, path: path.join(work, "real.txt") } }),
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "read fake", config, tools, undefined, [swap]);
      const allText = conversation.turns.map((t) => t.content).join("\n");
      assert.match(allText, /real-content/, "decorator should have swapped to real.txt");
      assert.ok(!allText.includes("fake-content"), "fake.txt should not have been read");
    });
  });

  it("after-decorator can deny: tool result replaced with deny reason", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      fs.writeFileSync(path.join(work, "secrets.txt"), "PASSWORD=supersecret");
      const provider = fixedProvider([
        `<tool_call>{"name":"read_file","arguments":{"path":"secrets.txt"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"ok"}}</tool_call>`,
      ]);
      const scrubber: ToolDecorator = {
        name: "no-secrets",
        appliesTo: (inv) => inv.name === "read_file",
        after: async (_inv, result) => {
          const text = result.ok ? result.output : result.error;
          return /PASSWORD=/.test(text) ? deny("output contains a secret") : allow({ name: "read_file", arguments: {} });
        },
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "read secrets", config, tools, undefined, [scrubber]);
      const allText = conversation.turns.map((t) => t.content).join("\n");
      assert.ok(!allText.includes("supersecret"), "secret must not reach conversation");
      assert.match(allText, /output blocked by decorator no-secrets/);
    });
  });

  it("first deny in chain short-circuits subsequent decorators", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      const provider = fixedProvider([
        `<tool_call>{"name":"shell","arguments":{"command":"echo hi"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"ok"}}</tool_call>`,
      ]);
      let secondCalled = 0;
      const first: ToolDecorator = {
        name: "first",
        appliesTo: () => true,
        before: async () => deny("first denied"),
      };
      const second: ToolDecorator = {
        name: "second",
        appliesTo: () => true,
        before: async (inv) => { secondCalled++; return allow(inv); },
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "x", config, tools, undefined, [first, second]);
      assert.equal(secondCalled, 0, "second decorator must not run after first deny");
    });
  });

  it("appliesTo=false skips both before and after for that decorator", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      fs.writeFileSync(path.join(work, "x.txt"), "hello");
      const provider = fixedProvider([
        `<tool_call>{"name":"read_file","arguments":{"path":"x.txt"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"ok"}}</tool_call>`,
      ]);
      let beforeRan = 0;
      let afterRan = 0;
      const shellOnly: ToolDecorator = {
        name: "shell-only",
        appliesTo: (inv) => inv.name === "shell",
        before: async (inv) => { beforeRan++; return allow(inv); },
        after: async (inv) => { afterRan++; return allow(inv); },
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "read it", config, tools, undefined, [shellOnly]);
      assert.equal(beforeRan, 0, "before should not run on non-matching tool");
      assert.equal(afterRan, 0, "after should not run on non-matching tool");
    });
  });

  it("artifact hook: after-Deny on message_user pushes deny back as user turn and continues the loop", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      // First attempt: agent says "bad" → decorator denies. Second attempt: "ok" → accepted.
      let i = 0;
      const responses = [
        `<tool_call>{"name":"message_user","arguments":{"message":"bad"}}</tool_call>`,
        `<tool_call>{"name":"message_user","arguments":{"message":"ok"}}</tool_call>`,
      ];
      const provider = async () => responses[i++] ?? responses[1];
      let invocations = 0;
      const denyBad: ToolDecorator = {
        name: "deny-bad-artifact",
        appliesTo: (inv) => inv.name === "message_user",
        after: async (_inv, result) => {
          invocations++;
          const text = result.ok ? result.output : result.error;
          return text === "bad" ? deny("artifact says 'bad'") : allow({ name: "message_user", arguments: {} });
        },
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      const result = await handleTurn(provider, conversation, "go", config, tools, undefined, [denyBad]);
      // Decorator fired twice: once on "bad" (deny), once on "ok" (allow).
      assert.equal(invocations, 2);
      // Final delivered content is "ok".
      assert.equal(result.content, "ok");
      // Conversation contains the deny msg as a user-role turn (forcing the revision).
      const userTurns = conversation.turns.filter((t) => t.role === "user").map((t) => t.content);
      assert.ok(
        userTurns.some((c) => c.includes("blocked by decorator deny-bad-artifact")),
        `expected deny msg in user turns, got: ${userTurns.join(" | ")}`,
      );
    });
  });

  it("chain composition: first decorator's Allow{modified args} flows into the next decorator's input", async () => {
    await withTempDir(async (dir) => {
      const work = path.join(dir, "work");
      fs.mkdirSync(work);
      fs.writeFileSync(path.join(work, "real.txt"), "real-content");
      const provider = (() => {
        let i = 0;
        const responses = [
          `<tool_call>{"name":"read_file","arguments":{"path":"fake.txt"}}</tool_call>`,
          `<tool_call>{"name":"message_user","arguments":{"message":"done"}}</tool_call>`,
        ];
        return async () => responses[i++] ?? responses[1];
      })();
      // Decorator A: rewrites path to real.txt.
      const swap: ToolDecorator = {
        name: "swap-path",
        appliesTo: (inv) => inv.name === "read_file",
        before: async (inv) => allow({
          name: inv.name,
          arguments: { ...inv.arguments, path: path.join(work, "real.txt") },
        }),
      };
      // Decorator B: asserts the path it sees is the rewritten one.
      let observed: unknown = null;
      const observe: ToolDecorator = {
        name: "observe-path",
        appliesTo: (inv) => inv.name === "read_file",
        before: async (inv) => {
          observed = (inv.arguments as Record<string, unknown>).path;
          return allow(inv);
        },
      };
      const config = { ...defaultConfig(), workDir: work };
      const conversation = createConversation("sys");
      await handleTurn(provider, conversation, "read fake", config, tools, undefined, [swap, observe]);
      assert.equal(observed, path.join(work, "real.txt"), "decorator B should see decorator A's rewrite");
    });
  });
});
