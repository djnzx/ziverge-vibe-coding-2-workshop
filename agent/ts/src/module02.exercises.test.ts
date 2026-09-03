import { describe, it } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";
import { defaultConfig } from "./config.js";
import { createConversation, type ChatProvider, type Conversation } from "./conversation.js";
import {
  applyCompaction,
  discoverCommands,
  discoverSkills,
  executeCommand,
  extractMemories,
  formatConversationForCompaction,
  loadInstructions,
  loadMemories,
  loadPastSessionSummaries,
  loadSession,
  loadSkillContent,
  measureContext,
  saveSession,
  shouldAutoCompact,
} from "./exercises.js";
import type { CommandPrompt, ContextUsage, Tool } from "./tools.js";

async function withTempDir(run: (tmpDir: string) => void | Promise<void>): Promise<void> {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agent-module02-test-ts-"));
  try {
    await run(tmpDir);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

function writeTextFile(filePath: string, content: string): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, "utf-8");
}

function conversationWithTurns(system: string, turns: Conversation["turns"]): Conversation {
  return { system, turns: [...turns] };
}

function promptTextForTool(tool: Tool): string {
  return [
    `### ${tool.name}`,
    tool.description,
    "Parameters:",
    "```json",
    JSON.stringify(zodToJsonSchema(tool.schema, { target: "openApi3" }), null, 2),
    "```",
  ].join("\n");
}

function sessionJson(
  timestamp: string,
  system: string,
  turns: Conversation["turns"],
): string {
  return JSON.stringify({
    timestamp,
    workDir: "/tmp/project",
    model: "anthropic/claude-sonnet-4",
    systemPrompt: system,
    turns,
  }, null, 2);
}

describe("Exercise 1: Load persistent instructions", () => {
  it("returns null when explicit instructions file is missing", async () => {
    await withTempDir((tmpDir) => {
      const missingPath = path.join(tmpDir, "missing.md");
      assert.equal(loadInstructions(tmpDir, missingPath), null);
    });
  });

  it("loads content from explicit instructionsPath", async () => {
    await withTempDir((tmpDir) => {
      const instructionsPath = path.join(tmpDir, "custom-instructions.md");
      writeTextFile(instructionsPath, "Always run tests before finishing.");

      assert.equal(
        loadInstructions(tmpDir, instructionsPath),
        "Always run tests before finishing.",
      );
    });
  });

  it("resolves a relative instructionsPath from workDir", async () => {
    await withTempDir((tmpDir) => {
      const workDir = path.join(tmpDir, "project");
      const instructionsPath = path.join(workDir, "config", "instructions.md");
      writeTextFile(instructionsPath, "Use the local project conventions.");

      assert.equal(
        loadInstructions(workDir, path.join("config", "instructions.md")),
        "Use the local project conventions.",
      );
    });
  });

  it("walks up directory tree and concatenates AGENTS.md files root-first", async () => {
    await withTempDir((tmpDir) => {
      writeTextFile(path.join(tmpDir, "AGENTS.md"), "Root instructions");
      writeTextFile(path.join(tmpDir, "project", "AGENTS.md"), "Project instructions");
      fs.mkdirSync(path.join(tmpDir, "project", "src", "nested"), { recursive: true });

      const result = loadInstructions(path.join(tmpDir, "project", "src", "nested"), null);
      assert.equal(result, "Root instructions\n\nProject instructions");
    });
  });

  it("returns null when no AGENTS.md files exist in the tree", async () => {
    await withTempDir((tmpDir) => {
      fs.mkdirSync(path.join(tmpDir, "project", "src"), { recursive: true });
      assert.equal(loadInstructions(path.join(tmpDir, "project", "src"), null), null);
    });
  });
});

