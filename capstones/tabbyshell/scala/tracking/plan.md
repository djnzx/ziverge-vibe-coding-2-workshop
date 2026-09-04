# TabbyShell Scala implementation plan

## Scope and starting point

Implement the Scala TabbyShell application in this directory only. The
behavioral source of truth is [`../../SPEC.md`](../../SPEC.md); the YAML cases
in [`../../tests`](../../tests) are its executable conformance suite. Preserve
the current Scala 3 / JDK / cats-parse / Circe / Kantan CSV / fansi approach
unless a change is needed by the specification, in which case update the
applicable Scala docs in the same change.

Current baseline (2026-09-04):

- `Value.scala`, `Parser.scala`, and `Renderer.scala` are implemented and have
  focused example and property tests.
- `Arguments.scala` preserves command-relevant argument provenance;
  `Contracts.scala` models complete shell state and all SPEC §3.3 errors.
- `Builtins.scala`, `JsonCodec.scala`, and `CsvCodec.scala` implement the
  Phase 2 filesystem/navigation boundary plus the Phase 3 typed transforms and
  serialization/save boundary, with Circe and core Kantan CSV RFC 4180 support.
- `Executor.scala` dispatches all 14 builtins and external commands, with
  process/AI and warning effects kept behind injectable boundaries.
  `Ai.scala`, `Terminal.scala`, `Repl.scala`, `Cli.scala`, and `Main.scala`
  complete the OpenRouter, JLine, Decline, and application-launcher edges.
- `sbt "testOnly tabbyshell.*"` passes: 239 tests across 18 suites (0 failures,
  errors, or ignored tests), and all 50 public YAML cases pass through the
  assembled Scala application.

## Delivery sequence

### 1. Establish the application contracts

1. Extend `Contracts.scala` with the state and result types needed to run a
   pipeline without reading mutable process state inside a builtin.
   - Represent `cwd`, optional `prevCwd`, `home`, frozen `now`, and resolved
     colour setting in `ShellState`.
   - Keep command execution state explicit: a command should return its value
     and, only for `cd`, an updated state.
   - Decide and document the narrow boundary for environment/process access:
     it belongs in `Main`/`Ai`, not in the pure renderer or builtin logic.
2. Complete `ShellError` with `MissingColumn`, `MissingArg`, `BadArg`,
   `IoError`, and `ExternalFailed`, each rendering the exact SPEC §3.3 message.
3. Keep `Value.Table` construction behind `Values.table` whenever rows are
   derived from input, JSON, CSV, or a transform, so ragged data cannot reach
   the renderer or serializer.
4. Add focused tests for state transitions and every error-message constructor
   before wiring them to commands.

**Exit criterion:** all state transitions and all seven error formats are
tested, with no thrown user-facing errors crossing module boundaries.

### 2. Finish and lock down the surface-language boundary

1. Retain the existing cats-parse pipeline parser and logical-line pre-pass;
   audit it against every SPEC §3.1 token and column-reporting rule before
   relying on it from the REPL or `--eval-file`.
2. Add a small argument-decoding layer shared by builtins. It must distinguish
   bare identifiers from quoted strings where the spec requires it (`where`
   rejects quoted columns; `select`, `sort-by`, and `get` accept either), and
   recognize short and long flags consistently.
3. Define literal-to-value and literal-to-external-argument conversions in one
   place so numeric values, `null`, and quoted strings do not acquire
   inconsistent representations.
4. Add parser/argument tests for flags, missing operands, comment and
   continuation boundaries, `cd -`, oversized literals, and exact parse-error
   columns. Use properties for totality and grammar-wide invariants.

**Exit criterion:** parsing produces only valid `Pipeline` values, command
argument validation is reusable, and parser tests remain green.

### 3. Implement data codecs before file-facing commands — completed 2026-09-04

1. Add a JSON codec module using Circe's `Json` AST and parser, with an explicit
   `Value` ↔ `Json` conversion.
   - Decode JSON scalars, lists, and insertion-ordered records without using
     generic codec derivation.
   - Promote an array of records to `Table` only when all records share keys in
     the same order; otherwise preserve it as `List[Record]`.
   - Serialize every `Value` according to SPEC §5.13 via an explicit `Json`
     mapping, Circe's two-space printer, and one trailing newline.
