import * as fs from "fs";
import * as path from "path";
import { parse } from "yaml";
import type { TestDefinition } from "./types.js";

export function loadTest(filePath: string): TestDefinition {
  const raw = fs.readFileSync(filePath, "utf-8");
  let parsed: unknown;
  try {
    parsed = parse(raw);
  } catch (error) {
    throw new Error(`${filePath}: malformed YAML: ${(error as Error).message}`);
  }

  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${filePath}: expected a YAML mapping`);
  }
  const definition = parsed as Record<string, unknown>;

  if (!definition.name || typeof definition.name !== "string") {
    throw new Error(`${filePath}: missing or invalid "name" field`);
  }
  if (!Array.isArray(definition.steps) || definition.steps.length === 0) {
    throw new Error(`${filePath}: missing or empty "steps" array`);
  }

  for (const [i, step] of definition.steps.entries()) {
    if (!step.input || typeof step.input !== "string") {
      throw new Error(`${filePath}: step ${i} missing "input" string`);
    }
    if (!Array.isArray(step.assertions)) {
      throw new Error(`${filePath}: step ${i} missing "assertions" array`);
    }
    for (const [j, a] of step.assertions.entries()) {
      if (!a.type) {
        throw new Error(`${filePath}: step ${i}, assertion ${j} missing "type"`);
      }
    }
  }

  return definition as TestDefinition;
}

export function discoverTests(moduleDir: string): string[] {
  const testsDir = path.join(moduleDir, "tests");
  if (!fs.existsSync(testsDir)) return [];

  return fs
    .readdirSync(testsDir)
    .filter((f) => f.endsWith(".yaml") || f.endsWith(".yml"))
    .sort()
    .map((f) => path.join(testsDir, f));
}
