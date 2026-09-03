import { spawn, execSync, type ChildProcess } from "child_process";
import * as fs from "fs";
import type {
  TestDefinition,
  AgentResponse,
  StepResult,
  TestResult,
  LangConfig,
} from "./types.js";
import { checkAssertion } from "./assertions.js";

function runShellCommands(commands: { shell: string }[], cwd: string): void {
  for (const cmd of commands) {
    execSync(cmd.shell, { cwd, stdio: "pipe", timeout: 15_000 });
  }
}

export async function runTest(
  test: TestDefinition,
  langConfig: LangConfig,
  env: Record<string, string>,
  verbose = false
): Promise<TestResult> {
  const start = Date.now();
  const agentCwd = langConfig.cwd;
  const timeoutMs = (test.timeout ?? 120) * 1000;
  let agentProcess: ChildProcess | undefined;

  try {
    if (test.setup) {
      runShellCommands(test.setup, agentCwd);
    }

    agentProcess = spawn(langConfig.command, langConfig.args, {
      cwd: agentCwd,
      env: { ...process.env, ...env, HOME: process.env.HOME ?? "" },
      stdio: ["pipe", "pipe", "pipe"],
    });

    if (!agentProcess.stdin || !agentProcess.stdout || !agentProcess.stderr) {
      throw new Error("Failed to open agent stdio pipes");
    }

    const stdin = agentProcess.stdin;
    const stdout = agentProcess.stdout;
    const stderr = agentProcess.stderr;

    let stderrBuffer = "";
    stderr.on("data", (chunk: Buffer) => {
      stderrBuffer += chunk.toString();
    });

    const steps: StepResult[] = [];
    let aborted = false;

    for (const [i, step] of test.steps.entries()) {
      if (aborted) break;

      stderrBuffer = "";

      const stepResult: StepResult = {
        stepIndex: i,
        input: step.input,
        response: null,
        assertions: [],
      };

      try {
        const response = await sendAndReceive(
          stdin,
          stdout,
          agentProcess,
          step.input,
          timeoutMs
        );
        stepResult.response = response;
        stepResult.stderr = stderrBuffer;

        for (const assertion of step.assertions) {
          stepResult.assertions.push(checkAssertion(assertion, response, agentCwd));
        }
      } catch (e) {
        stepResult.error = `${e}`;
        stepResult.stderr = stderrBuffer;
        aborted = true;
      }

      steps.push(stepResult);
    }

    stdin.end();
    agentProcess.kill("SIGTERM");

    const allPassed =
      !aborted && steps.every((s) => !s.error && s.assertions.every((a) => a.passed));

    return {
      name: test.name,
      passed: allPassed,
      duration: (Date.now() - start) / 1000,
      steps,
    };
  } catch (e) {
    return {
      name: test.name,
      passed: false,
      duration: (Date.now() - start) / 1000,
      steps: [],
      error: `${e}`,
    };
  } finally {
    if (agentProcess?.exitCode === null && agentProcess.signalCode === null) {
      agentProcess.kill("SIGTERM");
    }
    if (test.cleanup) {
      try {
        runShellCommands(test.cleanup, agentCwd);
      } catch {
        // cleanup failure is non-fatal
      }
    }
  }
}

function sendAndReceive(
  stdinStream: NonNullable<ReturnType<typeof spawn>["stdin"]>,
  stdoutStream: NonNullable<ReturnType<typeof spawn>["stdout"]>,
  proc: ReturnType<typeof spawn>,
  input: string,
  timeoutMs: number
): Promise<AgentResponse> {
  return new Promise((resolve, reject) => {
    if (proc.exitCode !== null || proc.signalCode !== null) {
      reject(new Error(`Agent exited with code ${proc.exitCode} before responding`));
      return;
    }
    const timer = setTimeout(() => {
      reject(new Error(`Agent timed out after ${timeoutMs / 1000}s`));
    }, timeoutMs);

    let buffer = "";

    const onData = (chunk: Buffer) => {
      buffer += chunk.toString();

      let newlineIndex: number;
      while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, newlineIndex).trim();
        buffer = buffer.slice(newlineIndex + 1);

        if (!line || !line.startsWith("{")) continue;

        try {
          const parsed = JSON.parse(line);
          if (parsed.content !== undefined || parsed.tool_calls !== undefined) {
            clearTimeout(timer);
            stdoutStream.removeListener("data", onData);
            proc.removeListener("exit", onExit);
            resolve({
              content: parsed.content ?? "",
              tool_calls: Array.isArray(parsed.tool_calls) ? parsed.tool_calls : [],
            });
            return;
          }
        } catch {
          continue;
        }
      }
    };

    const onExit = (code: number | null) => {
      clearTimeout(timer);
      stdoutStream.removeListener("data", onData);
      reject(new Error(`Agent exited with code ${code} before responding`));
    };

    stdoutStream.on("data", onData);
    proc.once("exit", onExit);

    const message = JSON.stringify({ content: input }) + "\n";
    stdinStream.write(message);
  });
}
