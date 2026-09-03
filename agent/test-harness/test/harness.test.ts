import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkAssertion } from "../src/assertions.js";
import { runTest } from "../src/runner.js";
import type { Assertion, LangConfig, TestDefinition } from "../src/types.js";
import { loadTest } from "../src/yaml-loader.js";

function temporaryDirectory(prefix: string): string {
  return mkdtempSync(join(tmpdir(), prefix));
}

function nodeCandidate(cwd: string, source: string): LangConfig {
  return { command: process.execPath, args: ["-e", source], cwd };
}

function definition(overrides: Partial<TestDefinition> = {}): TestDefinition {
  return {
    name: "unit case",
    timeout: 1,
    steps: [{ input: "hello", assertions: [{ type: "response_not_empty" }] }],
    ...overrides,
  };
}

test("loader gives contextual diagnostics for malformed YAML and invalid roots", () => {
  const root = temporaryDirectory("agent-harness-yaml-");
  try {
    const malformed = join(root, "malformed.yaml");
    writeFileSync(malformed, "name: broken\nsteps: [\n");
    assert.throws(() => loadTest(malformed), (error: Error) =>
      error.message.includes(malformed) && error.message.includes("malformed YAML"));

    const scalar = join(root, "scalar.yaml");
    writeFileSync(scalar, "hello\n");
    assert.throws(() => loadTest(scalar), /expected a YAML mapping/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("response, tool, path, and file assertions report successes and failures", () => {
  const root = temporaryDirectory("agent-harness-assertions-");
  try {
    writeFileSync(join(root, "message.txt"), "hello world\n");
    const response = { content: "completed", tool_calls: ["read_file", "write_file"] };
    const successes: Assertion[] = [
      { type: "response_contains", value: "plete" },
      { type: "response_not_empty" },
      { type: "tool_used", tool: "write_file" },
      { type: "tool_not_used", tool: "shell" },
      { type: "tool_count", count: 2 },
      { type: "file_exists", path: "message.txt" },
      { type: "file_not_exists", path: "missing.txt" },
      { type: "file_content", path: "message.txt", equals: "hello world\n" },
      { type: "file_content", path: "message.txt", contains: "world" },
      { type: "file_content", path: "message.txt", matches: "hello\\s+world" },
    ];
    assert.ok(successes.every((item) => checkAssertion(item, response, root).passed));

    const failures: Assertion[] = [
      { type: "response_contains", value: "nope" },
      { type: "tool_used", tool: "shell" },
      { type: "file_exists", path: "missing.txt" },
      { type: "file_not_exists", path: "message.txt" },
      { type: "file_content", path: "message.txt", equals: "wrong" },
    ];
    assert.ok(failures.every((item) => !checkAssertion(item, response, root).passed));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("runner reports an agent that exits before responding", async () => {
  const root = temporaryDirectory("agent-harness-exit-");
  try {
    const result = await runTest(definition(), nodeCandidate(root, "process.exit(7)"), {});
    assert.equal(result.passed, false);
    assert.match(result.steps[0]?.error ?? "", /exited with code 7 before responding/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("runner times out, terminates the child, and still runs cleanup", async () => {
  const root = temporaryDirectory("agent-harness-timeout-");
  const marker = join(root, "cleanup.txt");
  try {
    const result = await runTest(
      definition({ timeout: 0.03, cleanup: [{ shell: `${JSON.stringify(process.execPath)} -e "require('fs').writeFileSync('cleanup.txt','done')"` }] }),
      nodeCandidate(root, "setInterval(() => {}, 1000)"),
      {},
    );
    assert.equal(result.passed, false);
    assert.match(result.steps[0]?.error ?? "", /timed out/);
    assert.equal(existsSync(marker), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("cleanup runs even when setup fails", async () => {
  const root = temporaryDirectory("agent-harness-cleanup-");
  try {
    const result = await runTest(
      definition({
        setup: [{ shell: `${JSON.stringify(process.execPath)} -e "process.exit(2)"` }],
        cleanup: [{ shell: `${JSON.stringify(process.execPath)} -e "require('fs').writeFileSync('cleaned','yes')"` }],
      }),
      nodeCandidate(root, ""),
      {},
    );
    assert.equal(result.passed, false);
    assert.equal(existsSync(join(root, "cleaned")), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
