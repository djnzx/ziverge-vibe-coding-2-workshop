# TabbyShell — Scala implementation

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
costly to re-derive. Skills live in `.claude/skills/<name>/SKILL.md`; the
`skill-creator` skill scaffolds them.

## IRON RULE — isolate bulky subtasks in subagents

**Spawn a subagent whenever a subtask's context should not pollute the main
thread** — standing authorisation, no need to ask. Use it when the byproduct
dwarfs the answer: broad codebase sweeps, long conformance or build logs read once
for a verdict, skill baseline testing, large-file reads answering one narrow
question. Do not use it when the details feed later reasoning, when the agent
would re-derive context you already hold, or for a task that is two tool calls.
Relay what matters from the agent's report; it is not shown to the human.

## Setup

sbt fetches everything; no separate install step. Toolchain: sbt 2.0.8,
Scala 3.9.0, JDK 21+ (JDK 25 verified).

## Build / type-check

```
sbt compile
sbt assembly      # fat JAR — what `run`, `verify`, and the harness execute
```

`assembly` writes to `target/scala-3.9.0/tabbyshell-assembly-0.1.0.jar`. That
path is pinned in `build.sbt` because sbt 2 defaults to `target/out/jvm/...`,
which `capstones/tabbyshell/run` and `test-harness/src/runner.ts` do not scan.
Do not change `name`, `version`, or `assemblyOutputPath` without updating both.

## Run

```
sbt "run"                                        # interactive REPL
sbt "run --eval \"ls | first 3\""
../run --lang scala --eval "ls | first 3"        # via the shared launcher
echo "ls | first 3" | java -jar target/scala-3.9.0/tabbyshell-assembly-0.1.0.jar --eval-file -
```

## Test

```
sbt "testOnly tabbyshell.*"                      # examples + properties
sbt "testOnly tabbyshell.ParserTest"             # examples only
sbt "testOnly tabbyshell.ParserPropertyTest"     # properties only
```

> **`sbt test` is not the full suite here.** sbt 2.0.8 routes `test` through
> `testQuick`, which skips suites it considers unchanged and prints
> `No tests to run for Test / testQuick` — and that record survives `clean`.
> Run the suite through `testOnly`, which always executes.

Tests come in two flavours and both are expected:

- **MUnit examples** (`*Test.scala`) — written test-first, one behavior each.
  They pin the specific cases a reader will look up: the exact AST for
  `ls | where size > 0b`, the exact text of an error message.
- **ScalaCheck properties** (`*PropertyTest.scala`, extending
  `munit.ScalaCheckSuite`) — written wherever the claim is universal rather
  than specific. Round-trips, totality (never throws), algebraic laws
  (`sort-by` is a stable permutation; `select` preserves row count), unit
  scaling, and byte-exactness of `render(color = false)` are all properties.

The convention that makes round-trips tractable: generators emit a token's
**source text together with the value it must parse to**, so a pipeline can be
generated next to its expected AST without writing a pretty-printer. See
`ParserPropertyTest`.

The language-neutral verifier is the public test suite, and is the contract
that actually ships — a green unit suite does not imply conformance. From the
repository root:

```
./capstones/tabbyshell/verify --lang scala \
  --implementation-root capstones/tabbyshell/scala
```

## Dependencies

- `org.typelevel::cats-parse` — tokenizer + pipeline grammar, and the JSON
  parser used by `open *.json` and the AI response decoder.
- `com.lihaoyi::fansi` — every ANSI escape in SPEC 6.6. Nothing writes escape
  sequences by hand, in `main` or in tests.
- `org.scalameta::munit`, `org.scalameta::munit-scalacheck`,
  `org.scalacheck::scalacheck` (test only).

Everything else uses the JDK standard library (`java.nio.file`,
`java.lang.ProcessBuilder`, `java.net.http.HttpClient`, `java.io`). No
JSON library — parse JSON with cats-parse, emit it by hand per SPEC 5.13.

## Target file structure

