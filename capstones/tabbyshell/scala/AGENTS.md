# TabbyShell — Scala implementation

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
sbt test                                         # unit tests (munit)
```

The language-neutral verifier is the public test suite. From the repository
root:

```
./capstones/tabbyshell/verify --lang scala \
  --implementation-root capstones/tabbyshell/scala
```

## Dependencies

- `org.typelevel::cats-parse` — tokenizer + pipeline grammar, and the JSON
  parser used by `open *.json` and the AI response decoder.
- `org.scalameta::munit` (test only).

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
  Value.scala       — Value ADT smart constructors (Table rejects ragged rows)
  Parser.scala      — cats-parse tokenizer + pipeline grammar → Pipeline
  Renderer.scala    — pure render(value, opts) -> String
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
