import chalk from "chalk";
import type { TestResult } from "./types.js";

export function printResults(moduleName: string, lang: string, results: TestResult[], verbose = false): void {
  const header = `${moduleName} (${lang})`;
  console.log(`\n${chalk.bold.underline(header)}\n`);

  const nameWidth = Math.max(30, ...results.map((r) => r.name.length + 2));

  const divider = "─".repeat(nameWidth + 22);
  console.log(`┌${divider}┐`);
  console.log(
    `│ ${chalk.bold("Test".padEnd(nameWidth))}${chalk.bold("Status".padEnd(10))}${chalk.bold("Time".padStart(8))} │`
  );
  console.log(`├${divider}┤`);

  for (const result of results) {
    const status = result.passed
      ? chalk.green("✅ PASS")
      : chalk.red("❌ FAIL");
    const time = `${result.duration.toFixed(1)}s`;
    console.log(`│ ${result.name.padEnd(nameWidth)}${status.padEnd(19)}${time.padStart(8)} │`);
  }

  console.log(`└${divider}┘`);

  if (verbose) {
    for (const result of results) {
      console.log(`\n${chalk.bold.cyan(`── ${result.name} ──`)}`);
      for (const step of result.steps) {
        console.log(chalk.dim(`\n  ▸ stdin:`));
        printIndented(step.input, "    ");

        if (step.stderr) {
          console.log(chalk.dim("  ▸ stderr:"));
          for (const line of step.stderr.split("\n")) {
            if (line.trim()) console.log(chalk.dim(`    ${line}`));
          }
        }

        if (step.stderr) {
          const toolExecs = parseToolExecutions(step.stderr);
          if (toolExecs.length > 0) {
            console.log(chalk.dim("  ▸ tools:"));
            for (const exec of toolExecs) {
              console.log(chalk.yellow(`    ${exec.call}`));
              printIndented(exec.output, "      ");
            }
          }
        }

        if (step.response) {
          console.log(chalk.dim("  ▸ stdout:"));
          console.log(chalk.dim("    content:"));
          printIndented(step.response.content, "      ");
          console.log(chalk.dim("    tool_calls: ") + `[${step.response.tool_calls.join(", ")}]`);
        }

        if (step.error) {
          console.log(chalk.red(`  ▸ error:  ${step.error}`));
        }
      }
    }
  }

  for (const result of results) {
    if (result.passed) continue;

    console.log(`\n${chalk.red.bold(`FAILURE: ${result.name}`)}`);

    if (result.error) {
      console.log(`  ${chalk.red(result.error)}`);
      continue;
    }

    for (const step of result.steps) {
      const failedAssertions = step.assertions.filter((a) => !a.passed);
      if (failedAssertions.length === 0 && !step.error) continue;

      console.log(`  Step ${step.stepIndex + 1}: "${step.input}"`);

      if (step.error) {
        console.log(`    ${chalk.red("✗")} ${step.error}`);
      }

      for (const a of step.assertions) {
        const icon = a.passed ? chalk.green("✓") : chalk.red("✗");
        console.log(`    ${icon} ${a.assertion.type} — ${a.message}`);
      }
    }
  }

  const passed = results.filter((r) => r.passed).length;
  const total = results.length;
  const summary =
    passed === total
      ? chalk.green.bold(`\n${passed}/${total} passed`)
      : chalk.red.bold(`\n${passed}/${total} passed, ${total - passed} failed`);
  console.log(summary);
}

type ToolExecution = { call: string; output: string };

function parseToolExecutions(stderr: string): ToolExecution[] {
  const executions: ToolExecution[] = [];
  const lines = stderr.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const callMatch = lines[i].match(/→ Executing (.+)/);
    if (callMatch) {
      const call = callMatch[1];
      const outputMatch = lines[i + 1]?.match(/← (.*)/);
      executions.push({ call, output: outputMatch ? outputMatch[1] : "(no output)" });
    }
  }
  return executions;
}

function printIndented(text: string, indent: string): void {
  for (const line of text.split("\n")) {
    console.log(`${indent}${line}`);
  }
}

export function printAgentSummary(results: TestResult[]): string {
  const lines: string[] = [];

  for (const result of results) {
    if (result.passed) {
      lines.push(`PASS: ${result.name} (${result.duration.toFixed(1)}s)`);
    } else {
      const failures: string[] = [];

      if (result.error) {
        failures.push(result.error);
      }

      for (const step of result.steps) {
        for (const a of step.assertions) {
          if (!a.passed) {
            failures.push(`Step ${step.stepIndex + 1}: ${a.assertion.type} — ${a.message}`);
          }
        }
        if (step.error) {
          failures.push(`Step ${step.stepIndex + 1}: ${step.error}`);
        }
      }

      lines.push(
        `FAIL: ${result.name} (${result.duration.toFixed(1)}s) — ${failures.join("; ")}`
      );
    }
  }

  const passed = results.filter((r) => r.passed).length;
  lines.push(`SUMMARY: ${passed}/${results.length} passed`);

  return lines.join("\n");
}
