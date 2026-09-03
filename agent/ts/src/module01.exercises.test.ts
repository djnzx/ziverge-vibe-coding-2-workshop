import { describe, it, before, after } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { tools } from "./agent.js";
import { defaultConfig } from "./config.js";
import { createConversation, type ChatProvider, type Conversation } from "./conversation.js";
import {
  handleTurn,
  MAX_OUTPUT_CHARS,
  buildSystemPrompt,
  parseToolCalls,
  truncateOutput,
  NAIVE_SYSTEM_PROMPT,
  verifySudoku,
  parseSudokuGrid,
  evaluateResponse,
  executeListFiles,
} from "./exercises.js";

const cfg = defaultConfig();
let tmpDir: string;
let originalCwd: string;

before(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agent-module01-test-ts-"));
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
  return async (_messages) => {
    if (callIndex >= responses.length) {
      return DONE("No more canned responses.");
    }
    return responses[callIndex++];
  };
}

describe("Exercise 1: Build the system prompt", () => {
  const prompt = buildSystemPrompt(defaultConfig(), tools);

  it("contains all tool names", () => {
    assert.match(prompt, /read_file/);
    assert.match(prompt, /write_file/);
    assert.match(prompt, /edit_file/);
    assert.match(prompt, /shell/);
    assert.match(prompt, /message_user/);
  });

  it("contains the tool_call protocol", () => {
    assert.match(prompt, /<tool_call>/);
    assert.match(prompt, /<\/tool_call>/);
  });

  it("contains JSON schemas for each tool", () => {
    assert.match(prompt, /"path"/);
    assert.match(prompt, /"command"/);
    assert.match(prompt, /"message"/);
  });
});

describe("Exercise 2: Parse tool calls from LLM text", () => {
  it("extracts a single tool call", () => {
    const text = `Let me read that file.\n<tool_call>\n{"name": "read_file", "arguments": {"path": "foo.txt"}}\n</tool_call>`;
    const { calls } = parseToolCalls(text);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "read_file");
    assert.deepEqual(calls[0].arguments, { path: "foo.txt" });
  });

  it("extracts multiple tool calls", () => {
    const text = `<tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call> then <tool_call>{"name": "shell", "arguments": {"command": "ls"}}</tool_call>`;
    const { calls } = parseToolCalls(text);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].name, "read_file");
    assert.equal(calls[1].name, "shell");
  });

  it("returns empty for plain text", () => {
    const { calls, errors } = parseToolCalls("Just some commentary.");
    assert.equal(calls.length, 0);
    assert.equal(errors.length, 0);
  });

  it("reports malformed JSON as error", () => {
    const text = `<tool_call>this is not json</tool_call>`;
    const { calls, errors } = parseToolCalls(text);
    assert.equal(calls.length, 0);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /Malformed tool call JSON/);
  });

  it("reports missing name as error", () => {
    const text = `<tool_call>{"arguments": {"path": "foo.txt"}}</tool_call>`;
    const { calls, errors } = parseToolCalls(text);
    assert.equal(calls.length, 0);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /missing "name"/);
  });

  it("reports empty name as error", () => {
    const text = `<tool_call>{"name": "", "arguments": {"path": "foo.txt"}}</tool_call>`;
    const { calls, errors } = parseToolCalls(text);
    assert.equal(calls.length, 0);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /missing "name"/);
  });

  it("extracts valid calls and reports broken ones as errors", () => {
    const text = `<tool_call>broken</tool_call><tool_call>{"name": "shell", "arguments": {"command": "pwd"}}</tool_call>`;
    const { calls, errors } = parseToolCalls(text);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "shell");
    assert.equal(errors.length, 1);
    assert.match(errors[0], /Malformed/);
  });
});

