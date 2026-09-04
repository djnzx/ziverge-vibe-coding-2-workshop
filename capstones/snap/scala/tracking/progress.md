# Snap Scala progress

A live ledger for [`plan.md`](plan.md). Check an item **only** beside named
evidence: a test suite that ran, a verifier case that passed, a command whose
output you read. Source-file presence is never evidence.

Baseline: 2026-09-04 — `sbt "testOnly snap.*"` reports `Total 12, Failed 0`;
`../verify --lang scala` reports `28 failed, 0 passed`, every case stopping at
`snap: not implemented`.

## Phase 0 — Scaffold

- [x] `build.sbt` pins `assemblyOutputPath` to the path `../run` globs — `sbt assembly` built `target/scala-3.9.0/snap-assembly-0.1.0.jar` (2026-09-04)
- [x] `Main.scala` sits at `src/main/scala/Main.scala`, the path `../run` probes — `../run --lang scala --version` reached the binary (2026-09-04)
- [x] `Streams.scala` carries injected `stdoutIsTty` / `stderrIsTty` for SPEC §11's TTY unit tests
- [x] `Contracts.scala` domain model — `snap.ContractsTest` (7 examples), `snap.ContractsPropertyTest` (5 properties)
- [x] Mutation-checked: UTF-16 path order fails 2 tests; `FileBytes` reference equality fails 3; a wrong patch result fails 1 (2026-09-04)
- [x] `../verify --lang scala` runs the Scala candidate end to end — `28 failed, 0 passed`, every case stopping at `snap: not implemented` (2026-09-04)
- [x] `README.md`, `AGENTS.md`, `CLAUDE.md` written

## Phase 1 — Versions (§3)

- [ ] `Versions.contributorId` enforces §3.1, including the 254-byte boundary
- [ ] `Versions.parse` / `render` round-trip canonical §3.2 syntax
- [ ] `parse` rejects duplicate IDs, explicit zeroes, leading zeroes, whitespace, noncanonical order, and revisions over 9007199254740991 — one example each
- [ ] Oversized revisions become `SnapError` via `BigInt`, never a wrap or a thrown `NumberFormatException`
- [ ] `compare` returns all four `Causality` outcomes; `Concurrent` is never folded into before or after
- [ ] Properties: join is idempotent, commutative, associative, and a least upper bound
- [ ] Property: Snap order is total and extends causal order
- [ ] Mutation check: flipped Snap-order tie, dropped duplicate-ID check, `Concurrent` folded into `Before`

## Phase 2 — Paths and trees (§2)

- [ ] `Paths.trackedPath` rejects `.snap`, empty / `.` / `..` segments, backslash, and control characters
- [ ] `isPrefixOf` is segment-wise — `a/bc` is not under `a/b`
- [ ] Ancestor and descendant queries §6.2 needs
- [ ] Property: replay-shaped insertions preserve prefix-freedom
- [ ] Mutation check: `isPrefixOf` as a plain `startsWith`

## Phase 3 — Text and edit scripts (§4.4)

- [ ] `isText` uses a strict UTF-8 decoder and rejects NUL
- [ ] `tokens` splits *after* each LF and keeps it; `"a\r\nb"` gives two tokens
- [ ] `validate` covers positive counts, no adjacent same-kind ops, no empty insert, full consumption, and canonical results
- [ ] Every §4.4 rejection message matches its regex in the plan's catalog
- [ ] Property: `untokens ∘ tokens == id`; a validated script always yields a canonical sequence
- [ ] Mutation check: split *before* LF; drop the NUL check

## Phase 4 — Canonical diff (§5)

- [ ] Literal `D(i, j)` recurrence with delete-on-tie, coalesced output
- [ ] Golden: `"a\nb\na\n"` → `"b\na\na"` matches `05-diff-goldens.yaml`
- [ ] Property: `Text.apply(diff(a, b), a) == b`
- [ ] Property: insert + delete count equals `D(0, 0)` — the script is minimal
- [ ] Mutation check: tie rule as `<` instead of `<=`
- [ ] *(only if an optimized algorithm is adopted)* differential property against the literal recurrence

## Phase 5 — Operational transform (§6.3)

- [ ] `transform` implements all six rows with `Q insert` taking priority
- [ ] Example per table row, plus concurrent inserts at one cursor
- [ ] Property: both scripts consume the same base token count; no unmatched op remains
- [ ] Property: text inserted by `Q` always survives the transform
- [ ] Mutation check: demote the `Q insert` row below `P insert`

## Phase 6 — Repository JSON and validation (§4.1, §4.5, §3.5)

