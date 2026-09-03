import { describe, it } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { isCommand, handleCommand, parseFilePrompts } from "./agent.js";
import { defaultConfig } from "./config.js";
import { createConversation, type ChatProvider, type Conversation } from "./conversation.js";
import {
  applyCompaction,
  formatConversationForCompaction,
} from "./exercises.js";

describe("isCommand", () => {
  it("recognizes slash commands", () => {
    assert.ok(isCommand("/quit"));
    assert.ok(isCommand("/compact"));
    assert.ok(isCommand("/exit"));
    assert.ok(isCommand("/greet"));
    assert.ok(isCommand("/unknown"));
  });

  it("rejects non-commands", () => {
    assert.ok(!isCommand("hello"));
    assert.ok(!isCommand(""));
    assert.ok(!isCommand("create a /file"));
  });
});

describe("handleCommand", () => {
  const noopProvider: ChatProvider = async () => "";
  const config = defaultConfig();
  const sessionId = "test-session";

  it("returns quit for /quit", async () => {
    const result = await handleCommand("/quit", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "quit");
  });

  it("returns quit for /exit", async () => {
    const result = await handleCommand("/exit", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "quit");
  });

  it("returns unknown for /greet when no command template is configured", async () => {
    const result = await handleCommand("/greet", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "unknown");
    if (result.type === "unknown") {
      assert.equal(result.name, "greet");
    }
  });

  it("executes /greet when supplied by commandsDir", async () => {
    const commandsDir = fs.mkdtempSync(path.join(os.tmpdir(), "agent-commands-"));
    try {
      fs.writeFileSync(
        path.join(commandsDir, "greet.md"),
        "---\ndescription: Greet someone\n---\nGreet $ARGUMENTS.",
      );
      let receivedPrompt = "";
      const provider: ChatProvider = async (messages) => {
        receivedPrompt = messages[0].content;
        return "Hello, Ada!";
      };

      const result = await handleCommand(
        "/greet Ada",
        provider,
        createConversation("system prompt"),
        { ...config, commandsDir },
        sessionId,
      );

      assert.deepEqual(result, { type: "custom", response: "Hello, Ada!" });
      assert.equal(receivedPrompt, "Greet Ada.");
    } finally {
      fs.rmSync(commandsDir, { recursive: true, force: true });
    }
  });

  it("returns unknown for unrecognized commands", async () => {
    const result = await handleCommand("/foo", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "unknown");
    if (result.type === "unknown") {
      assert.equal(result.name, "foo");
    }
  });

  it("matches command name case-sensitively", async () => {
    const result = await handleCommand("/Quit", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "unknown");
    if (result.type === "unknown") {
      assert.equal(result.name, "Quit");
    }
  });

  it("parses only the first token as command name", async () => {
    const result = await handleCommand("/compact now", noopProvider, createConversation("system prompt"), config, sessionId);
    assert.equal(result.type, "compact");
  });

  it("calls provider with compaction prompt for /compact", async () => {
    let receivedPrompt = "";
    const mockProvider: ChatProvider = async (msgs) => {
      receivedPrompt = msgs[0].content;
      return "Summary of conversation.";
    };

    const conversation: Conversation = createConversation("system prompt");
    conversation.turns.push(
      { role: "user", content: "create hello.txt" },
      { role: "assistant", content: "done" }
    );

    const result = await handleCommand("/compact", mockProvider, conversation, config, sessionId);
    assert.equal(result.type, "compact");
    if (result.type === "compact") {
      assert.equal(result.summary, "Summary of conversation.");
    }
    assert.ok(receivedPrompt.includes("create hello.txt"));
    assert.ok(!receivedPrompt.includes("system prompt"));
  });
});

describe("applyCompaction", () => {
  it("replaces conversation with summary, preserving system message", () => {
    const conversation: Conversation = createConversation("system prompt");
    conversation.turns.push(
      { role: "user", content: "msg1" },
      { role: "assistant", content: "resp1" },
      { role: "user", content: "msg2" },
      { role: "assistant", content: "resp2" }
    );

    applyCompaction(conversation, "This is the summary.");
    assert.equal(conversation.system, "system prompt");
    assert.equal(conversation.turns.length, 2);
    assert.equal(conversation.turns[0].role, "user");
    assert.ok(conversation.turns[0].content.includes("This is the summary."));
    assert.equal(conversation.turns[1].role, "assistant");
  });
});

describe("formatConversationForCompaction", () => {
  it("excludes system messages", () => {
    const conversation: Conversation = createConversation("secret");
    conversation.turns.push(
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi" }
    );
    const result = formatConversationForCompaction(conversation);
    assert.ok(!result.includes("secret"));
    assert.ok(result.includes("user: hello"));
    assert.ok(result.includes("assistant: hi"));
  });
});

describe("parseFilePrompts", () => {
  it("splits on --- delimiter", () => {
    const content = "first prompt\n---\nsecond prompt\n---\nthird prompt";
    const prompts = parseFilePrompts(content);
    assert.deepEqual(prompts, ["first prompt", "second prompt", "third prompt"]);
  });

  it("handles multiline prompts", () => {
    const content = "line one\nline two\nline three\n---\nsecond";
    const prompts = parseFilePrompts(content);
    assert.equal(prompts.length, 2);
    assert.ok(prompts[0].includes("line one"));
    assert.ok(prompts[0].includes("line three"));
  });

  it("skips empty sections", () => {
    const content = "first\n---\n---\n\n---\nsecond";
    const prompts = parseFilePrompts(content);
    assert.deepEqual(prompts, ["first", "second"]);
  });

  it("handles single prompt with no delimiter", () => {
    const content = "just one prompt";
    const prompts = parseFilePrompts(content);
    assert.deepEqual(prompts, ["just one prompt"]);
  });
});

describe("interactive mode EOF handling", () => {
  it("exits cleanly when stdin closes during interactive mode", async () => {
    const { execSync } = await import("child_process");
    const output = execSync(
      'echo "" | npx tsx src/main.ts --interactive',
      {
        cwd: path.resolve(path.dirname(new URL(import.meta.url).pathname), ".."),
        encoding: "utf-8",
        timeout: 10_000,
        env: { ...process.env, OPENROUTER_API_KEY: "dummy" },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );
    assert.equal(typeof output, "string");
  });
});
