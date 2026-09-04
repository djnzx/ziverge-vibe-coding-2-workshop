# Snap (Scala) — working agreement

Reference material lives in [`README.md`](README.md) (toolchain, layout,
commands, testing strategy) and [`AGENTS.md`](AGENTS.md) (module decomposition,
conventions, open questions). [`../SPEC.md`](../SPEC.md) is the canonical
behavioral contract — if the implementation and the spec disagree, the
implementation is wrong. The work itself is sequenced in
[`tracking/plan.md`](tracking/plan.md) and tracked in
[`tracking/progress.md`](tracking/progress.md); keep the checklist honest as
phases land.

## IRON RULE — docs move with the code

**Any change to an approach, convention, code-style recommendation, tool, or
version is not done until the docs that state it are updated in the same
change.** No follow-up commit, no "I'll note it later".

This includes: adding or removing a dependency, bumping a version, changing a
build or formatter setting, adopting a new testing technique, changing a naming
or module convention, or discovering that a documented command does not do what
it claims.

| Change                                                    | Update                    |
|-----------------------------------------------------------|---------------------------|
| toolchain, versions, commands, layout, testing strategy   | `README.md`               |
| how to work here — rules, discipline, what "done" means   | `CLAUDE.md`               |
| module decomposition, per-language conventions            | `AGENTS.md`               |
| behavior any implementation must match                    | `../SPEC.md` (say so out loud — it binds all three languages) |

A rule stated in more than one of these must be changed in **all** of them, or
they drift and the next reader believes the stale one. Correcting a documented
statement that turns out to be wrong is part of the fix, not a nicety — a doc
that lies is worse than no doc.

## IRON RULE — a reusable pattern becomes a skill

**When a pattern proves itself and would apply beyond the task at hand, offer to
capture it as a skill.** Offer — do not create one unilaterally.

A pattern qualifies when all three hold: it worked here with evidence; it would
apply to a different file, module, or project; and restating it from scratch
next time would cost real effort or get it wrong.

Skills live in `.claude/skills/<name>/SKILL.md` at the repository root. Three
already exist and apply directly here:

- `spec-implementation-planning` — turn `../SPEC.md` plus the repository state
  into an execution-ready plan and an evidence-based progress checklist.
- `property-testing-parsers` — the three axes every parser suite needs
  (round-trip, rejection, totality) and the source-text-paired-with-value
  generator pattern. Snap has four parsers to write: canonical versions (§3.2),
  `repository.json` (§4.1), configuration (§8), and the CLI grammar (§7).
- `mutation-checking-tests` — what to do when a batch of tests passes first try.

## IRON RULE — isolate bulky subtasks in subagents

**Spawn a subagent whenever a subtask's context should not pollute the main
thread.** Standing authorisation: do not stop to ask, and do not default to
doing everything inline because the harness warns against unrequested spawns —
that warning is overridden here.

Spawn when the *byproduct* dwarfs the *answer*: broad codebase sweeps, long
build or conformance logs read once for a verdict, exploratory reads of large
files answering one narrow question.

Do **not** spawn when the details themselves feed later reasoning, when the
agent would re-derive context you already hold (use `subagent_type: "fork"` if
it genuinely needs this conversation), or when the task is two tool calls.

The agent's report is not shown to the human — relay what matters in your own
words, and never invent results for an agent that has not reported back.

## How to work here

**Test-first, always.** Write the failing test, run it, confirm it fails for the
right reason, then write the minimum code that passes. A test that passes the
first time proves nothing about whether it can catch the bug it names.

**Reach for a property when the claim is universal.** Snap is unusually full of
universal claims, and SPEC §11 asks for them by name: join laws, the four-way
comparison exhausting its outcomes, `apply(diff(a, b), a) == b`, replay
convergence across import permutations, re-merge as a no-op. If the sentence you
would write starts "for *any*", it is a ScalaCheck property. If it is "for
*this* input, exactly these bytes", it is an MUnit example. Most behavior
deserves one of each.