The scaffold starts with `Main.scala` and `Contracts.scala`. Create the
following modules as you implement the specification; the names are a
recommended decomposition, not pre-existing files:

Sources live under `src/main/scala/tabbyshell/`, matching the `tabbyshell`
package (standard Java/Scala layout); tests mirror it under
`src/test/scala/tabbyshell/`.

```
src/main/scala/tabbyshell/
  Value.scala       — `object Values` smart constructors (Table rejects ragged rows)
                      plus `typeName` / `isScalar` / `isNumeric` extensions. The
                      constructors cannot live in a `Value` companion: a companion
                      must share a file with its type, and the enum is in Contracts.scala.
  Parser.scala      — line-continuation pre-pass (`logicalLines`), then the
                      cats-parse tokenizer + pipeline grammar → Pipeline
  Renderer.scala    — `RenderOpts` + pure `render(value, opts)`. The SPEC 6.3
                      compact form is `compact()`, not `inline()` — `inline` is a
                      Scala 3 soft keyword and cannot name a method.
  Builtins.scala    — one function per command + dispatch table
  Executor.scala    — pipeline interpreter + AI-external fallback dispatch
  Ai.scala          — OpenRouter HTTP POST + JSON response parser
  Repl.scala        — readline loop, banner, history, line continuation
  Terminal.scala    — small abstraction for stdout/stderr/prompt
  Contracts.scala   — public domain model (given)
  Main.scala        — CLI parsing (--eval / --eval-file / --no-color /
                      --interactive / --version)
```

Note: `capstones/tabbyshell/run` reads the mtime of `src/main/scala/Main.scala`
only to pick the freshest implementation when `--lang` is omitted. With the
packaged layout that path is absent and the mtime reads 0, so pass
`--lang scala` explicitly whenever another implementation is also built.

## Conventions

- Scala 3 syntax, `enum`-based ADTs, exhaustive matches — never `asInstanceOf`,
  `isInstanceOf`, `null`, or `return` (enforced by WartRemover).
- Errors are values: return `Either[ShellError, Value]`; do not throw across
  module boundaries.
- Renderer must not read the system clock — take `now` from `RenderOpts`.
- Builtins must not read `user.dir` or the process cwd — take `cwd` from
  `ShellState`.
- Resolve `banner.txt` and other project assets from `TABBY_PROJECT_ROOT`,
  never by guessing from cwd or the JAR's location.
- Use `Vector` for the ordered sequences in `Value`; `Record` keys are an
  ordered `Vector[(String, Value)]`, never a `Map`.
- `sbt scalafmtAll` before committing.
- Numeric literals that do not fit `Int64` are a parse error, never a silent
  wrap or a thrown `NumberFormatException` (SPEC 3.1). Widen through `BigInt` /
  `BigDecimal` before narrowing.
- Colour goes through fansi — `fansi.Bold.Faint` for dim, `fansi.Bold.On ++
  fansi.Color.Cyan` for headers, and so on. Never write an escape sequence
  literally, and assert on `fansi....render` in tests rather than on raw bytes,
  so the tests survive a library change. `fansi.Str(out).plainText` is the way
  to check output is escape-free.
- Attributes decorate a cell's *content*, never its padding: widths are computed
  from plain text so that colour is purely additive and colour-off output stays
  byte-identical (SPEC 10).
- Format every number and date through `String.format(Locale.ROOT, ...)`. The
  default locale would swap the decimal separator or the digits and silently
  break the byte-exactness guarantee in SPEC 10.
- Widen to `BigInt` / `BigDecimal` for filesize scaling and rounding: SPEC 6.5
  wants half-away-from-zero, which is `RoundingMode.HALF_UP`, not `math.round`.
- Split input with `Parser.logicalLines` before parsing, never on `\n` directly:
  a continued pipeline keeps its newlines, and the grammar treats `\n` as
  inter-token whitespace (SPEC 3.1). `Parser.continuesLine` is what the REPL
  needs for its two-space continuation prompt (SPEC 7.3).
