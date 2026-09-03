import { describe, it, before, after } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { tools } from "./agent.js";
import {
  defaultConfig,
  resolveConfig,
  parseCliConfig,
  loadConfigFile,
  DEFAULT_SECRET_PATTERNS,
} from "./config.js";
import { createConversation, type ChatProvider, type Conversation } from "./conversation.js";
import { handleTurn } from "./exercises.js";
import { getEnabledTools } from "./tools.js";

function assertParsedConfig(result: ReturnType<typeof parseCliConfig>): Exclude<ReturnType<typeof parseCliConfig>, string> {
  if (typeof result === "string") {
    throw new Error(`Expected parse result object, got error: ${result}`);
  }
  return result;
}

describe("defaultConfig", () => {
  it("returns sensible defaults for module 01 fields", () => {
    const cfg = defaultConfig();
    assert.equal(cfg.model, "anthropic/claude-sonnet-4");
    assert.equal(cfg.maxIterations, 20);
    assert.equal(cfg.temperature, 0);
    assert.deepEqual(cfg.tools, { type: "all" });
    assert.ok(cfg.systemPrompt.endsWith("system-prompt.txt"));
    assert.ok(cfg.workDir.length > 0);
  });

  it("returns null/empty defaults for module 02 fields", () => {
    const cfg = defaultConfig();
    assert.equal(cfg.instructions, null);
    assert.equal(cfg.skillsDir, null);
    assert.equal(cfg.historyDir, null);
    assert.equal(cfg.maxContextChars, null);
  });

  it("returns sensible defaults for module 03 fields", () => {
    const cfg = defaultConfig();
    assert.deepEqual(cfg.allowTools, []);
    assert.deepEqual(cfg.denyTools, []);
    assert.deepEqual(cfg.protectedFiles, []);
    assert.ok(cfg.secretPatterns.length > 0, "should have default secret patterns");
    assert.equal(cfg.auditLog, null);
  });
});

describe("parseCliConfig", () => {
  it("parses module 01 flags", () => {
    const argv = [
      "node", "main.ts",
      "--model", "gpt-4o-mini",
      "--tools", "read_file,shell",
      "--max-iterations", "5",
      "--temperature", "0.7",
      "--work-dir", "/tmp/sandbox",
      "--prompt", "/custom/prompt.txt",
    ];
    const { config } = assertParsedConfig(parseCliConfig(argv));
    assert.equal(config.model, "gpt-4o-mini");
    assert.deepEqual(config.tools, { type: "only", tools: ["read_file", "shell"] });
    assert.equal(config.maxIterations, 5);
    assert.equal(config.temperature, 0.7);
    assert.equal(config.workDir, "/tmp/sandbox");
    assert.equal(config.systemPrompt, "/custom/prompt.txt");
  });

  it("parses module 02 flags", () => {
    const argv = [
      "node", "main.ts",
      "--instructions", "./AGENTS.md",
      "--skills-dir", "./skills",
      "--history-dir", "./history",
      "--resume", "session-123",
      "--max-context-chars", "50000",
    ];
    const { config } = assertParsedConfig(parseCliConfig(argv));
    assert.equal(config.instructions, "./AGENTS.md");
    assert.equal(config.skillsDir, "./skills");
    assert.equal(config.historyDir, "./history");
    assert.equal(assertParsedConfig(parseCliConfig(argv)).resumeSessionId, "session-123");
    assert.equal(config.maxContextChars, 50000);
  });

  it("parses module 03 flags", () => {
    const argv = [
      "node", "main.ts",
      "--allow-tools", "read_file,write_file",
      "--deny-tools", "shell",
      "--protected-files", "*.env,secrets/*",
      "--secret-patterns", "sk-.*,ghp_.*",
      "--audit-log", "./audit.log",
    ];
    const { config } = assertParsedConfig(parseCliConfig(argv));
    assert.deepEqual(config.allowTools, ["read_file", "write_file"]);
    assert.deepEqual(config.denyTools, ["shell"]);
    assert.deepEqual(config.protectedFiles, ["*.env", "secrets/*"]);
    assert.deepEqual(config.secretPatterns, ["sk-.*", "ghp_.*"]);
    assert.equal(config.auditLog, "./audit.log");
  });

  it("returns empty config for no flags", () => {
    const { config } = assertParsedConfig(parseCliConfig(["node", "main.ts"]));
    assert.equal(config.model, undefined);
    assert.equal(config.instructions, undefined);
    assert.equal(config.allowTools, undefined);
  });

  it("parses requested mode as file when only --file is provided", () => {
    const { requestedMode } = assertParsedConfig(parseCliConfig([
      "node", "main.ts", "--file", "test.txt",
    ]));
    assert.deepEqual(requestedMode, { type: "file", path: "test.txt" });
  });

  it("rejects conflicting mode flags", () => {
    const result = parseCliConfig(["node", "main.ts", "--interactive", "--protocol"]);
    assert.equal(typeof result, "string");
    if (typeof result === "string") {
      assert.match(result, /Conflicting mode flags: --interactive and --protocol/);
    }
  });

  it("fails fast on unknown names in --tools", () => {
    const result = parseCliConfig(["node", "main.ts", "--tools", "read_file,not_a_tool"]);
    assert.equal(typeof result, "string");
    if (typeof result === "string") {
      assert.match(result, /Unknown tool name\(s\): not_a_tool/);
    }
  });

  it("treats --tools empty string as only([])", () => {
    const { config } = assertParsedConfig(parseCliConfig(["node", "main.ts", "--tools", ""]));
    assert.deepEqual(config.tools, { type: "only", tools: [] });
  });

  it("fails fast when a value-taking flag is missing its value", () => {
    const valueFlags = [
      "--model",
      "--prompt",
      "--tools",
      "--work-dir",
      "--max-iterations",
      "--temperature",
      "--instructions",
      "--skills-dir",
      "--history-dir",
      "--resume",
      "--max-context-chars",
      "--allow-tools",
      "--deny-tools",
      "--protected-files",
      "--secret-patterns",
      "--audit-log",
      "--config",
      "--file",
    ];

    for (const flag of valueFlags) {
      const result = parseCliConfig(["node", "main.ts", flag]);
      assert.equal(typeof result, "string", `${flag} should return a parse error`);
      if (typeof result === "string") {
        assert.equal(result, `${flag} requires a value`);
      }
    }
  });

  it("fails on non-numeric --max-iterations", () => {
    const result = parseCliConfig(["node", "main.ts", "--max-iterations", "abc"]);
    assert.equal(result, "Invalid value for --max-iterations: abc");
  });

  it("fails on non-numeric --temperature", () => {
    const result = parseCliConfig(["node", "main.ts", "--temperature", "abc"]);
    assert.equal(result, "Invalid value for --temperature: abc");
  });

  it("fails on non-numeric --max-context-chars", () => {
    const result = parseCliConfig(["node", "main.ts", "--max-context-chars", "abc"]);
    assert.equal(result, "Invalid value for --max-context-chars: abc");
  });
});