describe("Exercise 2: Discover and load skills", () => {
  it("returns empty when skillsDir is null", () => {
    assert.deepEqual(discoverSkills(null), []);
  });

  it("returns empty when skillsDir does not exist", () => {
    assert.deepEqual(discoverSkills("/nonexistent/skills-dir"), []);
  });

  it("discovers skills and parses name and description", async () => {
    await withTempDir((tmpDir) => {
      const skillsDir = path.join(tmpDir, "skills");
      writeTextFile(
        path.join(skillsDir, "architecture", "SKILL.md"),
        [
          "---",
          'name: "Architecture Review"',
          'description: "Review tradeoffs before coding."',
          "---",
          "Check boundaries and interfaces.",
        ].join("\n"),
      );
      writeTextFile(
        path.join(skillsDir, "testing", "SKILL.md"),
        [
          "---",
          "name: Testing",
          'description: "Write focused unit tests."',
          "---",
          "Prefer deterministic assertions.",
        ].join("\n"),
      );

      const result = discoverSkills(skillsDir);
      assert.deepEqual(result, [
        {
          name: "Architecture Review",
          description: "Review tradeoffs before coding.",
          path: path.resolve(skillsDir, "architecture", "SKILL.md"),
        },
        {
          name: "Testing",
          description: "Write focused unit tests.",
          path: path.resolve(skillsDir, "testing", "SKILL.md"),
        },
      ]);
    });
  });

  it("ignores subdirectories without SKILL.md", async () => {
    await withTempDir((tmpDir) => {
      const skillsDir = path.join(tmpDir, "skills");
      writeTextFile(
        path.join(skillsDir, "valid", "SKILL.md"),
        [
          "---",
          "name: Valid",
          'description: "Loaded skill."',
          "---",
          "Body",
        ].join("\n"),
      );
      fs.mkdirSync(path.join(skillsDir, "missing-file"), { recursive: true });

      const result = discoverSkills(skillsDir);
      assert.equal(result.length, 1);
      assert.equal(result[0].name, "Valid");
    });
  });

  it("loadSkillContent returns the full file content", async () => {
    await withTempDir((tmpDir) => {
      const skillPath = path.join(tmpDir, "skills", "refactor", "SKILL.md");
      const content = [
        "---",
        "name: Refactor",
        'description: "Extract shared helpers."',
        "---",
        "Look for repeated logic.",
      ].join("\n");
      writeTextFile(skillPath, content);

      assert.equal(loadSkillContent(skillPath), content);
    });
  });

  it("loadSkillContent returns empty string for a missing file", async () => {
    assert.equal(loadSkillContent("/nonexistent/path/SKILL.md"), "");
  });
});

describe("Exercise 3: Discover and execute command prompts", () => {
  it("returns empty when commandsDir is null", () => {
    assert.deepEqual(discoverCommands(null), []);
  });

  it("discovers commands from markdown files", async () => {
    await withTempDir((tmpDir) => {
      const commandsDir = path.join(tmpDir, "commands");
      writeTextFile(
        path.join(commandsDir, "code-review.md"),
        [
          "---",
          'description: "Review a diff for correctness."',
          'argument-hint: "<path or diff>"',
          "---",
          "Review $ARGUMENTS carefully.",
        ].join("\n"),
      );
      writeTextFile(path.join(commandsDir, "notes.txt"), "ignore me");

      const result = discoverCommands(commandsDir);
      assert.deepEqual(result, [
        {
          name: "code-review",
          description: "Review a diff for correctness.",
          argumentHint: "<path or diff>",
          path: path.resolve(commandsDir, "code-review.md"),
        },
      ]);
    });
  });

  it("executeCommand substitutes every $ARGUMENTS occurrence", async () => {
    await withTempDir((tmpDir) => {
      const command: CommandPrompt = {
        name: "summarize",
        description: "Summarize input",
        argumentHint: null,
        path: path.join(tmpDir, "summarize.md"),
      };
      writeTextFile(
        command.path,
        [
          "---",
          'description: "Summarize some input."',
          "---",
          "Summarize $ARGUMENTS.",
          "Then list actions for $ARGUMENTS.",
        ].join("\n"),
      );

      assert.equal(
        executeCommand(command, "the release plan"),
        "Summarize the release plan.\nThen list actions for the release plan.",
      );
    });
  });

  it("executeCommand strips YAML frontmatter from the returned prompt", async () => {
    await withTempDir((tmpDir) => {
      const command: CommandPrompt = {
        name: "triage",
        description: "Triage work",
        argumentHint: null,
        path: path.join(tmpDir, "triage.md"),
      };
      writeTextFile(
        command.path,
        [
          "---",
          'description: "Triage an issue."',
          "---",
          "Triage $ARGUMENTS now.",
        ].join("\n"),
      );

      const result = executeCommand(command, "bug-123");
      assert.equal(result, "Triage bug-123 now.");
      assert.ok(!result.includes("description:"));
      assert.ok(!result.includes("---"));
    });
  });

  it("executeCommand strips one blank separator line after frontmatter", async () => {
    await withTempDir((tmpDir) => {
      const command: CommandPrompt = {
        name: "triage",
        description: "Triage work",
        argumentHint: null,
        path: path.join(tmpDir, "triage.md"),
      };
      writeTextFile(
        command.path,
        [
          "---",
          'description: "Triage an issue."',
          "---",
          "",
          "Triage $ARGUMENTS now.",
          "Then report back.",
        ].join("\n"),
      );

      const result = executeCommand(command, "bug-123");
      assert.equal(result, "Triage bug-123 now.\nThen report back.");
    });
  });

  it("normalizes empty argument-hint to null", async () => {
    await withTempDir((tmpDir) => {
      const commandsDir = path.join(tmpDir, "commands");
      writeTextFile(
        path.join(commandsDir, "simple.md"),
        ["---", 'description: "Simple command"', 'argument-hint: ""', "---", "Do it."].join("\n"),
      );
      const result = discoverCommands(commandsDir);
      assert.equal(result[0].argumentHint, null);
    });
  });

  it("ignores non-markdown files in commands dir", async () => {
    await withTempDir((tmpDir) => {
      const commandsDir = path.join(tmpDir, "commands");
      writeTextFile(path.join(commandsDir, "valid.md"), "---\ndescription: Valid\n---\nDo $ARGUMENTS.");
      writeTextFile(path.join(commandsDir, "ignore.txt"), "not a command");
      writeTextFile(path.join(commandsDir, "ignore.yaml"), "also: not");
      const result = discoverCommands(commandsDir);
      assert.equal(result.length, 1);
      assert.equal(result[0].name, "valid");
    });
  });

  it("executeCommand returns empty string for missing file", () => {
    const cmd: CommandPrompt = { name: "ghost", description: "", argumentHint: null, path: "/nonexistent/ghost.md" };
    assert.equal(executeCommand(cmd, "args"), "");
  });
});

