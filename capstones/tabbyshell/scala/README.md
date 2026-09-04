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
| whitespace insensitivity          | extra spaces, tabs or newlines never change the AST       |
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
| continuation round-trip           | segments rendered with `\` rejoin to exactly those segments |
| continuation fixpoint             | a script with no `\` splits into its physical lines       |
| trailing newline                  | does not add an empty logical line                        |
| logical lines are complete        | no line handed to the parser still awaits a join          |
| continued pipeline                | parses identically to the same pipeline on one line       |

Totality only says the parser returns *something*. A second group says what it must
**refuse** — a parser that accepted every input would satisfy totality and fail all of
these:

| Rejection property                | Must be a parse error                                     |
|-----------------------------------|-----------------------------------------------------------|
| unterminated string               | `x "abc`, `x 'abc`                                        |
| unsupported escape                | `x "a\\qb"` — only `\\" \\\\ \\n \\t` are escapes                     |
| pipe with a missing operand       | trailing `|`, leading `|`, `… | | length`                  |
| character outside the grammar     | any of `&;$(){}[]@^*+,:?!\`` appended to a valid pipeline  |
| token not ended                   | `5abc` — a literal may not run into ident characters      |
| unknown filesize unit             | `5tb`, `5q`, `5bb` — only the 7 SPEC units parse          |
| valued flag with no literal       | `--name=`                                                 |
| bare double dash                  | `--`                                                      |
| SPEC §3.3 message shape           | every rejection matches `parse error: … at column <n>`    |
| rejection column bounds           | every rejection points inside its input                   |

Generators emit a token's **source text together with the `Arg` it must parse
to**, so a whole pipeline can be generated next to its expected AST. That is
what makes the round-trip property possible without a separate pretty-printer.

The totality and out-of-range properties are not decoration: they caught a real
`NumberFormatException` on `x 99999999999999999999`, which is now a parse error
(and is pinned by SPEC §3.1).

These are checked by mutation, not taken on faith. Removing the `tokenEnd` guard from
`Parser.scala` (so a literal may run into ident characters) is caught by 11 properties,
4 of them rejection properties; joining continued lines without their newline is caught
by 5; keeping the trailing empty field from the line split is caught by 2. A property suite that survives a mutation like that is
not testing anything.

Both techniques are written up as repository skills, and apply to the parsers still to
come: `.claude/skills/property-testing-parsers/` and
`.claude/skills/mutation-checking-tests/`.

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

## Keeping the docs honest

**IRON RULE: any change to an approach, convention, code-style recommendation,
tool, or version is not done until the docs that state it are updated in the
same change.**

- `README.md` (this file) — toolchain, versions, commands, layout, testing strategy
- [`CLAUDE.md`](CLAUDE.md) — the working agreement: how to work here, what "done" means
- [`AGENTS.md`](AGENTS.md) — module decomposition and per-language conventions
- [`../SPEC.md`](../SPEC.md) — behavior binding all three implementations; change deliberately

A rule stated in more than one of these must be changed in all of them.
Correcting a documented statement that turns out to be wrong is part of the fix,
not an optional tidy-up: a doc that lies costs more than no doc at all.

**IRON RULE: a pattern that proves itself and generalises gets offered as a
skill.** If a technique worked here with evidence, would apply beyond this
project, and would be costly to re-derive, propose capturing it in
`.claude/skills/<name>/SKILL.md` rather than leaving it in one conversation.
Propose it — the decision to add a skill is the human's.

**IRON RULE: bulky subtasks run in subagents.** When a subtask's byproduct dwarfs
its answer — broad code sweeps, long conformance logs, exploratory reads — run it
in a subagent so the transcript stays out of the main thread. Not for work whose
details feed later reasoning, and not for two tool calls.


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