2. Add an RFC 4180 CSV codec through core `kantan.csv`.
   - Read a header plus string-valued cells into a rectangular table.
   - Parse quoted commas, quotes, and newlines; map parser failures and reject
     ragged input as user-facing command errors rather than host exceptions.
   - Write header and rows with correct quoting and deterministic newlines.
   - Do not add `kantan.csv-cats` or `kantan.csv-java8`: neither integration is
     needed by TabbyShell's `Either`-based, string-cell boundary.
3. Write examples plus round-trip/rejection properties for JSON and CSV. Include
   uniform/non-uniform JSON records, embedded quotes/newlines, and empty tables.

**Exit criterion:** `open`, `to`, and `save` can depend on codecs with no
format-specific logic duplicated in their command functions.

### 4. Implement filesystem and navigation builtins — completed 2026-09-04

1. Add a path helper which makes paths absolute relative to `state.cwd` and
   expands only a leading `~/` using `state.home`.
2. Implement `pwd`, `cat`, and `open` as input-ignoring commands. Resolve all
   runtime filesystem paths through the state helper and convert filesystem
   failures to `IoError` with the OS message.
3. Implement `ls [path] [-a] [-l]`.
   - Read metadata once per entry; classify file, directory, and symlink.
   - Produce the mandated ordered columns and `Filesize`/`Date` values.
   - Include dotfiles only with `-a`/`--all`, add `mode`/`uid` only with
     `-l`/`--long`, and sort names case-sensitively.
4. Implement `cd [path]` as the only state mutation.
   - `cd` uses the stored home path; `cd -` swaps through `prevCwd`.
   - Check existence and directory-ness separately to preserve the required
     `IoError` versus `BadArg` distinction.
5. Unit-test path expansion and state changes in temporary directories, then
   exercise them through sequential `--eval-file` commands.

**Exit criterion:** all filesystem commands use state rather than
`user.dir`/ambient cwd, and the `cd` YAML cases pass.

### 5. Implement typed pipeline transforms — completed 2026-09-04

1. Implement shared table/record access helpers that preserve column order and
   generate `TypeMismatch`/`MissingColumn` consistently.
2. Implement `where <col> <op> <literal>`.
   - Require `Table` input and a bare column identifier.
   - Support the six operators with numeric cross-comparisons for `Int`,
     `Float`, and `Filesize`; strings lexicographically; booleans only for
     equality; and dates only against dates.
3. Implement `select`, `sort-by`, and `get`.
   - Preserve requested column order and table row count for `select`.
   - Make `sort-by` stable, support `--reverse`/`-r`, and reject mixed-type
     columns.
   - Return a `List` from table `get` and a scalar from record `get`.
4. Implement `first`, `last`, and `length`, including zero counts, empty input,
   Unicode code-point string length, and `Null` length zero.
5. Add MUnit examples for each rule and ScalaCheck properties for stable sort,
   element preservation, and bounded first/last results.

**Exit criterion:** every built-in in SPEC §§5.6–5.12 has exact error behavior
and composes correctly in a pipeline.

### 6. Implement serialization commands and saving — completed 2026-09-04

1. Implement `to json` and `to csv` using the codecs from step 3, with strict
   format validation and `TypeMismatch` for non-table CSV input.
2. Implement `save <path>` with the specified priority:
   `.json` always uses JSON; `.csv` uses CSV only for tables; raw `Str` bytes
   otherwise; then plain colour-free renderer output.
3. Ensure parent/file I/O errors surface as `IoError`, output is written exactly
   once, and raw strings never gain a newline.
4. Add temporary-file integration tests for every branch, including a scalar
   saved as JSON and non-table content saved under a `.csv` name.

**Exit criterion:** all `to`/`save` YAML cases and local codec round-trips pass.

### 7. Add external-command execution and AI formatting — completed 2026-09-04

1. Implement external command execution for non-builtin heads using
   `ProcessBuilder` in `state.cwd`; pass only parsed literal arguments and
   capture UTF-8 output lossily.
2. Map non-zero process exits to `ExternalFailed`; map spawn failures to
   `IoError`.
3. Add `Ai.scala` as a boundary client for OpenRouter.
   - Read `OPENROUTER_API_KEY`, optional base URL, and `TABBY_DISABLE_AI` only
     at this boundary.
   - POST the specified model, system prompt, user content, temperature, and
     30-second timeout.
   - Strip one optional Markdown fence pair, validate the returned JSON schema,
     and convert a table response to string-cell `Value.Table` or a string
     response to `Value.Str`.
4. On every AI failure, keep the successful process output as
   `Str(stdout.trimEnd)`, emit exactly one dim warning to stderr, and continue
   the pipeline. Test the deterministic disabled path and a mock base URL;
   never use real credentials in tests.