- [ ] Explicit circe conversion — no derived codecs; unknown fields rejected
- [ ] Duplicate JSON keys rejected by a dedicated pass (circe's AST silently keeps the last)
- [ ] Base64 `content` alphabet and padding checked before decoding
- [ ] Canonical serialization: two-space indent, trailing LF, §4.1 field order
- [ ] §4.5's six passes run in order, with the replay pass wired to Phase 7
- [ ] Structural patch equality compares parsed values, so whitespace and key order do not matter
- [ ] §3.5 dot collision fails as corruption before any write
- [ ] `known` implements §4.1's materializable definition, including versions that are neither the frontier nor a patch result
- [ ] Property: `parse ∘ serialize == id`; serialization is a fixpoint
- [ ] Rejection properties: middle-patch deletion, renumbering, unreachable patches, and cycles are all `Left`

## Phase 7 — Deterministic replay (§6.1, §6.2, §6.4, §6.5)

- [ ] Ready ordering keys in the §6.1 sequence: Snap order, author, revision
- [ ] Namespace resolution runs for the patch as a whole, **before** the per-path rules
- [ ] The four §6.2 per-path cases, including the identical-`C`-and-`T` collapse before OT
- [ ] OT uses the aggregate context edit `diff(B, C)` once, not once per historical patch
- [ ] All six §6.4 rules, in order, with correct warning reasons
- [ ] All of one patch's path changes applied together
- [ ] Warnings unique, sorted by path then reason; line OT emits none
- [ ] Generator for **valid causal patch graphs** (not arbitrary patches)
- [ ] Property: import permutations converge on frontier, patches, warnings, and bytes — ≥200 generated graphs
- [ ] Property: re-merge is a no-op; merge direction does not change the result
- [ ] Mutation check: ready ordering by author alone fails the permutation property

## Phase 8 — Workspace and filesystem (§2, §10)

- [ ] `discover` walks to the root; failure is `not a Snap repository`
- [ ] `scan` uses `NOFOLLOW_LINKS` and rejects symlinks and FIFOs by name
- [ ] `install` replaces blocking files, creates directories, and prunes newly empty ones
- [ ] `writeRepository` uses a same-directory temp file and `ATOMIC_MOVE`, leaving no leftover
- [ ] Validate-before-mutate ordering enforced for `merge` and `revert`
- [ ] Property: `scan ∘ install == id` for generated prefix-free trees
- [ ] Every test runs in a temporary directory; nothing reads the process cwd

## Phase 9 — Configuration (§8)

- [ ] Local `.snap/config.json` wins; global is not read when local supplies an ID
- [ ] Local without an ID falls through to `$HOME/.snapconfig.json`
- [ ] Malformed, duplicate-field, unknown-field, or invalid-ID files that *are* read fail
- [ ] Absent `$HOME` means unavailable, not an error
- [ ] `HOME` is injected, never read at the point of use

## Phase 10 — Presentation and error rendering (§7.11, §10)

- [ ] `select` implements the full `SNAP_COLOR` × `NO_COLOR` × TTY table
- [ ] `NO_COLOR` present-but-empty selects the complete plain presentation in `auto`
- [ ] An invalid `SNAP_COLOR` fails plain, **before** command execution
- [ ] `s(n, text)` is the only escape-emitting function; test expectations use it
- [ ] Goldens for every §7.11 layout family, cross-checked against `28-terminal-presentation.yaml`
- [ ] The deleted-row symbol is U+2212 MINUS SIGN, not a hyphen
- [ ] `--version` reports `snap 1.0.0`, held separately from `build.sbt`'s `0.1.0`
- [ ] The `--serve` URL stays plain; `config` stays silent
- [ ] **SPEC §11 requirement:** `auto` unit-tested for TTY and non-TTY stdout and stderr *independently* — no acceptance case can cover this
- [ ] Property: plain-mode output contains no ESC byte

## Phase 11 — Diff rendering and commands (§7.1–§7.8)

- [ ] `DiffRender` emits `/dev/null` headers, `@@ -1,<n> +1,<m> @@` starting at 1 on both sides, and `\ No newline at end of file`
- [ ] Binary changes print the single `Binary files …` line
- [ ] `init`, `config`, `status`, `log`, `commit`, `diff`, `revert`, `merge` implemented
- [ ] `log` escapes backslash, tab, and LF in that order
- [ ] `commit` chooses `text` versus `put` by §7.5's rule
- [ ] `diff --repo` compares shared dots and fails as corrupt on a mismatch
- [ ] `revert` prints the **new** version and never moves the frontier backward
- [ ] `merge` prints only warnings absent from the pre-merge local replay
- [ ] `Cli` implements §7's positional grammar; `diff` uses the `usage: snap diff …` family, everything else `invalid command or arguments`
- [ ] `SNAP_COLOR` resolved before dispatch
- [ ] Exit codes: 0 success, 1 expected error, 2 unanticipated only
- [ ] `../verify --lang scala` passes 24 of 28 — all but `12-http-server`, `13-http-client`, `26-portability-and-failure-safety`, and `28-terminal-presentation`, which each need Phase 12

## Phase 12 — HTTP (§7.9, §9)

- [ ] Server snapshots at startup and never reflects later repository changes
- [ ] Binds `127.0.0.1` only; port defaults to 8765, `0` asks the OS
- [ ] Startup URL printed **and flushed**, always plain
- [ ] `GET` and `HEAD` on `/repository.json`; 404 elsewhere; 405 with `Allow: GET, HEAD`
- [ ] SIGINT and SIGTERM exit 0
- [ ] `invalid port: 65536`
- [ ] Client performs exactly one GET, requires 200, then validates normally
- [ ] A malformed remote mutates nothing

## Final verification

Unchecked until each command has actually been run and its output read.

- [ ] `sbt scalafmtAll` leaves no diff
- [ ] `sbt "testOnly snap.*"` green — record the count, not just "passed"
- [ ] `sbt assembly` produces `target/scala-3.9.0/snap-assembly-0.1.0.jar`
- [ ] `../verify --lang scala` passes **28 of 28**
- [ ] Manual: `SNAP_COLOR=auto` on a real terminal, with and without `NO_COLOR`
- [ ] Manual: Ctrl-C on `snap --serve` exits 0
- [ ] Manual: a large binary file survives `commit` then `revert` byte-for-byte
- [ ] IRON RULES: docs updated in this change; proven patterns offered as skills; bulky subtasks run in subagents
