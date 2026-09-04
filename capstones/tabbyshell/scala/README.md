# TabbyShell — Scala

A Scala 3 implementation of [TabbyShell](../README.md). Behavior is defined by
[`SPEC.md`](../SPEC.md); this document covers only how the Scala workspace is
built, tested, and organised.

## Toolchain

| Component  | Version | Notes                                             |
|------------|---------|---------------------------------------------------|
| sbt        | 2.0.8   | pinned in `project/build.properties`              |
| Scala      | 3.9.0   | `ThisBuild / scalaVersion`                        |
| JDK        | 21+     | verified on 25 and 26                             |
| cats-parse | 1.1.0   | tokenizer, pipeline grammar, JSON                 |
| MUnit      | 1.3.6   | example-based tests                               |
| ScalaCheck | 1.20.0  | property-based tests, via munit-scalacheck 1.3.1  |

Production code otherwise uses only the JDK standard library. There is no JSON
library: JSON is parsed with cats-parse and emitted by hand per SPEC §5.13.

## Layout

```
build.sbt
project/
  build.properties           sbt 2.0.8
  plugins.sbt                sbt-assembly, sbt-scalafmt, sbt-wartremover
src/main/scala/tabbyshell/   implementation (package `tabbyshell`)
src/test/scala/tabbyshell/   tests, mirroring the implementation
```

## Build and run

```bash
sbt compile
sbt assembly                                  # fat JAR
sbt "run --eval \"ls | first 3\""

../run --lang scala --eval "ls | first 3"     # via the shared launcher
../tabby --lang scala                         # interactive REPL
```

`assembly` writes `target/scala-3.9.0/tabbyshell-assembly-0.1.0.jar`. That path
is pinned in `build.sbt`: sbt 2 defaults to `target/out/jvm/...`, which neither
`../run` nor `../test-harness/src/runner.ts` scans. Changing `name`, `version`,
or `assemblyOutputPath` breaks both.

## Testing

Three layers, in increasing scope.

### 1. Example-based unit tests (MUnit)

```bash
sbt "testOnly tabbyshell.*"            # the whole suite, examples + properties
sbt "testOnly tabbyshell.ParserTest"   # one suite
```

> **`sbt test` is not the full suite here.** sbt 2.0.8 routes `test` through
> `testQuick`, which skips suites it considers unchanged and prints
> `No tests to run for Test / testQuick` — and that record survives `clean`.
> Run the suite through `testOnly`, which always executes.

Written test-first: each behavior gets a failing test before the code that
satisfies it. These pin down concrete, quotable cases — the exact AST for
`ls | where size > 0b`, the exact message for a parse error.

### 2. Property-based tests (ScalaCheck)

```bash
sbt "testOnly tabbyshell.ParserPropertyTest"
```

Properties cover the invariants that examples can only sample. Use them where a
statement holds across a *family* of inputs; keep an example test alongside for
the specific case you want documented.

Current parser properties:

| Property                          | Invariant                                                 |
|-----------------------------------|-----------------------------------------------------------|
| generated pipeline round-trips    | source text built from an AST parses back to that AST     |
| whitespace insensitivity          | extra spaces/tabs between tokens do not change the AST    |
| operators need no whitespace      | `size>0b` parses identically to `size > 0b`               |
| trailing comment                  | `# …` appended to a pipeline never changes the AST        |
| integer round-trip                | every `Long` survives text → `Literal.Int`                |
| float round-trip                  | every generated decimal survives text → `Literal.Float`   |
| double-quoted string round-trip   | every string survives escaping → quoting → parsing        |
| single-quoted string is raw       | no escape processing inside `'…'`                         |
| filesize scaling                  | `<n><unit>` equals `n × multiplier` for all 7 units       |
| negative filesize scaling         | negative magnitudes scale like positive ones              |
| valued long flag round-trips      | `--name=<literal>` yields `Flag(name, Some(literal))`     |
| keyword boundary                  | `nullish` is one ident, never `null` + `ish`              |
| out-of-range number / filesize    | too large for `Int64` is a parse error, never a throw     |
| totality                          | arbitrary input yields `Either`, never an exception       |
| totality under grammar fuzz       | same, for strings built from grammar fragments            |
| error column bounds               | a reported column is always within the input              |

Generators emit a token's **source text together with the `Arg` it must parse
to**, so a whole pipeline can be generated next to its expected AST. That is
what makes the round-trip property possible without a separate pretty-printer.

The totality and out-of-range properties are not decoration: they caught a real
`NumberFormatException` on `x 99999999999999999999`, which is now a parse error
(and is pinned by SPEC §3.1).

Two generators are worth knowing about. `fuzz` builds strings from *grammar
fragments* (`"`, `\\`, `|`, `#`, `5kb`, `999999999999999999999`, …) rather than
arbitrary characters, because `Arbitrary[String]` essentially never produces an
unbalanced quote or an oversized numeric literal. And properties that compare two
parses assert `isRight` on one side, so they cannot pass vacuously by having both
sides fail.

### Parsers still to come

Each gets its properties when it is written, test-first:

| Parser                       | SPEC       | Natural properties                                  |
|------------------------------|------------|-----------------------------------------------------|
| line-continuation pre-pass   | §3.1, §7.3 | joining is associative; a line without `\\` is a fixpoint |
| JSON (`open *.json`, AI)     | §5.2, §5.15| `Value` → `to json` → parse round-trips              |
| CSV (`open *.csv`)           | §5.2       | `Table` → `to csv` → parse round-trips; RFC 4180 quoting |
| CLI arguments                | §8         | flag order is irrelevant; unknown flags are rejected |

### 3. Cross-language conformance (YAML)

```bash
../verify --lang scala --implementation-root .
```

Builds the assembly and runs the 50-case public suite in `../tests/` against
it. This is the contract shared with the TypeScript and Rust implementations;
a green unit suite does not imply conformance.

## Style

```bash
sbt scalafmtAll
```

WartRemover fails the build on `return`, `asInstanceOf`, `isInstanceOf`, and
`null`. Errors are values (`Either[ShellError, _]`), not exceptions.

## Deviations from `ts/src/contracts.ts`

The TypeScript scaffold's public model does not cover all of SPEC §3.1. Two
additions, neither observable in rendered output, so cross-language parity
(SPEC §10) is unaffected:

- `Literal.Null` — the grammar has `literal := … | 'null'`; the TypeScript union
  has no null member.
- `Arg.Flag.value: Option[Literal]` — the grammar has
  `flag := '--' IDENT ('=' literal)?`; the TypeScript `Arg` has no slot for the
  value. No v1 builtin uses the valued form.

`Literal` also splits `Int`/`Float` where TypeScript folds both into `number`,
because `5` parses as `Int` and `5.0` as `Float`.