**Exit criterion:** external failure has the mandated error, and the disabled
AI fallback passes without network access.

### 8. Build the executor and command registry — completed 2026-09-04

1. Create a builtin dispatch table containing exactly the 14 SPEC commands.
2. Implement the pipeline interpreter: start with `Value.Null`, execute commands
   left-to-right, thread `ShellState`, abort on the first `ShellError`, and
   route unknown heads to the external-command path.
3. Keep rendering and terminal output outside the executor except for explicit
   warning data passed back to the caller; this keeps pipeline semantics easy to
   test without a live terminal.
4. Add executor tests for ignored input, state propagation through pipelines,
   short-circuiting after errors, and external fallback dispatch.

**Exit criterion:** a pipeline can be evaluated entirely through a typed,
side-effect-bounded API.

### 9. Implement terminal, REPL, and CLI entry points — completed 2026-09-04

0. Consider using jline for convenient input from console
1. Add `Terminal.scala` as a narrow JLine-backed stdout/stderr/prompt and input
   event abstraction, and `Repl.scala` for banner, prompts, history, line
   buffering, errors, and goodbye behavior.
   - Resolve `banner.txt` from `TABBY_PROJECT_ROOT`.
   - Configure JLine to persist history at `~/.tabbyshell_history`, retaining
     at most 1,000 entries, and disable shell-style event expansion so it does
     not reinterpret TabbyShell input.
   - Translate JLine's `UserInterruptException` and `EndOfFileException` into
     typed terminal events. Implement short cwd rendering, line continuation,
     `exit`/`quit`, EOF, and Ctrl-C buffer reset according to SPEC §7.
   - Keep JLine out of `--eval` and `--eval-file`; inject a fake `Terminal` in
     REPL tests instead of requiring a real TTY.
2. Replace the `Main.scala` stub with strict command-line parsing via the core
   Decline library for `--eval`, `--eval-file` (including `-`), `--no-color`,
   `--interactive`, and `--version`; reject unsupported or malformed flag
   combinations as user errors.
   - Add only `com.monovore %% decline`; the application has no Cats Effect
     boundary, so do not add `decline-effect`.
   - Use Decline's parser API rather than an auto-running `CommandApp`, keeping
     error output, exit codes, and the deliberate absence of a `--help` flag
     under TabbyShell control.
   - Add a CLI regression test that `--help` is rejected, as required by
     SPEC §10.
3. Initialize state once at the application boundary.
   - Use absolute cwd/home paths and freeze `TABBY_NOW` when valid, otherwise
     read the system time once.
   - Apply colour precedence exactly: explicit flag, non-empty `NO_COLOR`, TTY,
     then off.
4. Make `--eval` and `--eval-file` render with colour off, process logical
   lines, skip empty/comment-only lines, stop at the first user error, and map
   outcomes to exits 0/1/2.
5. Add CLI/repl tests with injected terminal and state dependencies so that
   interactive paths do not require a human terminal.

**Exit criterion:** the shared `run` launcher can invoke the Scala assembly in
all required modes and the YAML harness has a deterministic interface.

### 10. Verify the shipped application and keep documentation honest

1. At each step, write the failing MUnit/property test first, run it to confirm
   the intended failure, then make the smallest implementation change.
2. Run `sbt scalafmtAll`, `sbt "testOnly tabbyshell.*"`, and `sbt assembly` after
   each coherent component is complete.
3. Run the public verifier from the project root:

   ```bash
   ./capstones/tabbyshell/verify --lang scala \
     --implementation-root capstones/tabbyshell/scala
   ```

4. Investigate each failing YAML test against SPEC.md rather than changing a
   test to make it green. Add regression tests for resolved defects.
5. Update `README.md`, `CLAUDE.md`, and `AGENTS.md` whenever implementation
   work changes a documented command, dependency, convention, or layout.

**Exit criterion:** formatter, full Scala suite, assembly, and all 50 public
YAML cases pass; manual REPL smoke checks confirm startup, a continued pipeline,
an error, `cd -`, and clean exit.

## Implementation order rationale

The order keeps parsing, codecs, values, and rendering deterministic and
independently testable before filesystem/network/process effects are introduced.
It then adds builtins in composable groups, places the external/AI boundary
behind a tested executor, and leaves terminal concerns at the edge. This also
makes every public YAML failure localize to a small layer rather than requiring
REPL-driven debugging.
