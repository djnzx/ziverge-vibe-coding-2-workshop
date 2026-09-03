# Module 05 capstone — choose your project

Use the agentic coding system and discipline from Modules 01–04 to deliver one
complete project. Browse the project directories named in the
[`capstones/projects`](../capstones/projects) catalogue and read each project's
README before choosing.

The two selectable projects are **TabbyShell**, a typed interactive shell, and
**Snap**, a vector-clock version control system. Both provide TypeScript, Rust,
and Scala scaffolds and use language-neutral YAML acceptance suites.

Additional projects can be added to the catalogue without changing the shared
capstone workflow. Your attendee archive contains every available project, but
only the implementation language selected for your cohort.

## Before you begin

- **Ownership:** If it ships, you own it. “The AI wrote that” is not a defense.
- **Verification discipline:** Prefer automated evidence—tests, evals, linters,
  and type checks—over an agent's success claim or a casual manual review.
- **Blame the system, not the model:** When delegation fails, improve the task,
  context, boundaries, or guardrails that allowed the failure.

## The exercise

1. Choose one project from the catalogue.
2. Read its `README.md`, `SPEC.md`, and public tests before planning.
3. Implement only the language workspace present in your archive.
4. Use the project's `verify` command as a necessary—not sufficient—quality
   gate, then review and probe the result yourself.

The project specification defines behavior. Its public acceptance suite defines
the observable completion bar. Neither replaces engineering judgment: tests
can omit behavior, and an agent can overfit to them.

See [`exercises.md`](exercises.md) for the shared workflow and the selected
project's README for its exact build and verification commands.

## Reflection

After you finish—or run out of time—write down:

1. Where did the agent succeed without intervention?
2. Where did you intervene, and what signal prompted it?
3. Where should you have intervened but did not? What did verification miss?
4. What would you change in the task, context, or guardrails next time?

There is no cross-project scoreboard. Different projects may exercise different
skills or difficulty levels; the goal is calibrated delegation and ownership.
