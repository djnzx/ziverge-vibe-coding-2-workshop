import * as fs from "fs";
import * as path from "path";
import { execSync } from "child_process";
import type { Assertion, AgentResponse, AssertionResult } from "./types.js";

export function checkAssertion(
  assertion: Assertion,
  response: AgentResponse,
  workDir: string
): AssertionResult {
  switch (assertion.type) {
    case "response_contains":
      return strResult(
        assertion,
        response.content.includes(assertion.value),
        `expected content to contain "${assertion.value}"`
      );

    case "response_not_empty":
      return strResult(
        assertion,
        response.content.trim().length > 0,
        "expected non-empty response"
      );

    case "tool_used":
      return strResult(
        assertion,
        response.tool_calls.includes(assertion.tool),
        `expected tool_calls to include "${assertion.tool}", got [${response.tool_calls.join(", ")}]`
      );

    case "tool_not_used":
      return strResult(
        assertion,
        !response.tool_calls.includes(assertion.tool),
        `expected tool_calls to NOT include "${assertion.tool}"`
      );

    case "tool_count":
      return strResult(
        assertion,
        response.tool_calls.length === assertion.count,
        `expected ${assertion.count} tool calls, got ${response.tool_calls.length}`
      );

    case "file_exists": {
      const fullPath = path.resolve(workDir, assertion.path);
      return strResult(assertion, fs.existsSync(fullPath), `file not found: ${assertion.path}`);
    }

    case "file_not_exists": {
      const fullPath = path.resolve(workDir, assertion.path);
      return strResult(
        assertion,
        !fs.existsSync(fullPath),
        `file should not exist: ${assertion.path}`
      );
    }

    case "file_content": {
      const fullPath = path.resolve(workDir, assertion.path);
      if (!fs.existsSync(fullPath)) {
        return { assertion, passed: false, message: `file not found: ${assertion.path}` };
      }
      const content = fs.readFileSync(fullPath, "utf-8");

      if (assertion.equals !== undefined) {
        return strResult(
          assertion,
          content === assertion.equals,
          `file content mismatch: expected "${assertion.equals}", got "${content.slice(0, 100)}"`
        );
      }
      if (assertion.contains !== undefined) {
        return strResult(
          assertion,
          content.includes(assertion.contains),
          `file does not contain "${assertion.contains}"`
        );
      }
      if (assertion.matches !== undefined) {
        const regex = new RegExp(assertion.matches);
        return strResult(
          assertion,
          regex.test(content),
          `file content does not match /${assertion.matches}/`
        );
      }
      return { assertion, passed: true, message: "file exists (no content check specified)" };
    }

    case "shell_check": {
      try {
        execSync(assertion.command, { cwd: workDir, stdio: "pipe", timeout: 10_000 });
        return { assertion, passed: true, message: `shell check passed: ${assertion.command}` };
      } catch {
        return {
          assertion,
          passed: false,
          message: `shell check failed: ${assertion.command}`,
        };
      }
    }

    case "shell_output": {
      try {
        const stdout = execSync(assertion.command, {
          cwd: workDir,
          encoding: "utf-8",
          stdio: ["pipe", "pipe", "pipe"],
          timeout: 10_000,
        });

        if (assertion.equals !== undefined) {
          return strResult(assertion, stdout.trim() === assertion.equals,
            `shell output mismatch: expected "${assertion.equals}", got "${stdout.trim().slice(0, 100)}"`);
        }
        if (assertion.contains !== undefined) {
          return strResult(assertion, stdout.includes(assertion.contains),
            `shell output does not contain "${assertion.contains}"`);
        }
        if (assertion.matches !== undefined) {
          return strResult(assertion, new RegExp(assertion.matches).test(stdout),
            `shell output does not match /${assertion.matches}/`);
        }
        return { assertion, passed: true, message: "shell ran (no output check specified)" };
      } catch (e) {
        const err = e as { stderr?: string; message?: string };
        return {
          assertion,
          passed: false,
          message: `shell command failed: ${err.stderr ?? err.message}`,
        };
      }
    }

    default:
      return {
        assertion,
        passed: false,
        message: `unknown assertion type: ${(assertion as Assertion).type}`,
      };
  }
}

function strResult(assertion: Assertion, passed: boolean, failMessage: string): AssertionResult {
  return { assertion, passed, message: passed ? "ok" : failMessage };
}
