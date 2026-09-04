# Snap Scala progress

A live ledger for [`plan.md`](plan.md). Check an item **only** beside named
evidence: a test suite that ran, a verifier case that passed, a command whose
output you read. Source-file presence is never evidence.

Baseline: 2026-09-04 — `sbt "testOnly snap.*"` reports `Total 79, Failed 0`;
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

Landed 2026-09-04. `sbt "testOnly snap.Versions*"` reports `Total 67, Failed 0`
(30 examples in `snap.VersionsTest`, 37 properties in `snap.VersionsPropertyTest`).

- [x] `Versions.contributorId` enforces §3.1, including the 254-byte boundary and the ASCII restriction
- [x] `Versions.parse` / `render` round-trip canonical §3.2 syntax — 4 round-trip properties
- [x] `parse` rejects duplicate ids, explicit zeroes, leading zeroes, whitespace, noncanonical order, and revisions over 9007199254740991 — an example and a property each
- [x] Oversized revisions become `SnapError` via `BigInt`, never a wrap or a thrown `NumberFormatException` — pinned by a property over 17-to-40-digit literals
- [x] `compare` returns all four `Causality` outcomes; `Concurrent` is never folded into before or after
- [x] Properties: join is idempotent, commutative, associative, an upper bound, and the *least* upper bound
- [x] Property: Snap order is total, antisymmetric, transitive, agrees with equality, and extends causal order
- [x] Property: Snap order matches §3.4 read literally off the sorted union (an oracle — the algebraic properties alone do not pin *which* order)
- [x] Error details escape control characters so §10's one-line rule holds — found by the message-shape property, not by an example
- [x] Mutation-checked: 12 mutations, each caught by 1–7 tests; the one survivor was an equivalent mutant and is recorded in `README.md`

## Phase 2 — Paths and trees (§2)

Landed 2026-09-04. `sbt "testOnly snap.Paths*"` reports `Total 39, Failed 0`
(24 examples in `snap.PathsTest`, 15 properties in `snap.PathsPropertyTest`).

- [x] `Paths.trackedPath` rejects `.snap`, empty / `.` / `..` segments, backslash, and control characters — examples and rejection properties for each shape
- [x] `isPrefixOf` is segment-wise — `a/bc` is not under `a/b` — example, plus transitivity and order-compatibility properties
- [x] Ancestor and descendant queries §6.2 needs — `Paths.ancestor` / `Paths.descendants` against a `Tree`, examples only (no property yet; Phase 7 exercises them against real replay data)
- [x] Property: replay-shaped insertions preserve prefix-freedom — a distinct-top-segment generator builds a prefix-free set, a candidate is removed against its ancestors/descendants, then inserted
- [x] Mutation-checked: `isPrefixOf` as a plain `startsWith` fails 7 tests (the `a/bc` example plus 6 properties); `prefixFree` via sorted-adjacency instead of pairwise fails 1 dedicated example — `README.md` records both

## Phase 3 — Text and edit scripts (§4.4)

Landed 2026-09-04. `sbt "testOnly snap.TextTest snap.TextPropertyTest"` reports
`Total 36, Failed 0` (26 examples in `snap.TextTest`, 10 properties in
`snap.TextPropertyTest`). Full suite: `sbt "testOnly snap.*"` reports
`Total 154, Failed 0`.

