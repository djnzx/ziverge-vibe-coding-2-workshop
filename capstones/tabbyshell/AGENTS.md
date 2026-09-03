# TabbyShell — agent guidance

Work in the language directory present at the project root. The shared spec,
banner, fixtures, and YAML test suite define the contract.

## Source of truth

[`SPEC.md`](SPEC.md) is the canonical spec. If an implementation disagrees
with the spec, the implementation is wrong. If a test in `tests/` disagrees
with the spec, raise it explicitly — do not silently change either.


## Determinism rules

The renderer's color-off output must be byte-identical across implementations.
Re-derive any time-dependent or environment-dependent value from the
`ShellState` (specifically `state.now` and `state.cwd`) — never call
`now()`/`getenv()` deep in the renderer or builtins.

For tests, set `TABBY_NOW=<unix-seconds>` to freeze the clock.
Runtime project assets are resolved from the explicit `TABBY_PROJECT_ROOT`
provided by `run`, `verify`, and the harness—never by guessing from cwd or the
binary's nesting depth.

## File organization (per language)

Each language follows the same logical decomposition:

```
value     — the Value ADT and its smart constructors
parser    — tokenizer + recursive-descent parser → Pipeline AST
renderer  — pure render(value, opts) -> String
builtins  — one function per command, plus the dispatch table
executor  — pipeline interpreter; threads ShellState, dispatches builtins
            or routes to the AI external fallback
ai        — OpenRouter HTTP client for external command formatting
repl      — readline loop, banner, history, line continuation
terminal  — small abstraction for stdout/stderr/prompt (mirrors agent/)
main      — entry point, CLI parsing, mode dispatch
```

The participant contract is `SPEC.md`, fixtures, and the YAML harness.

## Don't add features beyond the spec

The 14 commands and the grammar in `SPEC.md` are the full feature set.
Resist adding `each`, `group-by`, variables, `$in`, etc. — the small surface
is the point. If we discover a missing feature is required for the workshop,
update `SPEC.md` first, then propagate to all three implementations and the
test suite.
