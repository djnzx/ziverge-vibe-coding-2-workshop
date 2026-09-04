# TabbyShell (Scala) — working agreement

Reference material lives in [`README.md`](README.md) (toolchain, layout,
commands) and [`AGENTS.md`](AGENTS.md) (module decomposition, conventions).
[`../SPEC.md`](../SPEC.md) is the canonical behavioral contract — if the
implementation and the spec disagree, the implementation is wrong.

## IRON RULE — docs move with the code

**Any change to an approach, convention, code-style recommendation, tool, or
version is not done until the docs that state it are updated in the same
change.** No follow-up commit, no "I'll note it later".

This includes: adding or removing a dependency, bumping a version, changing a
build or formatter setting, adopting a new testing technique, changing a naming
or module convention, or discovering that a documented command does not do what
it claims.

Where it goes:

| Change                                                    | Update                    |
|-----------------------------------------------------------|---------------------------|
| toolchain, versions, commands, layout, testing strategy   | `README.md`               |
| how to work here — rules, discipline, what "done" means   | `CLAUDE.md`               |
| module decomposition, per-language conventions            | `AGENTS.md`               |
| behavior any implementation must match                    | `../SPEC.md` (say so out loud — it binds all three languages) |

A rule that appears in more than one of these must be changed in **all** of
them, or they drift and the next reader believes the stale one. When a
documented statement turns out to be wrong, correcting it is part of the fix,
not a nicety — a doc that lies is worse than no doc. Two live examples: `sbt
test` was documented as the way to run the suite until it turned out to report
`No tests to run` and exit green; and a comment in `.scalafmt.conf` claimed
alignment was `:=` "and nothing else" one edit after that stopped being true.

## IRON RULE — a reusable pattern becomes a skill

**When a pattern proves itself and would apply beyond the task at hand, offer to
capture it as a skill.** Offer — do not create one unilaterally, and do not
silently let the pattern evaporate into a single conversation.

A pattern qualifies when all three hold:

- it worked here, with evidence, not just in principle
- it would apply to a different file, module, or project — not only this one
- restating it from scratch next time would cost real effort or get it wrong

Say what the skill would cover and what it would *not*, in one or two lines, and
let the human decide. A declined offer costs a sentence; an uncaptured pattern
costs the next person the whole rediscovery.

Skills live in `.claude/skills/<name>/SKILL.md` at the repository root. Two
already exist and apply directly to this workspace:

- `property-testing-parsers` — the three axes every parser suite needs (round-trip,
  rejection, totality), the source-text-paired-with-value generator pattern, and
  grammar-fragment fuzzing. Read it before testing the JSON, CSV, CLI, or
  line-continuation parsers still to be written.
- `mutation-checking-tests` — what to do when a batch of tests passes first try.

`superpowers:writing-skills` and `skill-creator` scaffold new ones.

## IRON RULE — isolate bulky subtasks in subagents

**Spawn a subagent whenever a subtask's context should not pollute the main
thread.** This is standing authorisation: do not stop to ask first, and do not
default to doing everything inline because the harness warns against unrequested
spawns — that warning is overridden here.

Spawn when the *byproduct* dwarfs the *answer*:

- broad codebase sweeps where you need the conclusion, not the file dumps
- long build, test, or conformance logs read once to extract a verdict
- skill baseline testing — pressure-scenario transcripts are pure noise afterwards
- exploratory reads of large files to answer one narrow question
- anything you would otherwise skim and immediately want to forget

Do **not** spawn when:

- the details themselves feed later reasoning — an edit you must get exactly right
- the agent would re-derive context you already hold, and pay more than it saves
  (use `subagent_type: "fork"` when it genuinely needs this conversation)
- the task is a couple of tool calls; one `grep` is not a subtask

The agent's report is not shown to the human — relay what matters, in your own
words, and never invent results for an agent that has not reported back.

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
- Never hand-write an ANSI escape, in `main` or in tests — fansi owns them, and
  test expectations are built with fansi so they survive a library change.
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

Then check the IRON RULES:

1. Did this change touch an approach, a convention, a version, or a documented
   command? The matching doc edit is part of *this* change, not the next one.
2. Did a pattern here prove itself and generalise beyond this task? Offer it as
   a skill before the conversation ends.
3. Is the next step bulky enough that its transcript would crowd out this one?
   Run it in a subagent.
