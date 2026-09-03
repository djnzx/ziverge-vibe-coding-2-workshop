# Curriculum Overview

## 01 — Foundations (Day 1 AM)
*Build the agent. Develop a mental model.*

| # | Topic |
|---|---|
| 1 | Agent internals: system prompt → LLM call → parse → execute → feed back → repeat |
| 2 | System prompts: design determines behavior, iterate to fix |
| 3 | Tool grounding: tools bridge text to actions, essential for correctness |
| 4 | Fluency ≠ correctness: flat-format calls, hallucinated results, multi-action |
| 5 | Context windows: finite context constrains behavior, must truncate and manage |
| 6 | Model selection and tuning: choosing models for capability/cost/speed, temperature and sampling |
| 7 | LLMs don't think: pattern mining, not reasoning (Sudoku, fabrication) |

## 02 — Context Engineering (Day 1 PM early)
*Make the agent context-aware.*

| # | Topic |
|---|---|
| 1 | Persistent instructions: AGENTS.md configures behavior per-project |
| 2 | Context management: what to include, what to exclude, measuring usage, detecting stale or conflicting content |
| 3 | Knowledge injection: skills, reference docs loaded into context |
| 4 | Path-specific rules: inheritance, agent adapts to codebase location |
| 5 | Compaction: compressing conversation history to free context space |
| 6 | State persistence: survive across sessions (save/load) |

## 03 — Guardrails and Safety (Day 1 PM late)
*Constrain what the agent can do.*

| # | Topic |
|---|---|
| 1 | Sandboxing: directory restrictions, containers — the environment the agent operates in |
| 2 | Permissions: operator control over destructive actions |
| 3 | Tool decorators: wrap tools with validation, confirmation, policy enforcement |
| 4 | Secret scanning: prevent credential leakage into files |
| 5 | Output validation: check agent output before piping to shells, configs, deployment |
| 6 | Audit logging: record what the agent did for post-hoc review |
| 7 | Rollback and recovery: git worktrees, checkpoints when guardrails fail |

## 04 — Directing and Evaluating (Day 2 AM)
*Advanced prompting, planning, and eval-driven improvement.*

| # | Topic |
|---|---|
| 1 | Turn intent into an executable specification: goals, scope, constraints, acceptance criteria, and unresolved questions |
| 2 | Set autonomy and approval boundaries according to reversibility and risk |
| 3 | Engineer context deliberately as a finite, authoritative working set |
| 4 | Build evidence-seeking plans with unknowns, dependencies, verification, and replanning triggers |
| 5 | Delegate bounded outcomes without fragmenting ownership or integration responsibility |
| 6 | Turn “done” into executable evals: deterministic checks, scenarios, rubrics, and adversarial probes |
| 7 | Run trustworthy prompt and workflow experiments with fixed cases, baselines, repetitions, and recorded conditions |
| 8 | Inspect outcomes and tool-use trajectories for lucky passes, waste, unsupported claims, and unsafe actions |
| 9 | Audit LLM judges for calibration, ordering and style bias, and unsupported verdicts |
| 10 | Close the improvement loop by classifying failures and changing the smallest responsible system component |

## 05 — Capstone (Day 2 PM)
*Full integration + professional practice.*

| # | Topic |
|---|---|
| 1 | Ownership: if it ships, you own it |
| 2 | Verification discipline: automated tests, evals, linters first; manual review second; measure acceptance rates |
| 3 | Blame the system, not the model: failures are in planning, context, or guardrails |
