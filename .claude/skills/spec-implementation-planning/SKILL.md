---
name: spec-implementation-planning
description: Analyze a repository against its canonical specification and turn the gap into an execution-ready implementation plan plus evidence-based progress checklist. Use for spec-driven application work, not for a small isolated code change.
---

# Spec-Driven Implementation Planning

## Outcome

Produce two project-local artifacts:

- `tracking/plan.md`: the implementation sequence, detailed enough that another
  engineer can execute it without rediscovering requirements.
- `tracking/progress.md`: a compact, evidence-based checklist that mirrors the
  plan and distinguishes completed work from code that merely exists.

The canonical specification wins over code, tests, README text, and prior plans.
Treat the plan as a delivery contract, not a narrative summary.

## Before Writing

1. Find and read the canonical spec in full. Read applicable repository and
   subtree instructions before inspecting or changing the plan.
2. Inventory the requested implementation area and its tests. Also inventory
   public/conformance tests, build entry points, launchers, and existing
   `tracking` files when present.
3. Build a requirement-to-evidence map. For every significant spec section,
   record one of: implemented with evidence, partial/incorrect with a concrete
   gap, or absent. Source-file presence alone is never evidence of completion.
4. Preserve unrelated work in a dirty tree. Planning does not authorize source,
   dependency, test, configuration, or external-system changes.

If the specification and existing code disagree, plan the specification's
behavior. If a material choice cannot be derived from the spec, codebase, or
tests, ask one concise question before choosing. Otherwise state a narrow
assumption in the plan and continue.

## Build the Plan

Order work by dependency, keeping deterministic pure layers ahead of filesystem,
process, network, and terminal boundaries. A useful default sequence is:

1. Domain contracts, invariants, and user-facing errors.
2. Parser and argument provenance/validation.
3. Pure codecs, transformations, and rendering.
4. Stateful filesystem or persistence boundaries.
5. Command registry and pipeline/execution orchestration.
6. Process/network adapters with injectable boundaries and failure fallback.
7. CLI, REPL, and terminal adapters at the application edge.
8. Assembly, conformance verification, and manual smoke tests.

Adapt that sequence when the repository's dependency graph requires it; do not
force layers that do not exist in the specification.

For every phase, include:

- **Goal and SPEC references** — identify the binding clauses, not just a
  feature name.
- **Starting status** — implemented, partial, or absent, with source/test
  evidence and no optimistic inference.
- **Ordered implementation steps** — name the target file or module, public
  contract, state flow, error conversion, and data invariant to preserve.
- **Tests before completion** — focused examples, useful properties, and any
  public/conformance cases the phase exercises. Include deterministic fakes for
  time, filesystem, process, network, or terminal boundaries when relevant.
- **Exit criterion** — observable behavior and the exact verification command
  or harness; a unit-suite pass alone is insufficient when the application has
  a packaged or public interface.

Be explicit about seams that commonly fail in integration: argument lexical
provenance, state propagation across pipelines, color/time/environment
initialization, raw-versus-rendered serialization, error text/exit codes, and
whether terminal output is intentionally outside the executor.

## Write `tracking/plan.md`

Keep an existing useful plan, but reconcile stale claims against the current
tree. Include, at minimum:

1. Scope, canonical sources, and non-goals.
2. A dated implementation snapshot with test/build evidence.
3. Numbered phases in execution order, each with the five elements above.
4. A final verification phase covering formatter, complete suite, packaging,
   public harness, and manual behavior that the harness cannot observe.
5. A short rationale for ordering only when it changes implementation choices.

Use completed markers only when there is named evidence. Phrase pending work as
actions, not vague intentions (for example, "inject an HTTP client and convert
all response-schema failures to the specified fallback" rather than "add AI").

## Write `tracking/progress.md`

Create a short live ledger rather than a duplicate of the plan.

- Make each checkbox independently verifiable and align it to one plan step or
  exit criterion.
- Mark an item complete only beside concrete evidence: a named test suite,
  verifier case, assembly command, or manual check with a date.
- Keep partially implemented modules unchecked; add a brief status note if it
  prevents a false completion claim.
- Separate phase work from final verification. Include packaging and public
  conformance as unchecked until actually run.
- Update old test totals or baseline claims when the current repository proves
  they are stale.

## Finish

Review both files for consistency: every progress item must be traceable to a
plan phase, every completed item must name evidence, and no planned work should
contradict the canonical spec. Check the documentation diff for malformed links,
accidental source edits, and misleading completion statements. Then report the
artifacts created or updated, the highest-priority next phase, and only genuinely
blocking questions.