describe("Exercise 4: Measure context usage", () => {
  const sampleTool: Tool = {
    name: "read_file",
    description: "Read a file from disk",
    schema: z.object({ path: z.string() }),
    execute: () => ({ ok: true, output: "" }),
  };

  it("returns correct character counts for known inputs", () => {
    const conversation = conversationWithTurns("ignored", [
      { role: "user", content: "hello" },
      { role: "assistant", content: "world" },
    ]);

    const usage = measureContext("system", conversation, [], 20);
    assert.deepEqual(usage, {
      system: 6,
      conversation: 10,
      tools: 0,
      total: 16,
      limit: 20,
      percentage: 0.8,
    });
  });

  it("computes percentage as total divided by limit", () => {
    const usage = measureContext("abcd", createConversation("ignored"), [], 8);
    assert.equal(usage.percentage, 0.5);
  });

  it("returns null percentage when limit is null", () => {
    const usage = measureContext("abcd", createConversation("ignored"), [], null);
    assert.equal(usage.percentage, null);
  });

  it("returns null percentage when limit is zero", () => {
    const usage = measureContext("abcd", createConversation("ignored"), [], 0);
    assert.equal(usage.percentage, null);
  });

  it("counts system and tool overhead even for an empty conversation", () => {
    const systemPrompt = "base";
    const expectedTools = promptTextForTool(sampleTool).length;

    const usage = measureContext(systemPrompt, createConversation("ignored"), [sampleTool], null);
    assert.equal(usage.system, systemPrompt.length);
    assert.equal(usage.conversation, 0);
    assert.equal(usage.tools, expectedTools);
    assert.equal(usage.total, systemPrompt.length + expectedTools);
  });
});

