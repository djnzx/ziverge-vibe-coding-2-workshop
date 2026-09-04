# TabbyShell (Scala) — working agreement

Reference material lives in [`README.md`](README.md) (toolchain, layout,
commands) and [`AGENTS.md`](AGENTS.md) (module decomposition, conventions).
[`../SPEC.md`](../SPEC.md) is the canonical behavioral contract — if the
implementation and the spec disagree, the implementation is wrong.

## How to work here

**Test-first, always.** Write the failing test, run it, confirm it fails for the
right reason, then write the minimum code that passes. A test that passes the
first time proves nothing about whether it can catch the bug it names.

**Reach for a property when the claim is universal.** If the sentence you would
write is "for *any* X, …", it is a ScalaCheck property, not an example. If it is
"for *this* input, exactly this output", it is an MUnit example. Most behavior
deserves one of each: the property for the invariant, the example to pin the
case a reader will look up.

**Every parser needs rejection properties, not just round-trips.** A property that
only says "valid input parses correctly" is satisfied by a parser that accepts
everything. Pair each grammar with properties naming what it must *refuse*, and one
meta-property that every rejection carries the SPEC §3.3 message shape.

**Prove a property suite has teeth by mutating the code.** Break an invariant on
purpose, confirm properties fail, revert. A mutation nothing catches means the
properties are decorative.

Good candidates for properties in this codebase:

- round-trips — source text → AST → source text; `Value` → `to json` → `open`
- totality — a parser or renderer returns a value for *every* input, never throws
- algebraic laws — `first n` then `length` ≤ `n`; `sort-by` is stable and is a
  permutation of its input; `select` preserves row count
- scaling and formatting — filesize units, date buckets across the whole range
- byte-exactness — `render(color = false)` never emits an ESC byte

Generators that emit **source text alongside the value it must parse to** are the
pattern that makes round-trip properties work without a pretty-printer; see
`ParserPropertyTest`.

**Both suites, every time.** `sbt "testOnly tabbyshell.*"` runs examples and
properties together. Do **not** use bare `sbt test`: sbt 2.0.8 routes it through
`testQuick`, which prints `No tests to run for Test / testQuick` and exits green
without running anything — and that record survives `clean`. Green on the unit
suite is necessary, not sufficient; `../verify --lang scala
--implementation-root .` is the contract that actually ships.

## Non-negotiables

- No `return`, `asInstanceOf`, `isInstanceOf`, or `null` — WartRemover fails the
  build. Errors are `Either[ShellError, _]`; do not throw across module edges.
- The renderer takes `now` from `RenderOpts`; builtins take `cwd` from
  `ShellState`. Neither reads the clock or the process cwd directly — that is
  what makes cross-language output byte-identical.
- Project assets (`banner.txt`) resolve from `TABBY_PROJECT_ROOT`, never from a
  guess based on cwd or JAR location.
- Do not add commands or grammar beyond SPEC §3 and §5. If something is genuinely
  missing, change `SPEC.md` first and say so — it is a three-language contract.
- `name`, `version`, and `assembly / assemblyOutputPath` in `build.sbt` are
  coupled to `../run` and `../test-harness/src/runner.ts`. Do not retune them
  casually.

## Verification before claiming done

Run the command and read the output before saying anything passes:

```bash
sbt "testOnly tabbyshell.*"
../verify --lang scala --implementation-root .
```

A run that reports `Total 0` or `No tests to run` is not a pass. Read the
count.
