# TabbyShell — TypeScript implementation

## Setup

```
npm install
```

## Build / type-check

```
npx tsc --noEmit
```

## Run

```
npx tsx src/main.ts                      # interactive REPL
npx tsx src/main.ts --eval "ls | first 3"
npx tsx src/main.ts --eval-file script.tab
echo "ls | first 3" | npx tsx src/main.ts --eval-file -
```

## Test

The language-neutral verifier is the public test suite. From the repository
root after packaging:

```
./capstones/tabbyshell/verify --lang ts \
  --implementation-root capstones/tabbyshell/ts
```

## Dependencies

Only dev: `tsx`, `typescript`. Production code uses **only** Node built-ins
(`node:fs`, `node:path`, `node:os`, `node:readline`, `node:child_process`,
`node:fetch` global). No npm runtime dependencies.

## Target file structure

The scaffold starts with `main.ts` and `contracts.ts`. Create the following
modules as you implement the specification; the names are a recommended
decomposition, not pre-existing files:

```
src/
  value.ts      — Value ADT (discriminated union by `kind`) + smart constructors
  parser.ts     — tokenizer + recursive-descent parser → Pipeline AST
  renderer.ts   — pure render(value, opts) -> string
  builtins.ts   — one function per command + dispatch table
  executor.ts   — pipeline interpreter + AI-external fallback dispatch
  ai.ts         — OpenRouter HTTPS POST + JSON response parser
  repl.ts       — readline loop, banner, history, line continuation
  terminal.ts   — small abstraction for stdout/stderr/prompt
  main.ts       — CLI parsing (--eval / --eval-file / --no-color / --version)
```

## Conventions

- Use discriminated unions; never `any`.
- Use `node:` prefix for all built-in imports.
- Renderer must not call `Date.now()` — read `now` from `RenderOpts`.
- Builtins must not call `process.cwd()` — read `cwd` from `ShellState`.
- Line-continuation in REPL: trailing `\` joins (matches workshop agent).