describe("Exercise 5: Compact conversation history", () => {
  it("formats a transcript as role-prefixed lines with blank separators", () => {
    const conversation = conversationWithTurns("system prompt", [
      { role: "user", content: "Create a file" },
      { role: "assistant", content: "Done" },
    ]);

    assert.equal(
      formatConversationForCompaction(conversation),
      "user: Create a file\n\nassistant: Done",
    );
  });

  it("applyCompaction replaces prior turns with a summary turn pair", () => {
    const conversation = conversationWithTurns("system prompt", [
      { role: "user", content: "step 1" },
      { role: "assistant", content: "done 1" },
      { role: "user", content: "step 2" },
      { role: "assistant", content: "done 2" },
    ]);

    applyCompaction(conversation, "Summary of earlier work.");

    assert.equal(conversation.turns.length, 2);
    assert.equal(conversation.turns[0].role, "user");
    assert.match(conversation.turns[0].content, /Summary of earlier work/);
    assert.equal(conversation.turns[1].role, "assistant");
  });

  it("applyCompaction preserves the system prompt", () => {
    const conversation = conversationWithTurns("keep me", [
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi" },
    ]);

    applyCompaction(conversation, "Short summary");
    assert.equal(conversation.system, "keep me");
  });

  it("post-compaction conversation alternates user then assistant", () => {
    const conversation = conversationWithTurns("system", [
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi" },
    ]);

    applyCompaction(conversation, "summary");
    assert.deepEqual(
      conversation.turns.map((turn) => turn.role),
      ["user", "assistant"],
    );
  });
});

describe("Exercise 6: Auto-compact on budget", () => {
  it("returns false when limit is null", () => {
    const usage: ContextUsage = {
      system: 1,
      conversation: 1,
      tools: 1,
      total: 3,
      limit: null,
      percentage: null,
    };
    assert.equal(shouldAutoCompact(usage), false);
  });

  it("returns true when total exceeds the limit", () => {
    const usage: ContextUsage = {
      system: 10,
      conversation: 20,
      tools: 5,
      total: 35,
      limit: 34,
      percentage: 35 / 34,
    };
    assert.equal(shouldAutoCompact(usage), true);
  });

  it("returns false when total is within the limit", () => {
    const usage: ContextUsage = {
      system: 10,
      conversation: 20,
      tools: 5,
      total: 35,
      limit: 35,
      percentage: 1,
    };
    assert.equal(shouldAutoCompact(usage), false);
  });
});

describe("Exercise 7: Save and resume conversation", () => {
  it("saveSession creates a JSON file with metadata", async () => {
    await withTempDir((tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      const conversation = conversationWithTurns("system prompt", [
        { role: "user", content: "hello" },
        { role: "assistant", content: "hi" },
      ]);

      saveSession(historyDir, "session-1", conversation);

      const filePath = path.join(historyDir, "session-1.json");
      assert.equal(fs.existsSync(filePath), true);

      const saved = JSON.parse(fs.readFileSync(filePath, "utf-8")) as Record<string, unknown>;
      assert.equal(saved.systemPrompt, "system prompt");
      assert.equal(Array.isArray(saved.turns), true);
      assert.equal(typeof saved.timestamp, "string");
      assert.equal(saved.workDir, process.cwd());
      assert.equal(saved.model, defaultConfig().model);
    });
  });

  it("loadSession restores saved conversation turns", async () => {
    await withTempDir((tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      const conversation = conversationWithTurns("system prompt", [
        { role: "user", content: "draft a plan" },
        { role: "assistant", content: "here is the plan" },
      ]);
      saveSession(historyDir, "session-2", conversation);

      const loaded = loadSession(historyDir, "session-2");
      assert.deepEqual(loaded?.turns, conversation.turns);
    });
  });

  it("round-trips a conversation through saveSession and loadSession", async () => {
    await withTempDir((tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      const conversation = conversationWithTurns("system prompt", [
        { role: "user", content: "one" },
        { role: "assistant", content: "two" },
      ]);

      saveSession(historyDir, "session-3", conversation);

      assert.deepEqual(loadSession(historyDir, "session-3"), conversation);
    });
  });

  it("loadSession returns null for a missing file", async () => {
    await withTempDir((tmpDir) => {
      assert.equal(loadSession(path.join(tmpDir, "history"), "missing"), null);
    });
  });
});

