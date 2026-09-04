# TabbyShell Scala progress

This is the live completion checklist for the plan in
[`plan.md`](plan.md). A checked item has evidence named beside it; a passing
unit suite alone never means the application is complete.

## Baseline recorded

- [x] Read the canonical `SPEC.md`, Scala guidance, YAML conformance inventory,
  and every current file in `src/main/scala/tabbyshell`.
- [x] Record the existing implementation boundary: `Value`, `Parser`, and
  `Renderer` exist; `Main` is a stub and app-layer modules are absent.
- [x] Run the existing Scala suite: `sbt "testOnly tabbyshell.*"` — 6 suites,
  148 tests, 0 failures/errors/ignored (2026-09-04).

## Existing modules: validated baseline, not end-to-end completion

- [x] Value ADT extensions and rectangular-table smart constructor — 6 unit
  tests green.
- [x] Pipeline parser, literal handling, comments, and logical line continuation
  — 56 example + 38 property tests green.
- [x] Deterministic renderer, box grid, dates/filesizes, compact values, and
  ANSI behavior — 30 example + 25 property/color tests green.
- [x] Reconcile application contracts with SPEC §9 (`home`, `prevCwd`, complete
  state propagation) and SPEC §3.3 (all error variants) — `ContractsTest`,
  2026-09-04.

## Phase 1 — contracts and parsing boundary

- [x] Add the missing `ShellError` variants and exact message tests.
- [x] Add `home` and `prevCwd` to the explicit shell state and test state changes.
- [x] Define typed command/execution result contracts.
- [x] Add a shared builtin argument-decoding helper — `ArgumentsTest` covers
  positional/flag separation, missing args, typed accessors, and quote rules.
- [x] Audit/add parser cases required by command integration and exact columns —
  AST provenance, `cd -` vs `cd "-"`, quoted `where` rejection, flag errors,
  and both filesize-overflow forms are covered.

## Phase 2 — codecs and file commands

- [x] Implement and test Circe-based JSON parsing, insertion-ordered records,
  uniform-record table promotion, two-space serialization, and exhaustive
  `Json.fold` dispatch — `JsonCodecTest` (8 tests), 2026-09-04.
- [x] Implement and test core `kantan.csv` 0.12.0 RFC 4180 parsing and
  serialization, including quoted commas/quotes/newlines and ragged-input
  rejection — `CsvCodecTest` (6 tests), 2026-09-04.
- [x] Implement path resolution relative to `ShellState` plus leading `~/`
  expansion — `BuiltinsTest` temporary-directory coverage, 2026-09-04.
- [x] Implement and test state-driven `pwd`, `cat`, and extension-dispatched
  `open` (`.json`, `.csv`, raw text) — `BuiltinsTest`, 2026-09-04.
- [x] Implement and test `ls`, including `-a`/`-l`, case-sensitive ordering,
  typed metadata, symlink classification, and `IoError` mapping —
  `BuiltinsTest`, 2026-09-04.
- [x] Implement and test `cd`, `cd <path>`, `cd -`, home navigation, state
  propagation, no-history, missing-path, and non-directory errors —
  `BuiltinsTest`, 2026-09-04.

## Phase 3 — structured-data commands

- [x] Implement/test `where`, including numeric cross-comparisons, compatible
  type rules, and preserved empty-table schema — `StructuredBuiltinsTest`,
  2026-09-04.
- [x] Implement/test `select`, stable `sort-by` (including reverse), and `get`
  for tables and records — `StructuredBuiltinsTest`, 2026-09-04.
- [x] Implement/test `first`, `last`, and Unicode-code-point `length`,
  including zero-count and empty-input behavior — `StructuredBuiltinsTest`,
  2026-09-04.
- [x] Add properties for select projection, stable sort/permutation, and
  bounded first/last results — `StructuredBuiltinsPropertyTest` (3 properties),
  2026-09-04.
- [x] Implement/test `to json`, `to csv`, and all `save` extension branches,
  including JSON/CSV/raw-string/plain-render precedence —
  `StructuredBuiltinsTest`, 2026-09-04.

## Phase 4 — execution and interactive application

- [x] Add the exact 14-command builtin registry and terminal-free pipeline
  executor with state propagation, warning propagation, and error
  short-circuiting — `ExecutorTest` (7 tests), 2026-09-04.
- [x] Implement/test external process execution, UTF-8 output, cwd selection,
  spawn-error mapping, and non-zero `ExternalFailed` results — `ExecutorTest`
  plus public cases 45–46, 2026-09-04.
- [x] Implement/test OpenRouter formatting with injected transport, fenced JSON
  decoding, schema validation, mock-base URL behavior, and deterministic
  disabled fallback — `AiTest` (5 tests) plus public case 45, 2026-09-04.
- [x] Add the JLine-backed terminal abstraction and REPL: banner, prompt,
  persisted-history configuration, continuation, errors, Ctrl-C, EOF, and
  goodbye — `ReplTest` (12 tests) and assembled-JAR TTY smoke run, 2026-09-04.
- [x] Replace the `Main.scala` stub with Decline-based CLI mode parsing and state
  initialization; keep `--help` unsupported per SPEC §10 — `CliTest` (5 tests)
  and `MainTest` (2 tests), 2026-09-04.
- [x] Implement colour precedence and deterministic `--eval`/`--eval-file`
  behavior — `MainTest` and public case 48, 2026-09-04.

## Final verification

- [x] Run `sbt scalafmtAll` for the current change (2026-09-04).
- [x] Run `sbt "testOnly tabbyshell.*"` after the current change — 18 suites,
  239 tests, 0 failures/errors/ignored (2026-09-04; Phase 4 complete).
- [x] Run `sbt scalafmtCheckAll` after the current change (2026-09-04).
- [x] Run `sbt assembly`; built
  `target/scala-3.9.0/tabbyshell-assembly-0.1.0.jar` (2026-09-04).
- [x] Run all 50 YAML cases through the standard `../verify` command — all 50
  cases passed (2026-09-04).
- [x] Add regression coverage for the verifier-discovered `--version`
  precedence when the harness also injects `--eval-file -` — `CliTest`,
  2026-09-04.
- [x] Manually smoke-test assembled-REPL startup, continuation, error recovery,
  `cd -`, and clean exit (2026-09-04). The sandbox denied writes to the normal
  home-directory history file; JLine history configuration is covered by the
  implementation and fake-terminal tests.
- [x] Reconcile affected Scala documentation with the final toolchain, commands,
  module layout, and conventions — `README.md` and `AGENTS.md`, 2026-09-04.

## Completion definition

- [x] Every SPEC §3–§9 behavior is implemented or explicitly listed as a
  specification-approved carve-out.
- [x] No user-facing filesystem, parser, process, network, or type failure
  escapes as an exception.
- [x] Colour-off output and error text meet the byte-exact parity contract.
- [x] The assembly, full Scala test suite, and public conformance suite are green.
