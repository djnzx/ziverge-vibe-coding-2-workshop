# Snap — Scala implementation

## IRON RULE — docs move with the code

**Any change to an approach, convention, code-style recommendation, tool, or
version is not done until the docs that state it are updated in the same
change.** Dependencies, version bumps, build or formatter settings, testing
technique, module conventions — and any documented command that turns out not to
do what it claims.

`README.md` carries toolchain, commands, layout, and testing strategy;
`CLAUDE.md` carries the working agreement; this file carries module
decomposition and conventions; `../SPEC.md` carries behavior binding all three
language implementations — change it only deliberately, and say so. A rule
stated in more than one of these must be changed in all of them.

## IRON RULE — a reusable pattern becomes a skill

**When a pattern proves itself here and would apply beyond this task, offer to
capture it as a skill** — offer, do not create one unilaterally. It qualifies if
it worked with evidence, generalises past this one file or project, and would be
costly to re-derive. Skills live in `.claude/skills/<name>/SKILL.md`.

## IRON RULE — isolate bulky subtasks in subagents

**Spawn a subagent whenever a subtask's context should not pollute the main
thread** — standing authorisation, no need to ask. Use it when the byproduct
dwarfs the answer: broad codebase sweeps, long conformance or build logs read
once for a verdict, large-file reads answering one narrow question. Do not use
it when the details feed later reasoning, when the agent would re-derive context
you already hold, or for a task that is two tool calls. Relay what matters from
the agent's report; it is not shown to the human.

## Setup

sbt fetches everything; no separate install step. Toolchain: sbt 2.0.8,
Scala 3.9.0, JDK 22+ (25 and 26 verified).

## Build / type-check

```
sbt compile
sbt assembly      # fat JAR — what `../run` and `../verify` execute
```

`assembly` writes to `target/scala-3.9.0/snap-assembly-0.1.0.jar`. That path is
pinned in `build.sbt` because sbt 2 defaults to `target/out/jvm/...`, which
`../run` and `../run_tests` do not scan. Do not change `name`, `version`, or
`assemblyOutputPath` without updating both.

## Run

```
sbt "run --version"
../run --lang scala --version                    # via the shared launcher
java -jar target/scala-3.9.0/snap-assembly-0.1.0.jar status
```

`../run` also reads the mtime of `src/main/scala/Main.scala` to pick the
freshest implementation when `--lang` is omitted — and treats Scala as
*unavailable* if that exact path is missing. Keep `Main.scala` there.

## Test

```
sbt "testOnly snap.*"                            # examples + properties
sbt "testOnly snap.ContractsTest"                # one suite
../verify --lang scala                           # the 28-case public suite
```

> **`sbt test` is not the full suite here.** sbt 2.0.8 routes `test` through
> `testQuick`, which skips suites it considers unchanged and prints
> `No tests to run for Test / testQuick` — and that record survives `clean`.
> Run the suite through `testOnly`, which always executes.

## Dependencies

- `io.circe::circe-parser` — JSON AST and parser for `repository.json`, and for
  local and global configuration. Convert between circe's `Json` and Snap's
  contracts explicitly; do not derive codecs. SPEC §4.1 rejects unknown fields
  and non-integer numbers, §4.5 validates the parsed typed value rather than the
  bytes, and derivation would quietly accept both.
- `org.scalameta::munit`, `org.scalameta::munit-scalacheck`,
  `org.scalacheck::scalacheck` (test only).

Everything else is the JDK standard library: `java.nio.file`,
`java.util.Base64`, `com.sun.net.httpserver`, `java.net.http.HttpClient`.

Two known sharp edges in the JSON edge, both to handle explicitly:

- **Duplicate object keys.** SPEC §4.1 says valid input has unique object keys.
  circe's `JsonObject` keeps the last value for a repeated key and reports
  nothing, so duplicate rejection needs its own pass over the raw text; the AST
  cannot tell you.