describe("resolveConfig", () => {
  it("overrides merge with defaults", () => {
    const cfg = resolveConfig({ model: "custom-model", maxIterations: 3 });
    assert.equal(cfg.model, "custom-model");
    assert.equal(cfg.maxIterations, 3);
    assert.equal(cfg.temperature, 0);
    assert.equal(cfg.instructions, null);
  });

  it("preserves module 02 overrides", () => {
    const cfg = resolveConfig({
      instructions: "./AGENTS.md",
      skillsDir: "./skills",
      maxContextChars: 50000,
    });
    assert.equal(cfg.instructions, "./AGENTS.md");
    assert.equal(cfg.skillsDir, "./skills");
    assert.equal(cfg.maxContextChars, 50000);
  });

  it("preserves module 03 overrides", () => {
    const cfg = resolveConfig({
      allowTools: ["read_file"],
      denyTools: ["shell"],
      protectedFiles: ["*.env"],
      auditLog: "./audit.log",
    });
    assert.deepEqual(cfg.allowTools, ["read_file"]);
    assert.deepEqual(cfg.denyTools, ["shell"]);
    assert.deepEqual(cfg.protectedFiles, ["*.env"]);
    assert.equal(cfg.auditLog, "./audit.log");
  });

  it("custom secret patterns override defaults", () => {
    const cfg = resolveConfig({ secretPatterns: ["custom-.*"] });
    assert.deepEqual(cfg.secretPatterns, ["custom-.*"]);
    assert.ok(!cfg.secretPatterns.includes(DEFAULT_SECRET_PATTERNS[0]));
  });
});

describe("getEnabledTools", () => {
  it("returns all tools when config.tools is all", () => {
    const cfg = defaultConfig();
    const enabled = getEnabledTools(cfg, tools);
    assert.equal(enabled.length, tools.length);
  });

  it("filters to specified tools plus message_user", () => {
    const cfg = resolveConfig({ tools: { type: "only", tools: ["read_file", "shell"] } });
    const enabled = getEnabledTools(cfg, tools);
    const names = enabled.map((t) => t.name);
    assert.ok(names.includes("read_file"));
    assert.ok(names.includes("shell"));
    assert.ok(names.includes("message_user"));
    assert.ok(!names.includes("write_file"));
    assert.ok(!names.includes("edit_file"));
  });
});

describe("config affects agent behavior", () => {
  it("maxIterations limits turns", async () => {
    const cfg = resolveConfig({ maxIterations: 2 });
    const provider: ChatProvider = async () => "Still thinking...";
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Do stuff", cfg, tools);
    assert.match(result.content, /Max iterations reached/);

    const assistantMsgs = conversation.turns.filter((m) => m.role === "assistant");
    assert.equal(assistantMsgs.length, 2);
  });

  it("tool filtering prevents use of disabled tools", async () => {
    const cfg = resolveConfig({ tools: { type: "only", tools: ["read_file"] } });
    const enabled = getEnabledTools(cfg, tools);

    const provider: ChatProvider = async () =>
      `<tool_call>{"name": "shell", "arguments": {"command": "echo hi"}}</tool_call>`;
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Run shell", { ...cfg, maxIterations: 1 }, enabled);

    const unknownMsg = conversation.turns.find(
      (m) => m.role === "user" && m.content.includes("Unknown tool")
    );
    assert.ok(unknownMsg, "shell should be unknown when not in enabled tools");
  });
});