describe("Exercise 3: Implement the agent loop", () => {
  it("executes a tool call and completes", async () => {
    const provider = mockProvider([
      `THINKING: I'll create the file.\n<tool_call>\n{"name": "write_file", "arguments": {"path": "out.txt", "content": "hello"}}\n</tool_call>`,
      DONE("File created."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Create out.txt", cfgForTmp(), tools);
    assert.ok(result.toolCalls.includes("write_file"));
    assert.equal(
      result.toolCalls[result.toolCalls.length - 1],
      "message_user",
      "message_user should be the terminating tool in tool_calls",
    );
    assert.equal(fs.readFileSync("out.txt", "utf-8"), "hello");
    assert.match(result.content, /File created/);
  });

  it("ignores text after tool_call and still executes tool", async () => {
    const provider = mockProvider([
      `<tool_call>\n{"name": "write_file", "arguments": {"path": "before-done.txt", "content": "written"}}\n</tool_call>\nSome trailing text`,
      DONE("Done."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Create before-done.txt", cfgForTmp(), tools);
    assert.ok(result.toolCalls.includes("write_file"));
    assert.ok(fs.existsSync("before-done.txt"));
    assert.equal(fs.readFileSync("before-done.txt", "utf-8"), "written");
  });

  it("nudges the model when it responds with no tool call", async () => {
    const provider = mockProvider([
      "Let me think about this...",
      "Still thinking...",
      DONE("OK here we go."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Do something", cfgForTmp(), tools);

    const nudges = conversation.turns.filter(
      (m) => m.role === "user" && m.content.includes("must call a tool")
    );
    assert.ok(nudges.length >= 2, "should have nudged at least twice");
    assert.match(result.content, /here we go/);
  });

  it("only executes first tool call per response", async () => {
    const provider = mockProvider([
      `<tool_call>{"name": "write_file", "arguments": {"path": "a.txt", "content": "A"}}</tool_call>\n<tool_call>{"name": "write_file", "arguments": {"path": "b.txt", "content": "B"}}</tool_call>`,
      DONE("Done."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Create two files", cfgForTmp(), tools);
    assert.equal(fs.readFileSync("a.txt", "utf-8"), "A");
    assert.ok(!fs.existsSync("b.txt"), "second tool call should be ignored — one per turn");
  });

  it("handles read after write across iterations", async () => {
    const provider = mockProvider([
      `<tool_call>{"name": "write_file", "arguments": {"path": "data.txt", "content": "42"}}</tool_call>`,
      `<tool_call>{"name": "read_file", "arguments": {"path": "data.txt"}}</tool_call>`,
      DONE("The file contains 42."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Write then read", cfgForTmp(), tools);
    assert.ok(result.toolCalls.includes("write_file"));
    assert.ok(result.toolCalls.includes("read_file"));
    assert.match(result.content, /42/);
  });

  it("resolves relative file paths against config.workDir", async () => {
    const workDir = path.join(tmpDir, "workdir-resolution");
    fs.mkdirSync(workDir, { recursive: true });

    const provider = mockProvider([
      `<tool_call>{"name": "write_file", "arguments": {"path": "workdir-only.txt", "content": "hello"}}</tool_call>`,
      DONE("done"),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "write file", { ...cfg, workDir }, tools);

    assert.equal(fs.readFileSync(path.join(workDir, "workdir-only.txt"), "utf-8"), "hello");
    assert.ok(
      !fs.existsSync(path.join(tmpDir, "workdir-only.txt")),
      "should not resolve relative path from process cwd"
    );
  });

  it("reports unknown tool gracefully", async () => {
    const provider = mockProvider([
      `<tool_call>{"name": "delete_file", "arguments": {"path": "x.txt"}}</tool_call>`,
      DONE("That tool didn't work."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Delete a file", cfgForTmp(), tools);

    const toolResultMsg = conversation.turns.find(
      (m) => m.role === "user" && m.content.includes("Unknown tool")
    );
    assert.ok(toolResultMsg, "should report unknown tool back to model");
  });

  it("returns max iterations when model never completes", async () => {
    const neverDone = Array(25).fill("Still working...");
    const provider = mockProvider(neverDone);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Infinite task", cfgForTmp(), tools);
    assert.match(result.content, /Max iterations reached/);
  });

  it("forces another iteration after tool calls so model sees results", async () => {
    let callCount = 0;
    const provider: ChatProvider = async (messages) => {
      callCount++;
      if (callCount === 1) {
        return `<tool_call>{"name": "shell", "arguments": {"command": "echo real-output"}}</tool_call>`;
      }
      const lastUserMsg = [...messages].reverse().find((m) => m.role === "user");
      if (lastUserMsg && lastUserMsg.content.includes("real-output")) {
        return DONE("The output was real-output.");
      }
      return DONE("Something went wrong.");
    };
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Run echo", cfgForTmp(), tools);
    assert.ok(callCount >= 2, `model should get a second turn, got ${callCount} calls`);
    assert.match(result.content, /real-output/);
  });

  it("feeds malformed JSON parse errors back to the model", async () => {
    let callCount = 0;
    const provider: ChatProvider = async (_messages) => {
      callCount++;
      if (callCount === 1) {
        return `<tool_call>this is broken json</tool_call>`;
      }
      return DONE("Fixed it.");
    };
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Do stuff", cfgForTmp(), tools);
    assert.ok(callCount >= 2, "model should get a second chance after parse error");

    const errorMsg = conversation.turns.find(
      (m) => m.role === "user" && m.content.includes("Tool call parse error")
    );
    assert.ok(errorMsg, "parse error should be fed back to the model");
    assert.match(errorMsg!.content, /Malformed/);
    assert.match(errorMsg!.content, /fix the JSON/);
  });

  it("retries when message_user arguments are invalid", async () => {
    let callCount = 0;
    const provider: ChatProvider = async (_messages) => {
      callCount++;
      if (callCount === 1) {
        return `<tool_call>{"name":"message_user","arguments":{"msg":"bad"}}</tool_call>`;
      }
      return DONE("Recovered.");
    };
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Do stuff", cfgForTmp(), tools);

    assert.equal(result.content, "Recovered.");
    assert.ok(callCount >= 2, "model should get a second chance after invalid message_user args");
    const errorMsg = conversation.turns.find(
      (m) => m.role === "user" && m.content.includes("Invalid arguments for message_user")
    );
    assert.ok(errorMsg, "invalid message_user args should be fed back as parse error");
  });
});

describe("Exercise 4: Implement read_file", () => {
  const findTool = (name: string) => tools.find((t) => t.name === name)!;

  it("read_file reads an existing file", async () => {
    const filePath = path.join(tmpDir, "test-read.txt");
    fs.writeFileSync(filePath, "contents here");
    const result = await findTool("read_file").execute({ path: filePath });
    assert.deepEqual(result, { ok: true, output: "contents here" });
  });

  it("read_file returns error for missing file", async () => {
    const result = await findTool("read_file").execute({ path: "/nonexistent/path.txt" });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /reading file/);
    }
  });

  it("read_file returns invalid arguments error for bad args", async () => {
    const result = await findTool("read_file").execute({});
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /invalid arguments:/);
    }
  });
});

describe("Exercise 5: Implement shell", () => {
  const findTool = (name: string) => tools.find((t) => t.name === name)!;

  it("shell runs a command and returns stdout", async () => {
    const result = await findTool("shell").execute({ command: "echo hello" });
    assert.equal(result.ok, true);
    if (result.ok) {
      assert.equal(result.output.trim(), "hello");
    }
  });

  it("shell returns error for failing command", async () => {
    const result = await findTool("shell").execute({ command: "false" });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.ok(result.error.length > 0);
      assert.ok(!result.error.startsWith("Error"));
    }
  });

  it("shell returns invalid arguments error for bad args", async () => {
    const result = await findTool("shell").execute({});
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /invalid arguments:/);
    }
  });

  it("shell runs with config.workDir as cwd through handleTurn", async () => {
    const workDir = path.join(tmpDir, "shell-cwd");
    fs.mkdirSync(workDir, { recursive: true });

    const provider = mockProvider([
      `<tool_call>{"name": "shell", "arguments": {"command": "node -e 'process.stdout.write(process.cwd())'"}}</tool_call>`,
      DONE("done"),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "show cwd", { ...cfg, workDir }, tools);

    const shellResult = conversation.turns.find(
      (turn) => turn.role === "user" && turn.content.startsWith("Tool shell returned:\n")
    );
    assert.ok(shellResult, "shell result should be fed back to model");
    assert.ok(shellResult!.content.includes(workDir), `shell should execute in ${workDir}`);
  });

});

describe("Exercise 6: Iterate on the system prompt", () => {
  const config = defaultConfig();
  const prompt = buildSystemPrompt(config, tools);

  it("gold prompt mentions one tool call per turn", () => {
    assert.ok(
      /one tool.*(call|action)|single tool|one.*(action|step)/i.test(prompt),
      "prompt should instruct one tool call per turn",
    );
  });

  it("gold prompt warns against predicting tool results", () => {
    assert.ok(
      /never predict|do not (guess|fabricate|predict)|not seen the output/i.test(prompt),
      "prompt should warn against predicting/fabricating tool results",
    );
  });

  it("gold prompt mentions multi-turn loop", () => {
    assert.ok(
      /iteration|multi.?turn|loop|next turn|subsequent/i.test(prompt),
      "prompt should mention the multi-turn loop",
    );
  });

  it("naive prompt lacks defensive clauses", () => {
    assert.ok(
      !/do not (guess|fabricate)|one tool.*(call|action)|iteration|multi.?turn/i.test(NAIVE_SYSTEM_PROMPT),
      "naive prompt should lack the defensive clauses the gold prompt has",
    );
  });
});

describe("Exercise 7: Harden the loop", () => {
  it("handles flat format without arguments wrapper", () => {
    const text = `<tool_call>{"name": "write_file", "path": "foo.txt", "content": "bar"}</tool_call>`;
    const { calls } = parseToolCalls(text);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "write_file");
    assert.deepEqual(calls[0].arguments, { path: "foo.txt", content: "bar" });
  });

  it("passes through short output unchanged", () => {
    assert.equal(truncateOutput("short"), "short");
  });

  it("truncates long output with marker", () => {
    const long = "x".repeat(MAX_OUTPUT_CHARS + 500);
    const result = truncateOutput(long);
    assert.ok(result.length < long.length);
    assert.match(result, /\[truncated — 500 more chars\]/);
  });

  it("truncates large tool output in conversation history", async () => {
    const hugeLine = "x".repeat(MAX_OUTPUT_CHARS + 5000);
    fs.writeFileSync("huge.txt", hugeLine);
    const provider = mockProvider([
      `<tool_call>{"name": "read_file", "arguments": {"path": "huge.txt"}}</tool_call>`,
      DONE("Got it."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Read huge file", cfgForTmp(), tools);

    const toolResultMsg = conversation.turns.find(
      (m) => m.role === "user" && m.content.includes("[truncated")
    );
    assert.ok(toolResultMsg, "tool result should be truncated in conversation");
    assert.ok(
      toolResultMsg!.content.length < hugeLine.length,
      "truncated message should be shorter than raw output"
    );
  });

  it("truncates assistant message history at first </tool_call>", async () => {
    const provider = mockProvider([
      `THINKING: I'll do two things.\n<tool_call>{"name": "write_file", "arguments": {"path": "first.txt", "content": "1"}}</tool_call>\nExtra text and <tool_call>{"name": "write_file", "arguments": {"path": "second.txt", "content": "2"}}</tool_call>`,
      DONE("Done."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    await handleTurn(provider, conversation, "Do stuff", cfgForTmp(), tools);

    const assistantMsgs = conversation.turns.filter((m) => m.role === "assistant");
    for (const msg of assistantMsgs) {
      const toolCallCount = (msg.content.match(/<tool_call>/g) || []).length;
      assert.ok(toolCallCount <= 1, `assistant message should have at most 1 tool call in history, found ${toolCallCount}`);
      assert.ok(!msg.content.includes("Extra text"), "text after first </tool_call> should be pruned from history");
    }
  });
});

// ── Exercise 9: Sudoku verification ──────────────────────────────────────

describe("Exercise 9: Sudoku — the reasoning illusion", () => {
  const VALID_GRID = [
    [5, 3, 4, 6, 7, 8, 9, 1, 2],
    [6, 7, 2, 1, 9, 5, 3, 4, 8],
    [1, 9, 8, 3, 4, 2, 5, 6, 7],
    [8, 5, 9, 7, 6, 1, 4, 2, 3],
    [4, 2, 6, 8, 5, 3, 7, 9, 1],
    [7, 1, 3, 9, 2, 4, 8, 5, 6],
    [9, 6, 1, 5, 3, 7, 2, 8, 4],
    [2, 8, 7, 4, 1, 9, 6, 3, 5],
    [3, 4, 5, 2, 8, 6, 1, 7, 9],
  ];

  it("returns empty array for a valid grid", () => {
    assert.deepEqual(verifySudoku(VALID_GRID), []);
  });

  it("detects row violations", () => {
    const bad = VALID_GRID.map(r => [...r]);
    bad[0][0] = bad[0][1]; // duplicate in row 1
    const errors = verifySudoku(bad);
    assert.ok(errors.some(e => e.startsWith("Row 1")));
  });

  it("detects column violations", () => {
    const bad = VALID_GRID.map(r => [...r]);
    bad[0][0] = bad[1][0]; // duplicate in col 1
    const errors = verifySudoku(bad);
    assert.ok(errors.some(e => e.startsWith("Col 1")));
  });

  it("detects box violations", () => {
    const bad = VALID_GRID.map(r => [...r]);
    bad[0][0] = bad[1][1]; // duplicate in box (1,1)
    const errors = verifySudoku(bad);
    assert.ok(errors.some(e => e.startsWith("Box (1,1)")));
  });

  it("parseSudokuGrid parses a space-separated text grid", () => {
    const text = VALID_GRID.map(r => r.join(" ")).join("\n");
    const grid = parseSudokuGrid(text);
    assert.deepEqual(grid, VALID_GRID);
  });

  it("parseSudokuGrid returns null for incomplete grids", () => {
    assert.equal(parseSudokuGrid("1 2 3\n4 5 6"), null);
  });
});

// ── Exercise 10: Fabrication detection ───────────────────────────────────

describe("Exercise 10: The fabrication test", () => {
  it("returns SPECIFIED when judge says SPECIFIED", async () => {
    const provider: ChatProvider = async () => "SPECIFIED";
    const result = await evaluateResponse(provider, "here are 5 user records: [{...}]");
    assert.equal(result, "SPECIFIED");
  });

  it("returns UNSPECIFIED when judge says UNSPECIFIED", async () => {
    const provider: ChatProvider = async () => "UNSPECIFIED";
    const result = await evaluateResponse(provider, "I don't have access to that database");
    assert.equal(result, "UNSPECIFIED");
  });

  it("handles case-insensitive judge responses", async () => {
    const provider: ChatProvider = async () => "  specified  ";
    const result = await evaluateResponse(provider, "fake data");
    assert.equal(result, "SPECIFIED");
  });

  it("sends the agent response in the judge prompt", async () => {
    let capturedPrompt = "";
    const provider: ChatProvider = async (msgs) => {
      capturedPrompt = msgs[0].content as string;
      return "SPECIFIED";
    };
    await evaluateResponse(provider, "AGENT_OUTPUT_MARKER");
    assert.ok(capturedPrompt.includes("AGENT_OUTPUT_MARKER"));
  });
});

describe("Exercise 11: Implement edit_file", () => {
  const findTool = (name: string) => tools.find((t) => t.name === name)!;

  it("edit_file replaces text within line range", async () => {
    const filePath = path.join(tmpDir, "test-edit.txt");
    fs.writeFileSync(filePath, "line1\nline2\nline3\nline4\n");
    const result = await findTool("edit_file").execute({
      path: filePath, line_start: 2, line_end: 3, old_text: "line2\nline3", new_text: "replaced2\nreplaced3",
    });
    assert.equal(result.ok, true);
    if (result.ok) {
      assert.equal(result.output, `Successfully edited ${filePath} (2 lines)`);
    }
    assert.equal(fs.readFileSync(filePath, "utf-8"), "line1\nreplaced2\nreplaced3\nline4\n");
  });

  it("edit_file leaves file unmodified when old_text not found", async () => {
    const filePath = path.join(tmpDir, "test-edit-noop.txt");
    fs.writeFileSync(filePath, "aaa\nbbb\nccc\n");
    const result = await findTool("edit_file").execute({
      path: filePath, line_start: 1, line_end: 2, old_text: "zzz", new_text: "yyy",
    });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /old_text not found/);
    }
    assert.equal(fs.readFileSync(filePath, "utf-8"), "aaa\nbbb\nccc\n");
  });

  it("edit_file rejects invalid line range", async () => {
    const filePath = path.join(tmpDir, "test-edit-range.txt");
    fs.writeFileSync(filePath, "one\ntwo\n");
    const result = await findTool("edit_file").execute({
      path: filePath, line_start: 5, line_end: 10, old_text: "x", new_text: "y",
    });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /out of range/);
    }
  });

  it("edit_file rejects line_end beyond file length", async () => {
    const filePath = path.join(tmpDir, "test-edit-range-end.txt");
    fs.writeFileSync(filePath, "one\ntwo\n");
    const result = await findTool("edit_file").execute({
      path: filePath, line_start: 1, line_end: 10, old_text: "one", new_text: "ONE",
    });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /line_end 10 is out of range/);
    }
  });

  it("edit_file returns invalid arguments error for bad args", async () => {
    const result = await findTool("edit_file").execute({});
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /invalid arguments:/);
    }
  });

  it("edit_file treats new_text literally (no JS $ replacement semantics)", async () => {
    const filePath = path.join(tmpDir, "test-edit-literal-new-text.txt");
    fs.writeFileSync(filePath, "alpha\nbeta\ngamma\n");
    const result = await findTool("edit_file").execute({
      path: filePath,
      line_start: 2,
      line_end: 2,
      old_text: "beta",
      new_text: "$&",
    });
    assert.equal(result.ok, true);
    assert.equal(fs.readFileSync(filePath, "utf-8"), "alpha\n$&\ngamma\n");
  });

  it("edit_file modifies a file through the loop", async () => {
    fs.writeFileSync("edit-target.txt", "line1\nold_value\nline3\n");
    const provider = mockProvider([
      `<tool_call>{"name": "edit_file", "arguments": {"path": "edit-target.txt", "line_start": 2, "line_end": 2, "old_text": "old_value", "new_text": "new_value"}}</tool_call>`,
      DONE("Edited."),
    ]);
    const conversation: Conversation = createConversation("system prompt");

    const result = await handleTurn(provider, conversation, "Edit the file", cfgForTmp(), tools);
    assert.ok(result.toolCalls.includes("edit_file"));
    assert.equal(fs.readFileSync("edit-target.txt", "utf-8"), "line1\nnew_value\nline3\n");
  });
});

// ── Exercise 12: list_files tool ─────────────────────────────────────────

describe("Exercise 12: Implement the list_files tool", () => {
  let tmpDir: string;

  before(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "ex12-"));
    fs.mkdirSync(path.join(tmpDir, "sub"));
    fs.writeFileSync(path.join(tmpDir, "a.txt"), "a");
    fs.writeFileSync(path.join(tmpDir, "b.txt"), "b");
    fs.writeFileSync(path.join(tmpDir, "sub", "c.txt"), "c");
    fs.mkdirSync(path.join(tmpDir, "sub", "deep"));
    fs.writeFileSync(path.join(tmpDir, "sub", "deep", "d.txt"), "d");
  });

  after(() => {
    fs.rmSync(tmpDir, { recursive: true });
  });

  it("lists immediate children at depth 1", () => {
    const result = executeListFiles({ path: tmpDir, max_depth: 1 });
    assert.ok(result.ok);
    const output = result.ok ? result.output : "";
    assert.ok(output.includes("a.txt"));
    assert.ok(output.includes("b.txt"));
    assert.ok(output.includes("sub/"));
    assert.ok(!output.includes("c.txt"));
  });

  it("lists recursively at depth 2", () => {
    const result = executeListFiles({ path: tmpDir, max_depth: 2 });
    assert.ok(result.ok);
    const output = result.ok ? result.output : "";
    assert.ok(output.includes("sub/c.txt"));
    assert.ok(output.includes("sub/deep/"));
    assert.ok(!output.includes("d.txt"));
  });

  it("returns error for missing directory", () => {
    const result = executeListFiles({ path: "/nonexistent/dir", max_depth: 1 });
    assert.ok(!result.ok);
  });

  it("returns sorted output", () => {
    const result = executeListFiles({ path: tmpDir, max_depth: 1 });
    assert.ok(result.ok);
    const lines = (result.ok ? result.output : "").split("\n");
    assert.equal(lines[0], "a.txt");
    assert.equal(lines[1], "b.txt");
    assert.equal(lines[2], "sub/");
  });
});