- **`java.util.Base64.getDecoder` is not strict enough on its own.** SPEC §4.3
  fixes `content` as *padded* RFC 4648. Check padding and alphabet before
  decoding rather than trusting the decoder to reject every non-canonical form.

Do not add `fansi` or a command-line option library — see `README.md` for why
SPEC §7.11's exact escape bytes and §7's positional grammar both rule them out.

## Target file structure

The scaffold starts with `Main.scala`, `Streams.scala`, `Contracts.scala`, and a
stub `Cli.scala`. Create the following modules as you implement the
specification; the names are a recommended decomposition, not pre-existing
files. Sources live in `src/main/scala/snap/` (package `snap`) except
`Main.scala`; tests mirror them under `src/test/scala/snap/`.

```
src/main/scala/Main.scala
                    — entry point; path pinned by ../run. Keep it thin.
src/main/scala/snap/
  Contracts.scala   — public domain model and canonical orderings (given)
  Streams.scala     — the UTF-8/LF output edge and injected TTY flags (given)
  Cli.scala         — §7 positional grammar and dispatch (stub)

  Versions.scala    — §3: contributor-ID and version validation, canonical
                      parse/render, the four-way causal comparison, join, and
                      Snap order
  Paths.scala       — §2: tracked-path validation, prefix-freedom by segment,
                      and the ancestor/descendant queries §6.2 needs
  Text.scala        — §4.4: text detection (valid UTF-8, no NUL), tokenization
                      after every LF, edit-script validation and application
  Diff.scala        — §5: the canonical token diff. The D(i,j) recurrence and
                      the delete-on-tie rule *define* the output; an optimized
                      algorithm is allowed only if it agrees on every input,
                      including repeated equal lines
  Ot.scala          — §6.3: transform one text edit through an aggregate
                      context edit, `Q insert` row first
  Replay.scala      — §6.1/§6.2/§6.4: ready-set selection and ordering,
                      per-patch integration, namespace resolution, the six
                      path-level rules, and warning collection
  RepositoryJson.scala — §4.1: strict parse and canonical serialization of
                      `repository.json` (two-space indent, trailing LF)
  Validation.scala  — §4.5: the six validation passes, in order
  Workspace.scala   — §2/§10: repository discovery by walking to the root,
                      working-tree scan (failing on symlinks and other
                      unsupported entries), materialization, and the
                      write-files-then-atomically-replace-metadata sequence
  Config.scala      — §8: local `.snap/config.json` over `$HOME/.snapconfig.json`
  DiffRender.scala  — §7.6: unified-style blocks, `/dev/null` headers, the
                      no-newline marker, and the binary line
  Presentation.scala— §7.11: SNAP_COLOR/NO_COLOR resolution and `S(n, text)`.
                      The only file allowed to contain an escape sequence
  Http.scala        — §9: the read-only server and the single-GET client
  Commands.scala    — §7: one function per command
```

## Conventions

- Scala 3 syntax, `enum`-based ADTs, exhaustive matches — never `asInstanceOf`,
  `isInstanceOf`, `null`, or `return` (enforced by WartRemover).
- Errors are values: return `Either[SnapError, A]`; do not throw across module
  boundaries. `SnapError` renders as `snap: <detail>` and exits 1; only genuinely
  unanticipated failures reach exit 2.
- **Nothing reads the process working directory, the environment, or the clock
  except at the CLI edge.** Commands take the repository root, the cwd, and the
  resolved configuration as parameters. That is what makes them testable and
  keeps cross-language output identical.
- **Never sort tracked paths as `String`s.** SPEC §2 orders them by unsigned
  UTF-8 bytes, which differs from UTF-16 code-unit order above the BMP. Use the
  `Ordering[TrackedPath]` in `Contracts.scala`.
- **Never compare file contents as `Array[Byte]`.** Arrays compare by reference;
  `FileBytes` compares by content, and SPEC §2 defines a clean tree as an exact
  byte-map equality.