- [x] `isText` uses a strict `CharsetDecoder` (`CodingErrorAction.REPORT`) and rejects NUL explicitly, since NUL is itself valid UTF-8
- [x] `tokens` splits *after* each LF and keeps it; `"a\r\nb"` gives two tokens (`snap.TextTest`); the empty file has no tokens; an unterminated final token survives
- [x] `validate` covers positive counts bounded by `Limits.maxSafeInteger`, no adjacent same-kind ops, no empty insert, no empty/internally-LF'd insert token, exact old-length consumption (both over- and under-consumption rejected), and canonical results — the "may the last token skip its trailing LF" exemption is resolved by finding the script's last content-emitting operation (a positive `Retain` or a nonempty `Insert`; a trailing `Delete` emits nothing)
- [x] Every §4.4 rejection message satisfies its catalog regex: `must have one operation`, `insert is empty`, `consumes beyond old content`, `positive safe integer`, and `canonical` (adjacency and LF-position violations both render as `is not canonical: ...`) — one example per message in `snap.TextTest`
- [x] Properties: `untokens ∘ tokens == id` for arbitrary UTF-8 text; `tokens ∘ untokens == id` for any canonical token sequence; every token but the last ends in LF; `isText` is false for any byte sequence containing NUL; a validated script applied to its declared old length always succeeds and always yields a canonical sequence (generator builds scripts with no two adjacent same-kind ops and derives `oldLength` from the script's own retain/delete counts, so consumption always matches by construction)
- [x] Rejection properties: adjacent same-kind operations, a zero-count operation, a count beyond `Limits.maxSafeInteger`, and a script that does not consume the full declared old length are each always `Left`
- [x] Mutation-checked: dropping the NUL check in `isText` fails 2 tests; splitting *before* LF instead of after fails 4 tests (both reverted after confirming)

## Phase 4 — Canonical diff (§5)

Landed 2026-09-04. `sbt "testOnly snap.DiffTest snap.DiffPropertyTest"` reports
`Total 11, Failed 0` (7 examples, 4 properties). Full suite:
`sbt "testOnly snap.*"` reports `Total 165, Failed 0`.

- [x] Literal `D(i, j)` recurrence with delete-on-tie, coalesced output — `Diff.distances` implements the recurrence verbatim; `Diff.diff`'s walk coalesces adjacent same-kind operations as it emits them
- [x] Golden: `"a\nb\na\n"` → `"b\na\na"` matches `05-diff-goldens.yaml` — pinned in `snap.DiffTest` as `Delete(1), Retain(2), Insert(["a"])`, matching the patch recorded in the YAML's `json_equals` assertion
- [x] Property: `Text.apply(diff(a, b), a) == b` — `snap.DiffPropertyTest`, over generated canonical token sequences
- [x] Property: insert + delete count equals `D(0, 0)` — the script is minimal — checked against an independently reimplemented `D(0,0)` oracle in the test, not `Diff.distances` itself
- [x] Mutation check: tie rule as `<` instead of `<=` fails 2 tests (the golden and a dedicated coalescing example) — reverted after confirming (2026-09-04)
- [ ] *(only if an optimized algorithm is adopted)* differential property against the literal recurrence — not applicable; the literal recurrence ships as-is

## Phase 5 — Operational transform (§6.3)

Landed 2026-09-04. `sbt "testOnly snap.OtTest snap.OtPropertyTest"` reports
`Total 14, Failed 0` (11 examples, 3 properties). Full suite:
`sbt "testOnly snap.*"` reports `Total 179, Failed 0`.

- [x] `transform` implements all six rows with `Q insert` taking priority — `Ot.transform`, a left-to-right walk over both streams with count-splitting via a shared `push`/`remainder` pair mirroring `Diff.scala`'s coalescing
- [x] Example per table row, plus concurrent inserts at one cursor — six single-row examples plus the priority-deciding case in `snap.OtTest`, asserting `p`'s insert lands *after* `q`'s when both insert at the same cursor
- [x] Three cases shaped like `22-ot-matrix.yaml`'s merge scenarios (delete/delete, the all-rows-at-once split, retain/delete, and insert-survives-delete), each computed via `Diff.diff` against the same base the YAML fixture uses and checked against that fixture's asserted merged bytes
- [x] Property: `transform(p, q)` is a valid, coalesced script against the token count `q`'s result has, and applying it never fails — `snap.OtPropertyTest`, `p`/`q` generated as real `Diff.diff` scripts off a shared base
- [x] Property: text inserted by `Q` always survives the transform — a marker-token generator (`withMarkersSpliced`) drawn from an alphabet disjoint from the base/edit alphabet, asserting the marker's count in the result matches its count in `q`'s own result
- [x] Mutation check: demoting the `Q insert` row below `P insert` fails the concurrent-insert example plus all 3 properties (4 of 14 tests) — reverted after confirming (2026-09-04)

## Phase 6 — Repository JSON and validation (§4.1, §4.5, §3.5)

Landed 2026-09-05. `sbt "testOnly snap.RepositoryJsonTest snap.RepositoryJsonPropertyTest snap.ValidationTest snap.ValidationPropertyTest"` reports
`Total 46, Failed 0` (20 + 3 + 18 + 5). Full suite: `sbt "testOnly snap.*"` reports `Total 225, Failed 0`. `sbt assembly` still builds the JAR.
`../verify --lang scala` still reports `28 failed, 0 passed` — expected, since `Cli.scala` is still the Phase 0 stub; this phase's four acceptance files
(`15-repository-validation.yaml`, `23-strict-validation-matrix.yaml`, `27-history-canonicality.yaml`, `16-dot-collision.yaml`) are exercised at the unit level
against the exact scenarios those YAML files use, so passing them today is the evidence Phase 11's CLI wiring will make the acceptance cases pass too.

- [x] Explicit circe conversion — no derived codecs; unknown fields rejected at every object level (repository, patch, change, edit operation) —
      `RepositoryJson.parse`, one `rejectUnknown` example per level in `RepositoryJsonTest`
- [x] Duplicate JSON keys rejected by a dedicated raw-text scanner (`JsonValidation.duplicateKeys`) run after circe confirms syntactic validity — circe's AST
      silently keeps the last value and reports nothing, so the scan cannot be skipped
- [x] Base64 `content` checked by decoding then re-encoding and comparing to the original text — catches non-canonical encodings
      `java.util.Base64.getDecoder` alone accepts (unused low bits in the final character), not just bad alphabet/padding
- [x] Canonical serialization: two-space indent, trailing LF, §4.1's `format`/`frontier`/`patches` and per-patch/per-change field order — hand-built
      `RepositoryJson.serialize`, independent of `circe.Printer`'s own formatting choices
- [x] §4.5's six passes: pass 1 (schema) in `RepositoryJson.parse`; passes 2–6 in `Validation.validate`, in order — patch sorting and one-value-per-dot,
      base closure and the revision formula, acyclic causality (Kahn's-algorithm ready-set walk), every change against its materialized exact base, and
      frontier closure (`unreachable patch`, and every base component within the frontier)
- [x] Pass 2's other half — contiguous per-author revisions — is not a separate scan: `checkBaseClosure`'s `revision = base[author] + 1` plus its
      base-existence check force it by induction down to revision 1, documented at the call site rather than duplicated as code
- [x] Passes 5 and 6 share a **placeholder** materializer (`Validation.materialize`): selects a version's causal closure, applies patches in §6.1 ready
      order, with **no** namespace or OT conflict resolution. Correct for every base this phase's tests need (empty or a single linear chain); Phase 7
      replaces it with `Replay.materialize` and only the two call sites change — documented in the module doc comment, not hidden
- [x] `Text.scala` split into `validateShape` (script-only checks, no `oldLength`) and `validate` (`validateShape` plus the base-length comparison), so the
      JSON schema pass can run the context-free checks before any tree exists — pure refactor, `TextTest`/`TextPropertyTest` still green at `Total 36`
- [x] Found and fixed a real message-catalog mismatch predating this phase: `Text`'s under-consume error said "does not consume all old content";
      `15-repository-validation.yaml` asserts the substring "does not consume old content" (no "all"). Would have failed the acceptance suite the moment
      Phase 11 wired a CLI to it — caught here by writing the phase's tests directly from the YAML fixtures instead of re-deriving expected text
- [x] Structural patch equality compares parsed values: `Patch`'s case-class equality already operates on typed fields, so whitespace and JSON key order
      never cause a false collision — no bespoke comparison needed, `dotCollision` examples in `ValidationTest`
- [x] §3.5 dot collision (`Validation.dotCollision`) fails as `patch collision: <author> revision <n>` before any write — exact string
      `16-dot-collision.yaml` asserts; wiring into `merge`/`diff --repo` is Phase 11's job
- [x] `known` implements §4.1's materializable definition (every selected patch's revisions 1..n present, and the selected set contains the complete base
      of every selected patch), including a version that is neither the frontier nor a patch result — `ValidationTest`
- [x] Property: `parse(serialize(repo)) == repo` for generated schema-valid repositories; serialization is a fixpoint
      (`serialize(parse(serialize(repo))) == serialize(repo)`) — `RepositoryJsonPropertyTest`
- [x] Rejection properties over a generated causally-simple repository (independent per-author linear `put` chains — deliberately short of Phase 7's
      "valid causal patch graph" generator, since this phase's placeholder materializer cannot resolve a real concurrent conflict): deleting a non-last
      patch, renumbering a revision without its base, appending a patch beyond the frontier, and a two-contributor mutual cycle are all `Left` —
      `ValidationPropertyTest`
- [x] Mutation-checked (`Validation.scala`): disabling the acyclic check, the `put`/`text` no-op check, the base-closure missing-patch check, or the
      frontier unreachable-patch check each fail at least one test (reverted after confirming, 2026-09-05); a mutation of `RepositoryJson`'s message-escaping
      (`\n` written raw instead of `\\n`) fails both the example test and the round-trip property, confirming the property has teeth

## Phase 7 — Deterministic replay (§6.1, §6.2, §6.4, §6.5)

Landed 2026-09-05. `sbt "testOnly snap.ReplayTest snap.ReplayPropertyTest"` reports `Total 26, Failed 0` (17 examples, 8 properties, 1 generator-coverage test).
Full suite: `sbt "testOnly snap.*"` reports `Total 251, Failed 0`. `sbt scalafmtAll` reformatted 2 sources and the suite stayed green; `sbt assembly` still
builds the JAR. `../verify --lang scala` still reports `28 failed, 0 passed` — expected, `Cli.scala` is still the Phase 0 stub; this phase's five acceptance
files are exercised at the unit level against the exact scenarios they run.

- [x] Ready ordering keys in the §6.1 sequence: Snap order of result versions, then author, then revision — `Replay.readyOrdering`; every example below is only
      correct for that order, which is what makes them the evidence
- [x] Namespace resolution runs for the patch as a whole, **before** the per-path rules, over `C'` (the canonical tree minus what the patch deletes) — both
      directions of `11-namespace-conflicts.yaml` reproduced in `ReplayTest`
- [x] The four §6.2 per-path cases, including the identical-`C`-and-`T` collapse before OT; without it, two identical concurrent edits duplicate their own text
      (`"same\nsame\n"` instead of `"same\n"`), which is what the mutation showed
- [x] OT uses the aggregate context edit `diff(B, C)` once — the three-way merge from `18-three-way-convergence.yaml` produces `"B\nA\nend\n"`, worked by hand
      and matching the YAML's assertion
- [x] All six §6.4 rules, in order, with correct reasons — one example per rule, all against a single history shaped like `10-merge-conflicts.yaml`, whose
      stderr assertion the warning set reproduces exactly
- [x] All of one patch's path changes applied together, each resolved against the same `B` and `C` and never against a tree a sibling path already updated
- [x] Warnings unique and sorted by path then reason (a `SortedSet[Warning]`); line OT emits none — the three-way text merge asserts an empty warning set
- [x] Nested base replays are memoized by version. Without it a forked history costs exponential time, because §6.2 makes every patch's exact base a full replay
- [x] Generator for **valid causal patch graphs** — grows a history one patch at a time from a pool of already-materializable versions, joining a random subset
      for each new base (joins of materializable versions are materializable, so validity is structural, not filtered for), and forces the author's own latest
      result into that base so the dot is fresh and `revision = base[author] + 1` holds by construction
- [x] The generator is checked two ways, because it uses `Replay.materialize` to compute the base tree it draws changes against: `Validation.validate` accepts
      every generated repository (re-deriving each base independently of replay's bookkeeping), and a seeded coverage test asserts that all five §6.4 reasons
      actually occur across 300 graphs — it fails if the generator ever stops producing genuine conflicts
- [x] Property: replay depends only on the patch *set*, never on arrival order — 200 generated graphs, each against an independently drawn permutation
- [x] Property: merge direction does not change the joined result; merging a version the frontier already contains is a no-op; import is associative
- [x] Property: the replayed tree is always prefix-free; every warning names a path some patch actually changed
- [x] Phase 6's placeholder materializer is gone. `Validation` now calls `Replay.materialize` for passes 5 and 6, and §4.3's authored-result rules moved to
      `Replay` (`authored`, `authoredTree`) so both callers share one implementation and the dependency runs one way. Phase 6's 46 tests stayed green through
      the swap, which is the evidence the move preserved every message in the catalog
- [x] **The plan's mutation check was wrong about which test catches what.** Ready ordering by plain author order is *still* a deterministic function of the
      patch set, so the permutation property survives it untouched; the 8 golden examples plus the coverage test are what fail (9 tests). Only a selection that
      reads arrival order — taking the ready set's head — falsifies the permutation property (2 tests). Both are now recorded in `README.md`; `plan.md`'s exit
      criterion was corrected to ask for both
- [x] **A mutation survived and found a real gap.** Folding a nested base replay's warnings into the outer set failed nothing. It is observable: a patch's base
      can resolve a path as `later-put-wins` while the replay containing it sees an earlier concurrent delete, resolves `delete-wins`, and never reaches that
      rule at all. `ReplayTest` now builds exactly that five-patch history and the mutation fails it. The reading it pins — a base replay computes `B`, and a
      replay reports only its own integrations' warnings — is recorded as an open question in `AGENTS.md`, since no case in `../tests/` discriminates
- [x] Mutation-checked (`Replay.scala`), each reverted after confirming (2026-09-05): ready ordering by author alone fails 9; ready set's head fails 2;
      namespace resolution per path instead of per patch fails 7 (including the prefix-free property); dropping §6.2's identical-`C`-and-`T` collapse fails 1;
      swapping §6.4's `later-put-wins` and `put-wins` fails 3; nested base warnings collected fails 1 (0 before the gap above was closed)

## Phase 8 — Workspace and filesystem (§2, §10)

Workspace boundary landed 2026-09-05. `sbt "testOnly snap.WorkspaceTest"` reports `Total 10, Failed 0`; the full `sbt "testOnly snap.*"` suite reports `Total 261, Failed 0`. The one command-composition item remains for Phase 11, where merge and revert are introduced.

- [x] `discover` walks to the root; failure is `not a Snap repository` — nested-repository and failure examples in `snap.WorkspaceTest`
- [x] `scan` uses `NOFOLLOW_LINKS` and rejects symlinks and FIFOs by name — the two exact-message examples in `snap.WorkspaceTest`
- [x] `install` replaces blocking files, creates directories, and prunes newly empty ones — `snap.WorkspaceTest` covers file-to-directory replacement, pruning, and preservation of `.snap/`
- [x] `writeRepository` uses a same-directory temp file and `ATOMIC_MOVE`, leaving no leftover — `snap.WorkspaceTest` checks its parsed replacement and directory entries
- [ ] Validate-before-mutate ordering enforced for `merge` and `revert` — Phase 11 owns the still-absent command handlers; it must compose `scan`, validation, `Replay.materialize`, `install`, then `writeRepository` in that order
- [x] Property: `scan ∘ install == id` for generated prefix-free trees — `snap.WorkspaceTest`
- [x] Every test runs in a temporary directory; nothing reads the process cwd — `snap.WorkspaceTest`'s shared temporary-directory fixture

## Phase 9 — Configuration (§8)

Configuration boundary landed 2026-09-05. `sbt "testOnly snap.ConfigTest"` reports `Total 10, Failed 0`; the full `sbt "testOnly snap.*"` suite reports `Total 271, Failed 0`. `JsonValidation` now shares raw duplicate-key detection between repository and configuration parsing, replacing the former `RepositoryJson.DuplicateKeys` helper.

- [x] Local `.snap/config.json` wins; global is not read when local supplies an ID — malformed-global precedence example in `snap.ConfigTest`
- [x] Local without an ID falls through to `$HOME/.snapconfig.json` — empty-local fallback example in `snap.ConfigTest`
- [x] Malformed, duplicate-field, unknown-field, or invalid-ID files that *are* read fail — `snap.ConfigTest`
- [x] Absent `$HOME` means unavailable, not an error — injected-`None` example in `snap.ConfigTest`
- [x] `HOME` is injected, never read at the point of use — every `snap.ConfigTest` path is an explicit temporary directory

## Phase 10 — Presentation and error rendering (§7.11, §10)

Presentation boundary landed 2026-09-05. `sbt "testOnly snap.PresentationTest"` reports `Total 13, Failed 0`; the full `sbt "testOnly snap.*"` suite reports `Total 284, Failed 0`.

- [x] `select` implements the full `SNAP_COLOR` × `NO_COLOR` × TTY table — `snap.PresentationTest`
- [x] `NO_COLOR` present-but-empty selects the complete plain presentation in `auto` — `snap.PresentationTest`
- [x] An invalid `SNAP_COLOR` fails plain, **before** command execution — exact-error example in `snap.PresentationTest`
- [x] `s(n, text)` is the only escape-emitting function; test expectations use it — `Presentation.s` and every terminal golden in `snap.PresentationTest`
- [x] Goldens for every §7.11 layout family, cross-checked against `28-terminal-presentation.yaml` — `snap.PresentationTest`
- [x] The deleted-row symbol is U+2212 MINUS SIGN, not a hyphen — exact `status` golden in `snap.PresentationTest`
- [x] `--version` reports `snap 1.0.0`, held separately from `build.sbt`'s `0.1.0` — `Presentation.release` and `snap.PresentationTest`
- [x] The `--serve` URL stays plain; `config` stays silent — `snap.PresentationTest`
- [x] **SPEC §11 requirement:** `auto` unit-tested for TTY and non-TTY stdout and stderr *independently* — injected-stream example in `snap.PresentationTest`
- [x] Property: plain-mode output contains no ESC byte — `snap.PresentationTest`

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