describe("Exercise 8: Session-aware compaction", () => {
  // Short inputs fit within sessionSummaryMaxChars and must NOT trigger an LLM
  // call. Tests pass this provider to assert the no-LLM-for-short-inputs path.
  const unusedProvider: ChatProvider = async () => {
    throw new Error("provider should not be called for short inputs");
  };

  // Returns a fixed summary regardless of input. Used for long-input cases to
  // verify the LLM summarisation path runs and the result is plumbed through.
  const fixedSummaryProvider = (summary: string): ChatProvider => async () => summary;

  it("returns an empty string when no prior sessions exist", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      fs.mkdirSync(historyDir, { recursive: true });
      assert.equal(await loadPastSessionSummaries(historyDir, "current", unusedProvider), "");
    });
  });

  it("formats prior sessions with timestamp and first user message", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      writeTextFile(
        path.join(historyDir, "session-a.json"),
        sessionJson("2026-01-01T10:00:00.000Z", "system", [
          { role: "user", content: "Build login page" },
          { role: "assistant", content: "Drafted plan" },
        ]),
      );
      writeTextFile(
        path.join(historyDir, "session-b.json"),
        sessionJson("2026-01-02T11:30:00.000Z", "system", [
          { role: "user", content: "Add audit logging" },
          { role: "assistant", content: "Added approach" },
        ]),
      );

      const result = await loadPastSessionSummaries(historyDir, "current", unusedProvider);
      assert.match(result, /\[2026-01-01T10:00:00.000Z\] topic: Build login page/);
      assert.match(result, /\[2026-01-02T11:30:00.000Z\] topic: Add audit logging/);
    });
  });

  it("excludes the current session by ID", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      writeTextFile(
        path.join(historyDir, "current.json"),
        sessionJson("2026-01-03T09:00:00.000Z", "system", [
          { role: "user", content: "Do not include me" },
        ]),
      );
      writeTextFile(
        path.join(historyDir, "previous.json"),
        sessionJson("2026-01-02T09:00:00.000Z", "system", [
          { role: "user", content: "Include me" },
        ]),
      );

      const result = await loadPastSessionSummaries(historyDir, "current", unusedProvider);
      assert.match(result, /Include me/);
      assert.doesNotMatch(result, /Do not include me/);
    });
  });

  it("LLM-summarises long topics; provider response is plumbed through verbatim when it fits", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      const longTopic = "A".repeat(90); // exceeds the 80-char default cap
      const provider = fixedSummaryProvider("Investigating ninety-A topic preview");
      writeTextFile(
        path.join(historyDir, "session-a.json"),
        sessionJson("2026-01-04T09:00:00.000Z", "system", [
          { role: "user", content: longTopic },
        ]),
      );

      const result = await loadPastSessionSummaries(historyDir, "current", provider);
      assert.equal(result, "[2026-01-04T09:00:00.000Z] topic: Investigating ninety-A topic preview");
    });
  });

  it("truncateSummaryText still caps an LLM response that overshoots", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      const longTopic = "A".repeat(90);
      const provider = fixedSummaryProvider("X".repeat(120)); // model overshoots the request
      writeTextFile(
        path.join(historyDir, "session-a.json"),
        sessionJson("2026-01-04T09:00:00.000Z", "system", [
          { role: "user", content: longTopic },
        ]),
      );

      const result = await loadPastSessionSummaries(historyDir, "current", provider);
      const expectedTopic = `${"X".repeat(77)}...`;
      assert.equal(expectedTopic.length, 80);
      assert.equal(result, `[2026-01-04T09:00:00.000Z] topic: ${expectedTopic}`);
    });
  });

  it("skips sessions that have no user messages", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      writeTextFile(
        path.join(historyDir, "assistant-only.json"),
        sessionJson("2026-01-01T09:00:00.000Z", "system", [
          { role: "assistant", content: "No user turn here" },
        ]),
      );
      writeTextFile(
        path.join(historyDir, "session-b.json"),
        sessionJson("2026-01-02T09:00:00.000Z", "system", [
          { role: "user", content: "Include me" },
        ]),
      );

      const result = await loadPastSessionSummaries(historyDir, "current", unusedProvider);
      assert.match(result, /\[2026-01-02T09:00:00.000Z\] topic: Include me/);
      assert.doesNotMatch(result, /\[2026-01-01T09:00:00.000Z\]/);
    });
  });

  it("returns an empty string when the history directory has no JSON files", async () => {
    await withTempDir(async (tmpDir) => {
      const historyDir = path.join(tmpDir, "history");
      writeTextFile(path.join(historyDir, "notes.txt"), "not a session");
      assert.equal(await loadPastSessionSummaries(historyDir, "current", unusedProvider), "");
    });
  });
});

