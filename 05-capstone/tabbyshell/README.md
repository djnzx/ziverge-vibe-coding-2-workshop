# TabbyShell

```
⣿⣿⣿⠟⠋⠉⠛⠻⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⠟⠛⠉⠙⠻⣿⣿⣿
⣿⣿⡏⠀⣰⣶⣶⣤⣀⠈⠙⠿⣿⣿⣿⣿⠿⠿⠿⠿⠿⠿⠿⠿⣿⣿⣿⣿⠿⠋⠁⣀⣤⣶⣶⣦⠀⢹⣿⣿
⣿⣿⡇⠀⣿⠁⠈⠙⠻⣿⣦⣄⠈⠉⣀⠀⠀⢠⡄⠀⠀⢠⡄⠀⠀⣀⠉⠁⣀⣴⣾⠟⠋⠁⠈⣿⠀⢸⣿⣿
⣿⣿⡇⠀⣿⡀⠀⠀⠀⣨⣿⣿⣷⣿⣿⡀⠀⢸⡇⠀⠀⢸⡇⠀⢀⣿⣿⣾⣿⣿⣅⠀⠀⠀⢀⣿⠀⢸⣿⣿
⣿⣿⣧⠀⢸⣇⢀⣴⣿⣿⣿⣿⣿⣿⣿⣷⣀⣸⣿⠀⠀⣿⣇⣀⣼⣿⣿⣿⣿⣿⣿⣿⣦⡀⣸⡏⠀⣼⣿⣿
⣿⣿⣿⡄⠈⡿⠿⠿⠿⠿⠿⠿⠿⠿⠿⢿⣿⣿⣿⣷⣾⣿⣿⣿⡿⠿⠿⠿⠿⠿⠿⠿⠿⠿⢿⠁⢀⣿⣿⣿
⣿⣿⡏⠁⠀⢀⣠⣤⣤⣤⣶⣶⣦⣤⣤⣤⡀⠈⠛⠛⠛⠛⠉⢀⣤⣤⣤⣴⣶⣶⣤⣤⣤⣄⡀⠀⠈⢹⣿⣿
⣿⣿⡇⠀⠀⢸⣿⣿⣿⣿⠟⠋⠉⠉⠻⣿⡗⠀⢠⣤⣤⡄⠀⢸⣿⠟⠋⠉⠙⠻⣿⣿⣿⣿⡇⠀⠀⢸⣿⣿
⣿⣿⠀⢸⡆⠀⣿⣿⣿⣏⣀⣾⣿⣷⣄⣘⡇⠀⡾⠿⠿⢿⠀⢸⣇⣠⣾⣿⣷⣀⣹⣿⣿⣿⠁⢰⡇⠀⣿⣿
⣧⡄⠀⢈⣳⡀⠘⠿⢿⣿⣿⣿⣿⡿⠿⠛⠀⣼⣧⡀⢀⣼⣧⡀⠙⠿⢿⣿⣿⣿⣿⡿⠿⠃⢀⣞⣁⠀⢠⣼
⡿⠓⠀⢈⣹⣿⠶⠤⣤⣤⣤⣤⣤⣤⣤⣦⡈⠉⠉⠀⠀⠉⠉⢁⣴⣤⣤⣤⣤⣤⣤⣤⠤⠶⣿⣏⡉⠀⠘⢿
⣿⣿⡄⠈⠋⠁⠀⣴⣿⣿⣿⣿⣿⣿⣿⣿⣧⠀⢺⣿⣿⡷⠀⣼⣿⣿⣿⣿⣿⣿⣿⣿⣦⠀⠀⠙⠁⢠⣿⣿
⣿⣿⣿⣄⠀⠠⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣧⣄⣉⣉⣀⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⠄⠀⣠⣿⣿⣿
⣿⣿⣿⣿⣷⣄⠈⠙⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠿⠋⠁⣠⣾⣿⣿⣿⣿
⣿⣿⣿⣿⣿⣿⣿⣦⣄⡀⠉⠙⠛⠿⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠿⠿⠛⠋⠉⢀⣠⣴⣾⣿⣿⣿⣿⣿⣿
⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣶⣦⣤⣤⣀⣀⣀⣀⣀⣀⣀⣀⣀⣀⣤⣤⣴⣶⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿
```

A small, NuShell-inspired interactive shell. Your archive contains one language
workspace under this project directory; its behavior is defined by the shared
specification and acceptance tests.

## At a glance

- **Focus:** typed data modelling, parsing, pipeline execution, exact rendering,
  process boundaries, and one constrained LLM integration.
- **Expected difficulty:** high. The feature set is deliberately small, but the
  parser, stateful shell behavior, and byte-exact output create a broad
  integration surface.
- **Prerequisites:** Node.js for the public harness, the toolchain for the
  selected implementation language, and an OpenRouter key to exercise AI
  formatting outside the deterministic public suite. The no-AI fallback is
  covered by the public suite. The `tabby` launcher loads
  `OPENROUTER_API_KEY` from the repository-root `.env` before entering this
  relocated project directory.

## What's here

- [`SPEC.md`](SPEC.md) — the canonical behavioral specification.
- [`banner.txt`](banner.txt) — the tabby cat ASCII, read at runtime.
- [`tests/`](tests/) — the process-style public acceptance suite.
- [`fixtures/`](fixtures/) — sample CSV/JSON files used by tests.
- [`test-harness/`](test-harness/) — TypeScript test runner that drives any
  built binary via `tabby --eval-file -`.
- `<language>/` — the selected scaffold's location in an attendee archive.

## Quick start

Verify your selected workspace; the verifier installs dependencies, builds it,
and runs the public suite:

```bash
./capstones/tabbyshell/verify --lang ts \
  --implementation-root capstones/tabbyshell/ts
```

Replace `ts` with `rust` or `scala` when appropriate. After a successful build,
run the REPL or a one-shot pipeline with the shared launcher:

```bash
./capstones/tabbyshell/tabby
./capstones/tabbyshell/tabby --eval "ls | first 3"
```


## Cross-language parity

`render(value, color=false)` must produce **byte-identical** output across all
three implementations. The YAML test suite enforces this — running the same
`tests/` directory against any of the three binaries must produce the same
pass/fail result.

## Design

See [`SPEC.md`](SPEC.md). Highlights:

- 14 commands across streamers (`ls`, `open`, `cat`, `pwd`), stateful (`cd`),
  filters (`where`, `select`, `sort-by`, `first`, `last`, `length`, `get`),
  formatters (`to`, `save`), plus AI-formatted external commands.
- One ADT (`Value`) with 10 variants — Null, Bool, Int, Float, Str, Filesize,
  Date, List, Record, Table.
- Tiny recursive-descent parser, single-line pipelines.
- Box-drawing renderer with ANSI-optional output.
- External commands route their stdout through OpenRouter to be reshaped into
  a typed table — the shell's one cool AI trick.
