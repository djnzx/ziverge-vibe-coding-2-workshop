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
  — 49 example + 38 property tests green.
- [x] Deterministic renderer, box grid, dates/filesizes, compact values, and
  ANSI behavior — 30 example + 25 property/color tests green.
- [x] Reconcile application contracts with SPEC §9 (`home`, `prevCwd`, complete
  state propagation) and SPEC §3.3 (all error variants) — `ContractsTest`,
  2026-09-04.

## Phase 1 — contracts and parsing boundary

- [x] Add the missing `ShellError` variants and exact message tests.
- [x] Add `home` and `prevCwd` to the explicit shell state and test state changes.
- [x] Define typed command/execution result contracts.
- [ ] Add a shared builtin argument-decoding helper.
- [ ] Audit/add parser cases required by command integration and exact columns.

## Phase 2 — codecs and file commands

- [ ] Implement and test JSON parsing, table promotion, and pretty serialization.
- [ ] Implement and test RFC 4180 CSV parsing and serialization.
- [ ] Implement path resolution relative to `ShellState` plus `~/` expansion.
- [ ] Implement and test `pwd`, `cat`, and `open`.
- [ ] Implement and test `ls`, including `-a`/`-l`, ordering, metadata, and errors.
- [ ] Implement and test `cd`, `cd <path>`, `cd -`, and no-history errors.

## Phase 3 — structured-data commands

- [ ] Implement/test `where`, including numeric cross-comparisons and type rules.
- [ ] Implement/test `select`, `sort-by`, and `get`.
- [ ] Implement/test `first`, `last`, and `length`.
- [ ] Add properties for sort stability/permutation and first/last bounds.
- [ ] Implement/test `to json`, `to csv`, and all `save` extension branches.

## Phase 4 — execution and interactive application

- [ ] Add the builtin registry and pipeline executor with error short-circuiting.
- [ ] Implement/test external process execution and exit/error mapping.
- [ ] Implement/test OpenRouter formatting, mock-base-url behavior, and disabled fallback.
- [ ] Add the terminal abstraction and REPL: banner, prompt, history, continuation,
  errors, Ctrl-C, EOF, and goodbye.
- [ ] Replace the `Main.scala` stub with CLI mode parsing and state initialization.
- [ ] Implement colour precedence and deterministic `--eval`/`--eval-file` behavior.

## Final verification

- [x] Run `sbt scalafmtAll` for the current change (2026-09-04).
- [x] Run `sbt "testOnly tabbyshell.*"` after the current change — 7 suites,
  152 tests, 0 failures/errors/ignored (2026-09-04).
- [ ] Run `sbt assembly`; confirm the shared launcher discovers the expected JAR.
- [ ] Run all 50 YAML cases through `../verify --lang scala --implementation-root .`.
- [ ] Add regression tests for any verifier failures discovered during implementation.
- [ ] Manually smoke-test REPL startup, continuation, error recovery, `cd -`, and exit.
- [ ] Reconcile affected Scala documentation with the final toolchain, commands,
  module layout, and conventions.

## Completion definition

- [ ] Every SPEC §3–§9 behavior is implemented or explicitly listed as a
  specification-approved carve-out.
- [ ] No user-facing filesystem, parser, process, network, or type failure
  escapes as an exception.
- [ ] Colour-off output and error text meet the byte-exact parity contract.
- [ ] The assembly, full Scala test suite, and public conformance suite are green.