describe("Exercise 9: Extract memories", () => {
  it("appends extracted facts to the memory file", async () => {
    await withTempDir(async (tmpDir) => {
      const provider: ChatProvider = async () => "Prefers TypeScript\nRuns tests before commit";

      await extractMemories(provider, "Help with ts", "Done", tmpDir);

      const memoryPath = path.join(tmpDir, ".agent", "memories.md");
      assert.equal(
        fs.readFileSync(memoryPath, "utf-8"),
        "- Prefers TypeScript\n- Runs tests before commit\n",
      );
    });
  });

  it("sends the expected extraction prompt to the provider", async () => {
    await withTempDir(async (tmpDir) => {
      let capturedPrompt = "";
      const provider: ChatProvider = async (messages) => {
        capturedPrompt = messages[0]?.content ?? "";
        return "Prefers TypeScript";
      };

      await extractMemories(provider, "Please use TypeScript.", "Will do.", tmpDir);

      assert.equal(
        capturedPrompt,
        `Extract durable facts worth remembering from this conversation turn.
Focus on: user preferences, project conventions, technical decisions, recurring patterns.

User message:
Please use TypeScript.

Agent response:
Will do.

Return one fact per line. If nothing is worth remembering, return exactly NONE.`,
      );
      assert.match(capturedPrompt, /Please use TypeScript\./);
      assert.match(capturedPrompt, /Will do\./);
      assert.match(capturedPrompt, /NONE/);
    });
  });

  it("does nothing when the provider returns NONE case-insensitively", async () => {
    await withTempDir(async (tmpDir) => {
      const provider: ChatProvider = async () => "  none\n";

      await extractMemories(provider, "Hello", "Hi", tmpDir);

      assert.equal(fs.existsSync(path.join(tmpDir, ".agent", "memories.md")), false);
    });
  });

  it("strips bullets and skips NONE lines in extracted facts", async () => {
    await withTempDir(async (tmpDir) => {
      const provider: ChatProvider = async () => [
        "- Prefers TypeScript",
        "* NONE",
        "* Runs tests before commit",
      ].join("\n");

      await extractMemories(provider, "Remember this", "Done", tmpDir);

      assert.equal(
        fs.readFileSync(path.join(tmpDir, ".agent", "memories.md"), "utf-8"),
        "- Prefers TypeScript\n- Runs tests before commit\n",
      );
    });
  });

  it("creates the memory file when it does not already exist", async () => {
    await withTempDir(async (tmpDir) => {
      const provider: ChatProvider = async () => "Repository uses pnpm";

      await extractMemories(provider, "Set up project", "Done", tmpDir);

      const memoryPath = path.join(tmpDir, ".agent", "memories.md");
      assert.equal(fs.existsSync(memoryPath), true);
    });
  });

  it("appends to an existing memory file instead of overwriting it", async () => {
    await withTempDir(async (tmpDir) => {
      const memoryPath = path.join(tmpDir, ".agent", "memories.md");
      writeTextFile(memoryPath, "- Existing fact\n");
      const provider: ChatProvider = async () => "Uses strict null checks";

      await extractMemories(provider, "Remember this", "Got it", tmpDir);

      assert.equal(
        fs.readFileSync(memoryPath, "utf-8"),
        "- Existing fact\n- Uses strict null checks\n",
      );
    });
  });

  it("ignores bare bullet lines in provider memory output", async () => {
    await withTempDir(async (tmpDir) => {
      const provider: ChatProvider = async () => [
        "- ",
        "*  ",
        "- Keeps commits small",
      ].join("\n");

      await extractMemories(provider, "Remember this", "Done", tmpDir);

      assert.equal(
        fs.readFileSync(path.join(tmpDir, ".agent", "memories.md"), "utf-8"),
        "- Keeps commits small\n",
      );
    });
  });
});

describe("Exercise 10: Inject memories into context", () => {
  it("returns the content of an existing memory file", async () => {
    await withTempDir((tmpDir) => {
      const memoryPath = path.join(tmpDir, ".agent", "memories.md");
      writeTextFile(memoryPath, "- Prefers concise answers\n");

      assert.equal(loadMemories(tmpDir), "- Prefers concise answers\n");
    });
  });

  it("returns null when the memory file is missing", async () => {
    await withTempDir((tmpDir) => {
      assert.equal(loadMemories(tmpDir), null);
    });
  });

  it("preserves formatting from the memory file", async () => {
    await withTempDir((tmpDir) => {
      const memoryPath = path.join(tmpDir, ".agent", "memories.md");
      const content = "- First memory\n- Second memory\n\n- Third memory\n";
      writeTextFile(memoryPath, content);

      assert.equal(loadMemories(tmpDir), content);
    });
  });
});
