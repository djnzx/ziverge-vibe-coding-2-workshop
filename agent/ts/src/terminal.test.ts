import { describe, it, before, after } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { tools } from "./agent.js";
import { defaultConfig } from "./config.js";
import { createConversation, type ChatProvider, type Conversation } from "./conversation.js";
import { handleTurn } from "./exercises.js";
import { RecordingTerminal, formatArgValue, type TerminalEvent } from "./terminal.js";

const cfg = defaultConfig();
let tmpDir: string;
let originalCwd: string;

before(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agent-terminal-test-"));
  originalCwd = process.cwd();
  process.chdir(tmpDir);
});

after(() => {
  process.chdir(originalCwd);
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

const DONE = (msg: string) =>
  `<tool_call>{"name": "message_user", "arguments": {"message": "${msg}"}}</tool_call>`;

function cfgForTmp() {
  return { ...cfg, workDir: tmpDir };
}

function mockProvider(responses: string[]): ChatProvider {
  let callIndex = 0;
  return async () => {
    if (callIndex >= responses.length) {
      return DONE("No more canned responses.");
    }
    return responses[callIndex++];
  };
}

function eventsOfType<T extends TerminalEvent["type"]>(
  events: TerminalEvent[],
  type: T
): Extract<TerminalEvent, { type: T }>[] {
  return events.filter((e): e is Extract<TerminalEvent, { type: T }> => e.type === type);
}

describe("formatArgValue", () => {
  it("returns simple strings unquoted", () => {
    assert.equal(formatArgValue("hello.txt"), "hello.txt");
  });

  it("returns multiline strings in YAML block-scalar form", () => {
    assert.equal(formatArgValue("line1\nline2"), "|\n      line1\n      line2");
  });

  it("returns numbers as-is", () => {
    assert.equal(formatArgValue(42), "42");
  });

  it("returns booleans as-is", () => {
    assert.equal(formatArgValue(true), "true");
    assert.equal(formatArgValue(false), "false");
  });

  it("returns objects as compact JSON", () => {
    assert.equal(formatArgValue({ a: 1 }), '{"a":1}');
  });

  it("returns arrays as compact JSON", () => {
    assert.equal(formatArgValue([1, 2, 3]), "[1,2,3]");
  });

  it("truncates multiline strings beyond 8 lines", () => {
    const lines = Array.from({ length: 12 }, (_, i) => `line${i + 1}`);
    const result = formatArgValue(lines.join("\n"));
    assert.ok(result.startsWith("|\n"));
    assert.ok(result.includes("line8"));
    assert.ok(!result.includes("line9"));
    assert.ok(result.endsWith("[+4 more lines]"));
  });
});

describe("RecordingTerminal", () => {
  it("records all event types", () => {
    const term = new RecordingTerminal();
    term.banner("model", ["read_file"]);
    term.thinking("thought");
    term.toolCall({ name: "read_file", arguments: { path: "f.txt" } });
    term.toolResult({ ok: true, output: "ok" });
    term.answer("done");
    term.error("oops");
    term.spinnerStart();
    term.spinnerStop();
    term.goodbye();

    assert.equal(term.events.length, 9);
    assert.equal(term.events[0].type, "banner");
    assert.equal(term.events[8].type, "goodbye");
  });

  it("promptString returns plain prompt", () => {
    const term = new RecordingTerminal();
    assert.equal(term.promptString(), "> ");
  });
});

describe("handleTurn terminal interactions", () => {
  it("records spinner start/stop around LLM call", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([DONE("hi")]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "hello", cfgForTmp(), tools, term);

    const spinnerStarts = eventsOfType(term.events, "spinnerStart");
    const spinnerStops = eventsOfType(term.events, "spinnerStop");
    assert.ok(spinnerStarts.length >= 1, "should start spinner at least once");
    assert.ok(spinnerStops.length >= 1, "should stop spinner at least once");
    assert.equal(spinnerStarts.length, spinnerStops.length, "start/stop should be balanced");
  });

  it("records thinking text", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([
      "THINKING: I need to analyze this.\nACTION: respond\n" + DONE("Analyzed."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Analyze", cfgForTmp(), tools, term);

    const thinkingEvents = eventsOfType(term.events, "thinking");
    assert.ok(thinkingEvents.length >= 1, "should record thinking");
    assert.ok(thinkingEvents[0].text.includes("analyze"), "should contain thinking text");
  });

  it("records tool call and result", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([
      `<tool_call>{"name": "write_file", "arguments": {"path": "rec.txt", "content": "data"}}</tool_call>`,
      DONE("Written."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Write rec.txt", cfgForTmp(), tools, term);

    const toolCalls = eventsOfType(term.events, "toolCall");
    assert.equal(toolCalls.length, 1);
    assert.equal(toolCalls[0].call.name, "write_file");
    assert.equal(toolCalls[0].call.arguments.path, path.join(tmpDir, "rec.txt"));

    const toolResults = eventsOfType(term.events, "toolResult");
    assert.equal(toolResults.length, 1);
    assert.equal(toolResults[0].result.ok, true);
    if (toolResults[0].result.ok) {
      assert.ok(toolResults[0].result.output.includes("Successfully wrote"));
    }
  });

  it("records answer on completion", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([DONE("Final answer here.")]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "question", cfgForTmp(), tools, term);

    const answers = eventsOfType(term.events, "answer");
    assert.equal(answers.length, 1);
    assert.equal(answers[0].text, "Final answer here.");
  });

  it("records error for unknown tool", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([
      `<tool_call>{"name": "nope", "arguments": {}}</tool_call>`,
      DONE("ok"),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "try nope", cfgForTmp(), tools, term);

    const errors = eventsOfType(term.events, "error");
    assert.ok(errors.length >= 1, "should record error for unknown tool");
    assert.ok(errors[0].text.includes("Unknown tool"));
  });

  it("records error on max iterations", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider(Array(25).fill("Still thinking..."));
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Infinite", cfgForTmp(), tools, term);

    const errors = eventsOfType(term.events, "error");
    assert.ok(errors.some((e) => e.text.includes("Max iterations")), "should record max iterations error");
  });

  it("records tool result as error when tool fails", async () => {
    const term = new RecordingTerminal();
    const provider = mockProvider([
      `<tool_call>{"name": "read_file", "arguments": {"path": "/nonexistent/file.txt"}}</tool_call>`,
      DONE("Failed."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Read missing file", cfgForTmp(), tools, term);

    const toolResults = eventsOfType(term.events, "toolResult");
    assert.equal(toolResults.length, 1);
    assert.equal(toolResults[0].result.ok, false);
    if (!toolResults[0].result.ok) {
      assert.ok(toolResults[0].result.error.includes("reading file"));
      assert.ok(!toolResults[0].result.error.startsWith("Error"));
    }
  });

  it("stops spinner when provider throws", async () => {
    const term = new RecordingTerminal();
    const provider: ChatProvider = async () => {
      throw new Error("provider failed");
    };
    const conversation: Conversation = createConversation("system prompt");

    await assert.rejects(
      () => handleTurn(provider, conversation, "boom", cfgForTmp(), tools, term),
      /provider failed/
    );

    const spinnerStarts = eventsOfType(term.events, "spinnerStart");
    const spinnerStops = eventsOfType(term.events, "spinnerStop");
    assert.equal(spinnerStarts.length, 1);
    assert.equal(spinnerStops.length, 1);
  });
});
