# Ziverge Agentic Coding Training — Levels I-II

Hands-on materials for a two-day training on agentic coding — using AI agents to accelerate engineering work without sacrificing code quality. The course takes a build-to-understand approach: participants implement a coding agent from scratch in TypeScript, Rust, or Scala, then progressively layer on context engineering, safety guardrails, advanced direction, planning, and evaluation. The goal is not to demo tools but to develop the judgment and mental models needed to delegate confidently and own everything that ships.

## Prerequisites

- Working development environment (editor, terminal, git)
- Familiarity with at least one of: TypeScript, Rust, or Scala
- Node.js installed (required for the workshop test harnesses)
- No prior experience with AI coding tools required

## Start Here

Open [`01-foundations/`](01-foundations/README.md) — it has setup commands, language options, and links to the exercises.

## Repository Layout

```
z                       ← Run your agent (auto-detects by default; use --lang ts|rust|scala to force)
01-foundations/          ← Module instructions and exercises (start here)
02-context-engineering/  ← Module 02 instructions (Day 1 PM)
03-guardrails-and-safety/ ← Module 03 instructions (Day 1 PM late)
04-directing-and-evaluating/ ← Module 04 outline (Day 2 AM)
05-capstone/            ← Module 05 instructions (Day 2 PM)
capstones/              ← TabbyShell and Snap projects, public tests, and language workspaces
agent/                  ← Code you edit in Modules 01–03
  ts/                      TypeScript implementation
  rust/                    Rust implementation
  scala/                   Scala implementation
  system-prompt.txt        Prompt template used by Exercise 1
  test-harness/            YAML-driven integration test runner
  tests/                   Integration test definitions
```


## Schedule

The repository follows the five-block training schedule:

| Folder | Block | Focus |
|---|---|---|
| `01-foundations` | Day 1 AM | Build a toy coding agent from scratch to develop a mental model of agent internals — agent loop, tool use, system prompts, LLM behavior, model selection — so you can delegate with judgment and catch failures early |
| `02-context-engineering` | Day 1 PM early | Control what the agent knows and remembers — persistent instructions, context management, skills, path rules, compaction, state persistence — so context quality drives delegation quality |
| `03-guardrails-and-safety` | Day 1 PM late | Constrain what the agent can do — sandboxing, permissions, tool decorators, secret scanning, output validation, audit, rollback — so you stay in control of risk |
| `04-directing-and-evaluating` | Day 2 AM | Direct capable coding agents with executable specifications, deliberate context, evidence-seeking plans, bounded delegation, trustworthy evals, trajectory review, and calibrated judges |
| `05-capstone` | Day 2 PM | Full integration: triage, plan, delegate, review, validate. Ownership and professional practice |
