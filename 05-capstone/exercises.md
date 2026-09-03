# Module 05 capstone exercise

One exercise: **choose one capstone and deliver it end-to-end**.

## Step 1 — Choose the project and language

Browse [`capstones/`](../capstones/) and choose a project. Read its README for
the product goal, prerequisites, expected difficulty, and exact verification
command. Then identify the one language workspace included in your archive:

```text
capstones/<project>/<language>/
```

Maintainers keep three reference solutions to prove language parity; those
solutions are not included in attendee archives.

## Step 2 — Read the contract

Read `capstones/<project>/SPEC.md` and its public tests end to end. Record exact
contracts, ambiguous requirements, environmental assumptions, and behavior the
tests do not cover. This turns a large prompt into evidence-backed constraints
before an agent starts inventing details.

## Step 3 — Plan

Use the Module 04 practices to write an evidence-seeking plan. It should state
the goal and scope, preserve the contract's constraints, identify unknowns and
dependencies, define verification evidence for each meaningful step, and name
conditions that require replanning. Revise it if it merely restates the spec or
predicts edits without saying how you will know they are correct.

## Step 4 — Build

Direct your coding agent to implement the plan in the selected language only:

```bash
./z --lang <language> --work-dir capstones/<project>
```

Give it the goal, constraints, relevant evidence, and verification commands.
Use higher autonomy for reversible, well-tested work. Require approval around
external effects, security boundaries, ambiguous contracts, and irreversible
changes. Inspect evidence and replan when an assumption fails instead of asking
the agent to force the original plan through.

## Step 5 — Verify

Run the command documented by the selected project. The two verification
interfaces are intentionally different:

```bash
# TabbyShell
./capstones/tabbyshell/verify --lang <language> \
  --implementation-root capstones/tabbyshell/<language>

# Snap
./capstones/snap/verify --lang <language>
```

Snap can also verify an independently built executable with
`./capstones/snap/verify --candidate PATH`. Do not pass an implementation root
to Snap's verifier.

Then review the generated code and probe behavior the public suite does not
cover. Look specifically for test over-fitting, hidden environmental
assumptions, accidental network access, and success claims without evidence.

## Step 6 — Reflect

Answer the questions in [`README.md`](README.md#reflection). Focus on how the
delegation system shaped the outcome, not merely on whether the program works.