**Every parser needs rejection properties, not just round-trips.** A property
that only says "valid input parses" is satisfied by a parser that accepts
everything — and Snap's spec is mostly about what to *refuse*: noncanonical
version ordering, explicit zeroes, unknown JSON fields, duplicate keys,
non-integer numbers, adjacent same-kind edit operations, scripts that do not
consume the whole old sequence, paths with `..` segments, misplaced CLI options.
Pair every grammar with properties naming what it must reject.

**Prove a property suite has teeth by mutating the code.** Break an invariant on
purpose, confirm the tests fail, revert. A mutation nothing catches means the
properties are decorative. Three are already recorded against the scaffold in
`README.md`; add one line there whenever a new invariant lands.

**Generators are the hard part in this codebase.** Two lessons already paid for:
`Arbitrary[String]` essentially never produces the characters where UTF-16 and
UTF-8 order disagree, so path properties need a code-point pool that straddles
the UTF-8 length boundaries; and permutation properties need a generator for
*valid causal patch graphs*, not for arbitrary patches.

**Both suites, every time.** `sbt "testOnly snap.*"` runs examples and
properties together. Do **not** use bare `sbt test`: sbt 2.0.8 routes it through
`testQuick`, which prints `No tests to run for Test / testQuick` and exits green
without running anything — and that record survives `clean`. Green on the unit
suite is necessary, not sufficient; `../verify --lang scala` is the contract
that ships.

## Context discipline

**Clear between phases, never inside one.** Each phase in
[`tracking/plan.md`](tracking/plan.md) opens with a **Start here** block naming
the spec text to read, the modules it builds on, and the acceptance cases it
moves, so a session can begin a phase cold. That is what makes clearing cheap.

It is only safe because the durable knowledge is on disk. A phase is not
finished until `tracking/progress.md` names its evidence and anything learned
along the way is in the doc that owns it — which the first IRON RULE already
requires. Write the discovery down *when you find it*, not at the end.

Do not clear mid-phase. Mid-debugging especially, the live reasoning is exactly
the part no file holds: "I assumed a test gap, wrote a case to expose it, and it
passed too, so the mutation is equivalent" is three steps that existed only in
context until the conclusion was written down.

One caveat to hold honestly: a session entering cold follows the plan more
literally, because it has no memory of why a step is worded as it is. It is
likelier to implement a wrong step faithfully than to notice the spec disagrees.
The spec outranks the plan — when they conflict, the plan is what changes.

## Non-negotiables

- No `return`, `asInstanceOf`, `isInstanceOf`, or `null` — WartRemover fails the
  build. Errors are `Either[SnapError, _]`; do not throw across module edges.
- Nothing reads the process cwd, the environment, or the clock outside the CLI
  edge. Commands take the repository root, the cwd, and the resolved
  configuration as parameters.
- Sort tracked paths with `Ordering[TrackedPath]` (unsigned UTF-8 bytes), never
  as `String`s. Compare file contents as `FileBytes`, never `Array[Byte]`.
- No `println` — its terminator is the platform's and SPEC §10 fixes LF. Print
  through `Streams` with an explicit `\n`.
- No escape sequence outside `Presentation.scala`, in `main` or in tests.
- **Validate completely before mutating** (§10). Build the target tree in
  memory, write working files, then replace `repository.json` through a
  same-directory temporary file. A validation failure must leave the repository
  and the working tree untouched — there are acceptance cases for exactly this.
- Do not add commands, options, or format fields beyond SPEC §7 and §4. Snap's
  small surface is the point (§12). If something is genuinely missing, change
  `../SPEC.md` first, say so, and add a public YAML case in the same change.
- `name`, `version`, and `assembly / assemblyOutputPath` in `build.sbt` are
  coupled to `../run` and `../run_tests`; `Main.scala`'s location is coupled to
  `../run`'s availability check. Do not retune either casually.

## Verification before claiming done

Run the commands and read the output before saying anything passes:

```bash
sbt "testOnly snap.*"
../verify --lang scala
```

A run that reports `Total 0` or `No tests to run` is not a pass. Read the count.

Then check the IRON RULES:

1. Did this change touch an approach, a convention, a version, or a documented
   command? The matching doc edit is part of *this* change, not the next one.
2. Did a pattern here prove itself and generalise beyond this task? Offer it as
   a skill before the conversation ends.
3. Is the next step bulky enough that its transcript would crowd out this one?
   Run it in a subagent.