describe("config file loading", () => {
  let tmpDir: string;

  before(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "config-test-"));
  });

  after(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it("loadConfigFile reads a JSON config", () => {
    const cfgPath = path.join(tmpDir, "agent.json");
    fs.writeFileSync(cfgPath, JSON.stringify({
      model: "gpt-4o-mini",
      maxIterations: 5,
      temperature: 0.3,
    }));
    const loaded = loadConfigFile(cfgPath);
    assert.equal(loaded.model, "gpt-4o-mini");
    assert.equal(loaded.maxIterations, 5);
    assert.equal(loaded.temperature, 0.3);
  });

  it("--config flag loads file into parseCliConfig", () => {
    const cfgPath = path.join(tmpDir, "cli-config.json");
    fs.writeFileSync(cfgPath, JSON.stringify({
      model: "from-file",
      tools: ["read_file"],
      allowTools: ["read_file"],
    }));
    const { config, configFile } = assertParsedConfig(parseCliConfig(["node", "main.ts", "--config", cfgPath]));
    assert.equal(configFile?.model, "from-file");
    assert.deepEqual(configFile?.tools, { type: "only", tools: ["read_file"] });
    assert.deepEqual(configFile?.allowTools, ["read_file"]);
    assert.equal(config.model, undefined);
  });

  it("CLI flags override config file values", () => {
    const cfgPath = path.join(tmpDir, "override.json");
    fs.writeFileSync(cfgPath, JSON.stringify({
      model: "from-file",
      maxIterations: 10,
    }));
    const { config, configFile } = assertParsedConfig(parseCliConfig([
      "node", "main.ts", "--config", cfgPath, "--model", "from-cli",
    ]));
    const resolved = resolveConfig({ ...configFile, ...config });
    assert.equal(resolved.model, "from-cli");
    assert.equal(resolved.maxIterations, 10);
  });

  it("loadConfigFile maps empty tools array to all tools", () => {
    const cfgPath = path.join(tmpDir, "empty-tools.json");
    fs.writeFileSync(cfgPath, JSON.stringify({ tools: [] }));
    const loaded = loadConfigFile(cfgPath);
    assert.deepEqual(loaded.tools, { type: "all" });
  });

  it("loadConfigFile returns empty config for non-object root", () => {
    const cfgPath = path.join(tmpDir, "non-object-root.json");
    fs.writeFileSync(cfgPath, JSON.stringify(["not", "an", "object"]));
    const loaded = loadConfigFile(cfgPath);
    assert.deepEqual(loaded, {});
  });

  it("loadConfigFile ignores invalid tools shapes and unknown tool names", () => {
    const badShapePath = path.join(tmpDir, "bad-tools-shape.json");
    fs.writeFileSync(badShapePath, JSON.stringify({ tools: "read_file" }));
    const badShapeLoaded = loadConfigFile(badShapePath);
    assert.equal(badShapeLoaded.tools, undefined);

    const unknownNamePath = path.join(tmpDir, "bad-tools-unknown.json");
    fs.writeFileSync(unknownNamePath, JSON.stringify({ tools: ["read_file", "not_a_tool"] }));
    const unknownNameLoaded = loadConfigFile(unknownNamePath);
    assert.equal(unknownNameLoaded.tools, undefined);
  });

  it("loadConfigFile ignores wrong-typed fields and keeps valid ones", () => {
    const cfgPath = path.join(tmpDir, "lenient-fields.json");
    fs.writeFileSync(cfgPath, JSON.stringify({
      model: 123,
      maxIterations: "five",
      temperature: "hot",
      workDir: false,
      allowTools: ["read_file", 99],
      model2: "unused",
      instructions: "./AGENTS.md",
      secretPatterns: ["custom-.*"],
    }));

    const loaded = loadConfigFile(cfgPath);
    assert.equal(loaded.model, undefined);
    assert.equal(loaded.maxIterations, undefined);
    assert.equal(loaded.temperature, undefined);
    assert.equal(loaded.workDir, undefined);
    assert.equal(loaded.allowTools, undefined);
    assert.equal(loaded.instructions, path.resolve(tmpDir, "./AGENTS.md"));
    assert.deepEqual(loaded.secretPatterns, ["custom-.*"]);
  });

  it("loadConfigFile throws on malformed JSON", () => {
    const cfgPath = path.join(tmpDir, "malformed.json");
    fs.writeFileSync(cfgPath, "{\"model\": \"x\"");
    assert.throws(() => loadConfigFile(cfgPath));
  });
});
