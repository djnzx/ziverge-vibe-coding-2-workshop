import * as path from "path";
import * as fs from "fs";
import { execSync } from "child_process";
import { loadTest, discoverTests } from "./yaml-loader.js";
import { runTest } from "./runner.js";
import { printResults, printAgentSummary } from "./reporter.js";
import type { LangConfig } from "./types.js";

function findJava(): string {
  const sbtJava = "/opt/homebrew/opt/openjdk/bin/java";
  if (fs.existsSync(sbtJava)) return sbtJava;

  const javaHome = process.env.JAVA_HOME;
  if (javaHome) return path.join(javaHome, "bin", "java");

  return "java";
}

function findJarInScalaVersionDirs(baseDir: string): string {
  if (!fs.existsSync(baseDir)) return "";

  const scalaVersionDirs = fs.readdirSync(baseDir).filter((d) => d.startsWith("scala-"));
  for (const svd of scalaVersionDirs) {
    const svdPath = path.join(baseDir, svd);
    const entries = fs.readdirSync(svdPath, { withFileTypes: true });

    // sbt 1.x layout: target/scala-<ver>/<project>-assembly-<ver>.jar
    const jar = entries.find((e) => e.isFile() && e.name.endsWith("-assembly-0.1.0-SNAPSHOT.jar"));
    if (jar) return path.join(svdPath, jar.name);

    // sbt 2.x layout: target/out/jvm/scala-<ver>/<project>/<project>-assembly-<ver>.jar
    for (const projectDir of entries.filter((e) => e.isDirectory())) {
      const projectPath = path.join(svdPath, projectDir.name);
      const files = fs.readdirSync(projectPath);
      const nestedJar = files.find((f) => f.endsWith("-assembly-0.1.0-SNAPSHOT.jar"));
      if (nestedJar) return path.join(projectPath, nestedJar);
    }
  }
  return "";
}

function findScalaJar(moduleDir: string): string {
  const targetDir = path.join(moduleDir, "scala", "target");
  return (
    findJarInScalaVersionDirs(targetDir) || findJarInScalaVersionDirs(path.join(targetDir, "out", "jvm"))
  );
}

const LANG_CONFIGS: Record<string, (moduleDir: string) => LangConfig> = {
  ts: (moduleDir) => ({
    command: "npx",
    args: ["tsx", "src/main.ts"],
    cwd: path.join(moduleDir, "ts"),
  }),
  rust: (moduleDir) => ({
    command: "cargo",
    args: ["run", "--quiet"],
    cwd: path.join(moduleDir, "rust"),
  }),
  scala: (moduleDir) => {
    const scalaCwd = path.join(moduleDir, "scala");
    console.log("Building Scala fat JAR...");
    execSync("sbt --batch assembly", { cwd: scalaCwd, stdio: "inherit" });
    const jar = findScalaJar(moduleDir);
    if (!jar) {
      console.error("Scala fat JAR not found after sbt assembly.");
      process.exit(1);
    }
    return {
      command: findJava(),
      args: ["-jar", jar],
      cwd: scalaCwd,
    };
  },
};

function usage(): never {
  console.error("Usage: npx tsx test-harness/src/cli.ts --lang <ts|rust|scala>");
  console.error("");
  console.error("Options:");
  console.error("  --lang <lang>    Language to test (ts, rust, scala)");
  console.error("  --module <dir>   Module directory (default: auto-detect from harness location)");
  console.error("  --verbose, -v    Print agent stdin/stdout/stderr for each step");
  console.error("  --summary <file> Write agent-friendly summary to file");
  process.exit(1);
}

function parseArgs(argv: string[]): {
  module: string;
  lang: string;
  summaryFile?: string;
  verbose: boolean;
} {
  let mod: string | undefined;
  let lang: string | undefined;
  let summaryFile: string | undefined;
  let verbose = false;

  for (let i = 2; i < argv.length; i++) {
    switch (argv[i]) {
      case "--module":
        mod = argv[++i];
        break;
      case "--lang":
        lang = argv[++i];
        break;
      case "--summary":
        summaryFile = argv[++i];
        break;
      case "--verbose":
      case "-v":
        verbose = true;
        break;
      default:
        console.error(`Unknown argument: ${argv[i]}`);
        usage();
    }
  }

  if (!mod) {
    mod = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");
  }
  if (!lang) usage();
  if (!LANG_CONFIGS[lang]) {
    console.error(`Unknown language: ${lang}. Supported: ${Object.keys(LANG_CONFIGS).join(", ")}`);
    process.exit(1);
  }

  return { module: mod, lang, summaryFile, verbose };
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv);
  const moduleDir = path.resolve(args.module);

  if (!fs.existsSync(moduleDir)) {
    console.error(`Module directory not found: ${moduleDir}`);
    process.exit(1);
  }

  const langConfig = LANG_CONFIGS[args.lang]!(moduleDir);

  if (!fs.existsSync(langConfig.cwd)) {
    console.error(`Language directory not found: ${langConfig.cwd}`);
    process.exit(1);
  }

  const testFiles = discoverTests(moduleDir);
  if (testFiles.length === 0) {
    console.error(`No test files found in ${path.join(moduleDir, "tests")}/`);
    process.exit(1);
  }

  const env: Record<string, string> = {};
  const envFile = path.resolve(moduleDir, "../.env");
  if (fs.existsSync(envFile)) {
    const lines = fs.readFileSync(envFile, "utf-8").split("\n");
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const eqIndex = trimmed.indexOf("=");
      if (eqIndex > 0) {
        env[trimmed.slice(0, eqIndex)] = trimmed.slice(eqIndex + 1);
      }
    }
  }

  const tests = testFiles.map(loadTest);
  const results = [];

  for (const test of tests) {
    const result = await runTest(test, langConfig, env, args.verbose);
    results.push(result);
  }

  const moduleName = path.basename(moduleDir);
  printResults(moduleName, args.lang, results, args.verbose);

  const summary = printAgentSummary(results);
  if (args.summaryFile) {
    fs.writeFileSync(args.summaryFile, summary, "utf-8");
  }

  const allPassed = results.every((r) => r.passed);
  process.exit(allPassed ? 0 : 1);
}

main().catch((e) => {
  console.error(`Fatal: ${e}`);
  process.exit(1);
});
