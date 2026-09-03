import { describe, it, before, after } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { tools } from "./agent.js";

let tmpDir: string;

before(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "agent-scaffolding-test-ts-"));
});

after(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

describe("scaffolding", () => {
  const findTool = (name: string) => tools.find((t) => t.name === name)!;

  it("write_file creates a file", async () => {
    const filePath = path.join(tmpDir, "test-write.txt");
    const result = await findTool("write_file").execute({ path: filePath, content: "hello" });
    assert.equal(result.ok, true);
    if (result.ok) {
      assert.match(result.output, /Successfully wrote/);
    }
    assert.equal(fs.readFileSync(filePath, "utf-8"), "hello");
  });

  it("write_file returns invalid arguments error for bad args", async () => {
    const result = await findTool("write_file").execute({ path: "missing-content.txt" });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /invalid arguments:/);
    }
  });

  it("web_fetch is registered between list_files and message_user", () => {
    const names = tools.map((tool) => tool.name);
    assert.ok(names.includes("web_fetch"));
    assert.equal(names.indexOf("web_fetch"), names.indexOf("list_files") + 1);
    assert.equal(names.indexOf("message_user"), names.indexOf("web_fetch") + 1);
  });

  it("web_fetch schema accepts valid url argument", () => {
    const parsed = findTool("web_fetch").schema.safeParse({ url: "https://example.com" });
    assert.equal(parsed.success, true);
  });

  it("web_fetch execute function is callable", async () => {
    const result = await Promise.resolve(findTool("web_fetch").execute({}));
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.match(result.error, /invalid arguments:/);
    }
  });

  // --- generic update_board semantics ---

  const writeBoard = (file: string) =>
    fs.writeFileSync(
      file,
      JSON.stringify({
        intent: "",
        currentPhase: "spec",
        phases: { spec: { status: "pending" }, review: { status: "pending" } },
      }) + "\n",
    );

  it("update_board changes status and records an artifact", async () => {
    const boardPath = path.join(tmpDir, "board.json");
    writeBoard(boardPath);
    const result = await Promise.resolve(
      findTool("update_board").execute({
        path: boardPath,
        phase: "review",
        status: "in_review",
        artifact: "artifacts/review.md",
      }),
    );
    assert.equal(result.ok, true);
    const board = JSON.parse(fs.readFileSync(boardPath, "utf-8"));
    assert.equal(board.currentPhase, "review");
    assert.equal(board.phases.review.status, "in_review");
    assert.equal(board.phases.review.artifact, "artifacts/review.md");
  });

  it("update_board rejects an unknown phase", async () => {
    const boardPath = path.join(tmpDir, "board-unknown.json");
    writeBoard(boardPath);
    const result = await Promise.resolve(
      findTool("update_board").execute({
        path: boardPath,
        phase: "missing",
        status: "in_progress",
      }),
    );
    assert.equal(result.ok, false);
  });

  it("update_board works without an optional artifact", async () => {
    const boardPath = path.join(tmpDir, "board-bare.json");
    writeBoard(boardPath);
    const result = await Promise.resolve(
      findTool("update_board").execute({
        path: boardPath,
        phase: "spec",
        status: "in_progress",
      }),
    );
    assert.equal(result.ok, true);
    const board = JSON.parse(fs.readFileSync(boardPath, "utf-8"));
    assert.equal(board.phases.spec.status, "in_progress");
    assert.equal(board.phases.spec.artifact, undefined);
  });

  it("update_board accepts null for an optional artifact", async () => {
    const boardPath = path.join(tmpDir, "board-null-artifact.json");
    writeBoard(boardPath);
    const result = await Promise.resolve(
      findTool("update_board").execute({
        path: boardPath,
        phase: "spec",
        status: "queued",
        artifact: null,
      }),
    );
    assert.equal(result.ok, true);
    const board = JSON.parse(fs.readFileSync(boardPath, "utf-8"));
    assert.equal(board.phases.spec.status, "queued");
    assert.equal(board.phases.spec.artifact, undefined);
  });
});