- **Never write output with `println`.** Its terminator is the platform's; SPEC
  §10 fixes LF. Print records with an explicit `\n` through `Streams`.
- **Never hand-write an escape sequence outside `Presentation.scala`**, and
  build test expectations from the same `S(n, text)` helper so they state the
  §7.11 rule rather than a byte soup.
- Revisions and edit counts are bounded by `Limits.maxSafeInteger`
  (9007199254740991). Parse through `BigInt` and reject overflow; never let a
  `Long` wrap or a `NumberFormatException` escape.
- Read repositories as *typed values*: SPEC §4.1 says the parsed value, not the
  serialized bytes, is authoritative. Two repositories agree when their patches
  are structurally equal, whatever their whitespace or key order.
- Validate completely before mutating (§10). Build the whole target tree in
  memory first; write working files; then replace `repository.json` through a
  same-directory temporary file with `ATOMIC_MOVE`.
- "Later" in §6.4 always means canonical integration order, never wall-clock
  time. There is no timestamp anywhere in the format, and adding one would be a
  spec change.
- **`snap --version` prints `snap 1.0.0`** (`../tests/28-terminal-presentation.yaml`).
  That semver is *not* `build.sbt`'s `version`, which is `0.1.0` and exists only
  to name the assembly JAR. Hold the reported version in its own constant; wiring
  it to the build version would silently break the acceptance suite the next time
  the JAR is renamed.
- **`diff` has its own usage-error family.** Every other grammar violation is
  `snap: invalid command or arguments`; a malformed `diff` is
  `snap: usage: snap diff …` (`../tests/24-cli-grammar-matrix.yaml`).
- The `status` deleted-row symbol is **U+2212 MINUS SIGN**, not a hyphen.
- **Escape control characters in any error detail that quotes user input.** §10
  says an error is one line, and a contributor id or version argument may hold a
  newline. Echoing it raw splits one error across two and breaks the suite's
  `^snap: … .+\n$` matchers. `Versions.describe` is the pattern: `(empty)` for
  empty input, then backslash, tab, and LF escaped in §7.4's order, then any
  other control character as `\uXXXX`. A property found this, not an example.

## Planning artifacts

[`tracking/plan.md`](tracking/plan.md) holds the phase-by-phase implementation
sequence with its SPEC references, tests, and exit criteria;
[`tracking/progress.md`](tracking/progress.md) is the evidence-based checklist
that mirrors it. Update the checklist as work lands — an item is checked only
beside a named test suite, verifier case, or command whose output was read.

## Open questions to settle against the tests

Two places where the spec leaves a choice the acceptance suite will decide.
Resolve them by reading `../tests/`, and if the tests are silent too, correct
`../SPEC.md` and add a case rather than letting the implementation decide.

- **Warning tie-break.** §6.4 sorts warnings "by path, then reason" without
  saying how reasons order. `Contracts.scala` orders them by wire token;
  declaration order would give a different answer for two reasons on one path.
  Checked: every warning assertion in `../tests/` uses distinct paths, so the
  suite never discriminates. Leave the current choice, and do not invent a case
  that pins a different one.
- **Per-stream TTY detection.** §7.11's `auto` mode selects presentation
  independently for stdout and stderr, but the JVM exposes no per-stream
  `isatty` — `Console.isTerminal` describes the console as a whole. `Streams`
  injects both flags so the *logic* is testable; the real-process detection in
  `Streams.standard` reports them together. It only matters if one stream is
  redirected and the other is not, which the pipe-based harness cannot produce.

## Scope discipline

Snap's small surface is deliberate (SPEC §12). Do not add branches, staging,
checkout, push, authentication, object storage, or unresolved-conflict
machinery. If something is genuinely missing, change `../SPEC.md` first and say
so out loud — it binds all three language implementations — and add a case to
the public YAML suite in the same change.
