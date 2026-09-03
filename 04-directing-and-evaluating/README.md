# Module 04 — Directing and Evaluating

*Advanced prompting, planning, and eval-driven improvement.*

Modern coding agents are capable enough that the hard problem is no longer
writing a clever prompt. It is directing a long-running system: turning intent
into an executable contract, supplying the right evidence at the right time,
setting safe autonomy boundaries, and evaluating both the result and the path
that produced it.

This module follows one improvement loop:

```text
specify → context → plan → execute → evaluate → revise
```

The directory is intentionally README-only while its exercises are being
designed. The final module will cover these topics in order:

1. **Turn intent into an executable specification.** Define the goal, scope,
   constraints, acceptance criteria, and unresolved questions so the agent has
   a contract rather than a vague request.
2. **Set autonomy and approval boundaries.** Match permissions, checkpoints,
   and escalation rules to reversibility and risk instead of choosing only
   between manual control and full autonomy.
3. **Engineer context deliberately.** Treat context as a finite working set:
   select authoritative evidence, reveal it progressively, and prevent stale or
   conflicting information from crowding out the task.
4. **Build evidence-seeking plans.** Make plans identify unknowns,
   dependencies, verification evidence, and replanning triggers—not merely
   predict a sequence of edits.
5. **Delegate without fragmenting ownership.** Give subagents bounded outcomes,
   enough local context, and explicit return contracts while keeping synthesis
   and integration under one accountable owner.
6. **Turn “done” into executable evals.** Convert acceptance criteria into
   deterministic checks, scenario tests, rubrics, and adversarial probes before
   trusting a completion claim.
7. **Run trustworthy experiments.** Compare prompts and workflows against fixed
   cases, baselines, repeated trials, and recorded conditions so improvements
   are distinguishable from variance.
8. **Inspect outcomes and trajectories.** Evaluate the delivered artifact and
   the agent's tool-use path to find lucky passes, wasted exploration,
   unsupported claims, and unsafe actions.
9. **Audit LLM judges.** Calibrate judge prompts against human labels, control
   for ordering and style bias, require evidence, and keep deterministic checks
   authoritative where possible.
10. **Close the improvement loop.** Classify failures by specification,
    context, planning, execution, or evaluation; then change the smallest part
    of the system and rerun the same evals.

The goal is not a universal prompt template. It is a repeatable engineering
practice for directing capable agents and producing evidence that their work is
ready to own.

## Research basis

The module outline reflects current guidance on explicit task contracts,
context management, bounded delegation, eval design, trajectory inspection,
and judge calibration:

- [OpenAI prompt guidance](https://developers.openai.com/api/docs/guides/prompt-guidance)
- [Anthropic: Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Anthropic: Claude Code expertise](https://www.anthropic.com/research/claude-code-expertise)
- [ACL 2026: Agent evaluation research](https://aclanthology.org/2026.acl-long.335/)
- Recent preprints on [coding-agent evaluation](https://arxiv.org/html/2602.16666v3),
  [trajectory-aware evaluation](https://arxiv.org/html/2605.12925v1), and
  [LLM-judge reliability](https://arxiv.org/html/2606.22329v1)

The preprints are included as emerging evidence, not settled consensus. Their
claims should be revisited as peer-reviewed results and stronger replications
become available.
