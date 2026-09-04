# Snap Scala implementation plan

## Scope and canonical sources

Implement the Scala edition of Snap in this directory only. Every shell command
below is written to run from `capstones/snap/scala/`, and every bare `../path`
in prose is relative to that directory too — not to `tracking/`.

- **Behavior:** [`SPEC.md`](../../SPEC.md) is canonical. Where the spec and any
  code, README, or this plan disagree, the spec wins.
- **Executable contract:** the 28 language-neutral cases in
  [`tests/`](../../tests), run by `../verify --lang scala`. A green unit suite
  does not imply conformance.
- **Working agreement:** [`CLAUDE.md`](../CLAUDE.md) (how to work here and what
  "done" means), [`AGENTS.md`](../AGENTS.md) (module decomposition and
  conventions), [`README.md`](../README.md) (toolchain and testing strategy).

Non-goals, from SPEC §12: branches, tags, staging, checkout, amend, rebase,
unresolved conflicts or conflict markers, rename detection, symlink or
permission tracking, ignore files, push or a writable server, authentication,
object hashing or compression, concurrent-process safety, and crash recovery.
Do not add a command, option, or JSON field beyond §7 and §4. If something is
genuinely missing, change `../SPEC.md` first, say so out loud (it binds the
TypeScript and Rust editions too), and add a public YAML case in the same
change.

## Implementation snapshot — 2026-09-04

The workspace is a verified scaffold. Nothing in SPEC §§3–9 is implemented.

| Evidence | Result |
|---|---|
| `sbt compile` | success, 4 sources |
| `sbt "testOnly snap.*"` | `Total 12, Failed 0, Errors 0, Passed 12` (7 examples, 5 properties) |
| `sbt assembly` | `target/scala-3.9.0/snap-assembly-0.1.0.jar` |
| `../run --lang scala --version` | `snap: not implemented`, exit 1 |
| `../verify --lang scala` | `28 failed, 0 passed` in 4.3s — every case stops at `snap: not implemented` |

Present and working:

- `build.sbt` — sbt 2.0.8 / Scala 3.9.0, sbt-assembly with `assemblyOutputPath`
  pinned to `target/scala-3.9.0/snap-assembly-0.1.0.jar` (the path `../run` and
  `../run_tests` glob), WartRemover on `return` / `asInstanceOf` /
  `isInstanceOf` / `null`, circe-parser, MUnit + ScalaCheck.
- `src/main/scala/Main.scala` — entry point at the exact path `../run` probes to
  decide that a Scala implementation exists.
- `src/main/scala/snap/Streams.scala` — UTF-8 output edge with injected
  `stdoutIsTty` / `stderrIsTty`.
- `src/main/scala/snap/Contracts.scala` — domain model with two canonical
  orderings that are mutation-checked: `Ordering[TrackedPath]` (unsigned UTF-8
  bytes) and `FileBytes` content equality.
- `src/main/scala/snap/Cli.scala` — **stub**. Prints `snap: not implemented`,
  exits 1, dispatches nothing.

## Facts extracted from `../tests/` that the spec leaves implicit

Read these before writing code; each one is a trap.

1. **`--version` prints `snap 1.0.0`** (`28-terminal-presentation.yaml`). That
   string is *not* `build.sbt`'s `version`, which is `0.1.0` and exists only to
   name the assembly JAR. Keep the reported semver as its own constant.
2. **`diff` has its own usage-error family.** Every other grammar violation is
   `snap: invalid command or arguments`; a malformed `diff` is
   `snap: usage: snap diff …` (`24-cli-grammar-matrix.yaml`).
3. **The exact error catalog the suite asserts on**, verbatim or by regex:

   ```text
   snap: invalid command or arguments
   snap: usage: snap diff .+
   snap: not a Snap repository
   snap: working tree is clean
   snap: working tree is dirty
   snap: target tree is already current
   snap: invalid commit message
   snap: invalid contributor id: .+
   snap: invalid version: .+
   snap: unknown version: (a@x->2)
   snap: unreachable patch: .+
   snap: duplicate JSON key .+
   snap: repository has unknown field: unknown
   snap: delete of absent path: f
   snap: unsupported working tree entry: link
   snap: unsupported working tree entry: pipe
   snap: invalid port: 65536
   snap: contributor.id is required; configure it locally or globally
   snap: SNAP_COLOR must be auto, always, or never
   snap: .+changes is empty
   snap: .+message is empty
   snap: .+insert is empty
   snap: .+must have one operation
   snap: .+consumes beyond old content
   snap: .+positive safe integer
   snap: .+unknown field: extra
   snap: .*canonical.*
   ```

4. **`@@ -1,0 +1,1 @@`** — the hunk header always starts at 1 on both sides,
   even when a side is absent and its count is 0 (`05-diff-goldens.yaml`).
5. **The warning reason tie-break is unexercised.** Every warning assertion in
   the suite has distinct paths, so §6.4's "sorted by path, then reason" never
   discriminates. `Contracts.scala` orders by wire token. Leave it, and do not
   invent a case that pins a different answer.
6. **The harness never gives the candidate a PTY** — it captures both streams
   through pipes. `SNAP_COLOR=auto` against a terminal is unit-test-only
   territory, which SPEC §11 requires explicitly.
7. **Harness vocabulary in use:** `run`, `write_file`, `assert`, `copy_tree`,
   `remove`, `mkdir`, `symlink`, `fifo`, `start`/`stop` (long-lived `--serve`
   with a stdout readiness regex and capture), `start_http`/`stop_http` (a stub
   server the client tests point at), and `http_request`. Assertions include
   `trees_equal`, `json_equals`, `file_base` (base64), `stderr_matches`, and
   `http_requests_equal`.

## Phases

Every phase opens with a **Start here** block: the spec text to read, the
modules it builds on, and the acceptance cases it moves. That exists so a
session can begin cold — after a `/clear` between phases — without re-deriving
the map. Read `CLAUDE.md` and `AGENTS.md` once on entry too; they carry the
conventions and the working agreement, and no phase repeats them.

Line numbers are a convenience for jumping to the right place; the **section
number is authoritative**, and the line numbers drift the moment `../SPEC.md` is
edited. Clear between phases, not inside one — mid-phase, and especially
mid-debugging, the live reasoning is exactly the part that no file holds.

Pure, deterministic layers come first: everything in §§3–6 is decidable without
touching the filesystem, and every later phase depends on them. No public
acceptance case passes until Phase 11 assembles a vertical slice, so each phase
below is gated on unit evidence, and the phases that unlock YAML files name
them.

---

### Phase 1 — Versions (§3)

**Start here.**
- Read: `../SPEC.md` §3, lines 86–151.
- Builds on: `Contracts.scala` (`ContributorId`, `Version`, `Causality`, `Dot`, `Limits`).
- Acceptance cases it moves: `19-version-boundaries.yaml`, `21-version-algebra.yaml`, `25-config-version-path-boundaries.yaml`.

**Goal.** `Versions.scala`: contributor-ID validation, canonical version syntax,
the four-way causal comparison, join, and Snap order.

**SPEC references.** §3.1 (ID shape, revision bound), §3.2 (canonical syntax and
the repository-JSON pair array), §3.3 (comparison and join), §3.4 (Snap order),
§3.5 (serial contributor rule — the *statement* here; enforcement lands in
Phase 6).

**Starting status.** Absent. `Contracts.scala` has `ContributorId`, `Version`,
`Causality`, and `Dot` as data, plus `Version.apply(id)` returning 0 for an
absent component. No parsing, rendering, or comparison exists.

**Steps.**

1. `def contributorId(text: String): Either[SnapError, ContributorId]` —
   exactly one `@`, nonempty both sides, no control character, whitespace, `,`,
   `(`, `)`, or `->`, at most `Limits.maxContributorIdBytes` bytes. Preserve
   spelling exactly; no normalization. Error: `invalid contributor id: <text>`.
2. `def parse(text: String): Either[SnapError, Version]` for the CLI form
   `(a@x->2,b@y->3)` — reject duplicate IDs, explicit zeroes, leading zeroes,
   values over `Limits.maxSafeInteger`, whitespace anywhere, and noncanonical
   contributor ordering. Parse the magnitude through `BigInt` so an oversized
   revision is a `SnapError`, never a wrap or a thrown
   `NumberFormatException`. Error: `invalid version: <text>`.
3. `def render(version: Version): String` — the inverse, sorted by contributor,
   no spaces, `()` when empty.
4. `def compare(v: Version, w: Version): Causality` — all four outcomes.
   Concurrency must be a distinct answer, never folded into before or after.
5. `def join(v: Version, w: Version): Version` — componentwise max.
6. `given Ordering[Version]` for Snap order (§3.4): sorted union of contributor
   IDs, compare the counter at each ID, first inequality decides.

**Tests before completion.**

- Examples: `()` round-trip; the §3.2 two-contributor sample; each rejection in
  step 2 with its own case; the ID rules including a 254-byte boundary and a
  255-byte rejection; `(a@x->2)` versus `(a@x->2,b@y->1)` for each `Causality`.
- Properties: `parse ∘ render == id` for generated versions; `render ∘ parse`
  is a fixpoint on canonical text; join is idempotent, commutative, associative,
  and is the least upper bound under `Before`; exactly one `Causality` holds for
  any pair; Snap order is total, antisymmetric, transitive, and extends causal
  order (`v Before w` implies `Ordering[Version].lt(v, w)`).
- Rejection properties: noncanonical orderings, injected whitespace, `->0`
  components, and `9007199254740992` are all `Left`, and every message matches
  `invalid version: `.
- Totality: `parse` returns an `Either` for arbitrary input, never throws.

**Exit criterion.** `sbt "testOnly snap.VersionsTest snap.VersionsPropertyTest"`
green, and a mutation pass: flipping the tie in Snap order, dropping the
duplicate-ID check, and folding `Concurrent` into `Before` each fail at least
one test.

---

### Phase 2 — Paths and trees (§2)

**Start here.**
- Read: `../SPEC.md` §2, lines 53–85, and §6.2's namespace paragraph, lines 340–356.
- Builds on: `Contracts.scala` (`TrackedPath` and its unsigned-UTF-8 `Ordering`, `Tree`).
- Acceptance cases it moves: `02-init-paths.yaml`, `11-namespace-conflicts.yaml`, `25-config-version-path-boundaries.yaml`.

**Goal.** `Paths.scala`: tracked-path validation, prefix-freedom, and the
ancestor/descendant queries the namespace rule needs.

**SPEC references.** §2 (path grammar, prefix-freedom, unsupported entries as a
*concept*), §6.2 (namespace conflict inputs), §6.4 rule ordering.

**Starting status.** Absent. `Contracts.scala` supplies `TrackedPath`, the
unsigned-UTF-8 `Ordering`, `FileBytes`, and the `Tree` alias, all
mutation-checked. No validation exists.

**Steps.**

1. `def trackedPath(text: String): Either[SnapError, TrackedPath]` — nonempty;
   no ASCII control character; no backslash; no empty, `.`, or `..` segment;
   first segment not `.snap`; valid UTF-8 by construction. No Unicode or case
   normalization.
2. `def isPrefixOf(ancestor: TrackedPath, descendant: TrackedPath): Boolean` —
   segment-wise, so `a/b` is not a descendant of `a/bc`.
3. `def prefixFree(paths: Set[TrackedPath]): Either[SnapError, Unit]` and the
   query pair §6.2 needs: given a candidate path and a tree, the current
   ancestor and the current descendants.

**Tests before completion.**

- Examples: `a/b` accepted; `.snap`, `.snap/x`, `a//b`, `a/./b`, `a/../b`,
  `a\b`, `""`, and a path with U+0001 rejected; `a/bc` is not under `a/b`.
- Properties: prefix-freedom is preserved by inserting a path whose ancestors
  and descendants were removed first; `isPrefixOf` is transitive and
  irreflexive-compatible with the path ordering (an ancestor always sorts before
  its descendants).

**Exit criterion.** `sbt "testOnly snap.PathsTest snap.PathsPropertyTest"` green;
mutating `isPrefixOf` to a plain `startsWith` fails the `a/bc` case.

---

### Phase 3 — Text and edit scripts (§4.4)

**Start here.**
- Read: `../SPEC.md` §4.4, lines 253–272, plus §4.3, lines 226–252.
- Builds on: `Contracts.scala` (`EditOp`, `Change`, `FileBytes`).
- Acceptance cases it moves: `06-binary-and-empty.yaml`, `23-strict-validation-matrix.yaml`, `26-portability-and-failure-safety.yaml`.

**Goal.** `Text.scala`: text detection, canonical tokenization, and edit-script
validation and application.

**SPEC references.** §4.4 (tokens, script grammar, canonicality), §4.3 (an empty
script creates an empty text file), §7.5 (text-versus-`put` selection).

**Starting status.** Absent. `EditOp` and `Change` exist as data only.

**Steps.**

1. `def isText(bytes: FileBytes): Boolean` — valid UTF-8 **and** no NUL byte.
   Use a strict `CharsetDecoder`, not `new String(...)`, which replaces invalid
   sequences silently.
2. `def tokens(bytes: FileBytes): Vector[String]` — split immediately after
   every LF, keeping the LF in the token; the empty file has no tokens. `a\r\nb`
   becomes `Vector("a\r\n", "b")`.
3. `def untokens(tokens: Vector[String]): FileBytes` — the inverse.
4. `def validate(script: Vector[EditOp], oldLength: Int): Either[SnapError, Unit]`
   — positive counts within `Limits.maxSafeInteger`; no adjacent operations of
   the same kind; no empty `insert`; every inserted token nonempty; the script
   consumes exactly `oldLength` old tokens with no implicit trailing retain; the
   result is a canonical token sequence (every token but possibly the last ends
   in LF, and no token holds an LF before its final byte). Messages must satisfy
   the `changes is empty` / `insert is empty` / `must have one operation` /
   `consumes beyond old content` / `positive safe integer` regexes in the
   catalog above.
5. `def apply(script: Vector[EditOp], old: Vector[String]): Either[SnapError, Vector[String]]`.

**Tests before completion.**

- Examples: `"a\r\nb"` tokenizes to two tokens; an empty file to none; a file
  ending without LF keeps its unterminated final token; the empty script applied
  to no tokens yields an empty file; one case per rejection message.
- Properties: `untokens ∘ tokens == id` for any bytes; `tokens ∘ untokens == id`
  for any canonical token sequence; every token but the last ends in LF; a
  validated script applied to its declared old length always succeeds and always
  yields a canonical sequence; `isText` is false for any byte sequence
  containing NUL.
- Rejection properties: a script with adjacent same-kind operations, a
  zero-count operation, or a short total retain is always `Left`.

**Exit criterion.** `sbt "testOnly snap.TextTest snap.TextPropertyTest"` green;
mutating the splitter to split *before* LF, or dropping the NUL check, fails.

---

### Phase 4 — Canonical diff (§5)

**Start here.**
- Read: `../SPEC.md` §5, lines 289–321 — the recurrence is the specification, not a hint.
- Builds on: `Text.scala` (tokenization), `Contracts.scala` (`EditOp`).
- Acceptance cases it moves: `05-diff-goldens.yaml`, `21-version-algebra.yaml`.

**Goal.** `Diff.scala`: the one deterministic token diff used by patch creation,
displayed diffs, and OT.

**SPEC references.** §5 in full.

**Starting status.** Absent.

**Steps.**

1. Implement `D(i, j)` exactly as written, then walk from `(0, 0)` with the
   documented rules: equal tokens retain 1; otherwise delete when
   `D(i+1, j) <= D(i, j+1)`; otherwise insert `B[j]`; drain the exhausted side;
   coalesce adjacent same-kind operations.
2. Start with the literal memoized recurrence. **Do not** substitute Myers or
   Hirschberg until the literal version is green and can serve as the oracle for
   a differential property; §5 permits the swap only if the output is identical
   on every input, including repeated equal lines.
3. Expose `def diff(old: Vector[String], neu: Vector[String]): Vector[EditOp]`.

**Tests before completion.**

- Examples: the `05-diff-goldens.yaml` pair — `"a\nb\na\n"` to `"b\na\na"` — as
  a unit golden, so the script is pinned before any renderer exists; empty to
  nonempty; nonempty to empty; identical inputs yield a pure retain (or an empty
  script for empty inputs); a tie case that must delete first.
- Properties: `Text.apply(diff(a, b), a) == b` for any token sequences (the
  central one); the script's insert+delete total equals `D(0, 0)`, so it is
  minimal; output is always coalesced; `diff(a, a)` never deletes or inserts.
- Differential property, once and if an optimized algorithm is introduced: it
  agrees with the literal recurrence on every generated pair, with a generator
  biased toward repeated equal lines.

**Exit criterion.** `sbt "testOnly snap.DiffTest snap.DiffPropertyTest"` green;
mutating the tie rule to `<` instead of `<=` fails the tie golden.

---

### Phase 5 — Operational transform (§6.3)

**Start here.**
- Read: `../SPEC.md` §6.3, lines 374–397, and §6.5, lines 431–440.
- Builds on: `Text.scala`, `Diff.scala`.
- Acceptance cases it moves: `22-ot-matrix.yaml`, `18-three-way-convergence.yaml`, `09-merge-text.yaml`.

**Goal.** `Ot.scala`: transform an incoming text edit through an aggregate
context edit.

**SPEC references.** §6.3 (the six-row table and its `Q insert` priority), §6.5
(the convergence guarantee this underwrites).

**Starting status.** Absent.

**Steps.**

1. `def transform(p: Vector[EditOp], q: Vector[EditOp]): Vector[EditOp]`,
   walking both streams left to right and splitting counts. Apply the rows in
   the specified precedence: `Q insert` first, then `P insert`, then the four
   retain/delete pairs.
2. Continue until both streams end, handling a trailing insertion with its
   applicable row; coalesce the output. Leave no unmatched retain or delete.

**Tests before completion.**

- Examples: one per table row, plus the concurrent-insert-at-one-cursor case
  that the `Q insert` priority decides, plus at least one three-way case
  matching `22-ot-matrix.yaml` and `18-three-way-convergence.yaml`.
- Properties: `transform(p, q)` and `p` consume the same base token count;
  `transform(p, q)` is a valid script against the token count `q` produces;
  applying it never throws; deletion consumes only base tokens, so text inserted
  by `q` always survives `transform(p, q)`.

**Exit criterion.** `sbt "testOnly snap.OtTest snap.OtPropertyTest"` green;
demoting the `Q insert` row below `P insert` fails the concurrent-insert case.

---

### Phase 6 — Repository JSON and validation (§4.1, §4.5, §3.5)

**Start here.**
- Read: `../SPEC.md` §4.1–§4.5, lines 154–288, and §3.5, lines 140–151.
- Builds on: `Versions.scala`, `Paths.scala`, `Text.scala`.
- Acceptance cases it moves: `15-repository-validation.yaml`, `23-strict-validation-matrix.yaml`, `27-history-canonicality.yaml`, `16-dot-collision.yaml`.

**Goal.** `RepositoryJson.scala` and `Validation.scala`: strict parse and
canonical serialization of the repository value, and the six validation passes.

**SPEC references.** §4.1 (schema, closure, sorting), §4.2 (dot, result,
duplicate-versus-corrupt), §4.3 (change variants and their base preconditions),
§4.5 (the six passes, in order), §3.5 (serial contributor rule).

**Starting status.** Absent. Phase 1–4 give the typed values this parses into.

**Steps.**

1. Parse with circe, then convert to `Repository` explicitly — no derived
   codecs. Reject unknown fields (`repository has unknown field: unknown`,
   `.+unknown field: extra`), non-integer numbers, and invalid typed values.
2. **Duplicate object keys need their own pass.** circe's `JsonObject` keeps the
   last value for a repeated key and reports nothing, so scan the raw text for
   duplicates before or alongside the AST conversion. Message:
   `duplicate JSON key .+`.
3. **Base64 `content` needs an explicit check.** §4.3 fixes padded RFC 4648;
   verify the alphabet and padding before handing bytes to
   `java.util.Base64.getDecoder`.
4. Serialize canonically: two-space indentation, trailing LF, the field order in
   §4.1's example, and versions as ordered `[id, revision]` arrays.
5. `Validation.scala` runs §4.5's six passes in order: schema and all typed
   values; patch sorting, one value per dot, contiguous contributor revisions;
   every patch's complete base closure and `revision = base[author] + 1`;
   acyclic causality; every change against its materialized exact base; and a
   deterministic replay of the declared frontier (this last pass calls into
   Phase 7 — implement it as a hook now and wire it when replay lands).
6. `patches` must be exactly the causal closure of `frontier`, sorted by author
   then numeric revision, with no unreachable patch (`unreachable patch: .+`).
7. §3.5: the same dot carrying structurally different patch values is
   *corruption*, not a conflict, and fails before any write. Structural equality
   here is over the parsed typed value, not the bytes — so whitespace and
   key-order differences must compare equal.
8. `def known(repo: Repository, v: Version): Boolean` — §4.1's materializable
   definition: syntactically valid, every patch selected by `n <= V[c]` exists,
   and the selected set contains the complete base of every selected patch. A
   known version need not equal the frontier or any patch result. Error:
   `unknown version: (a@x->2)`.

**Tests before completion.**

- Examples: the §4.1 sample round-trips; one case per catalog message in this
  phase; a repository whose JSON differs only in whitespace and key order parses
  equal to the canonical one; a version that is materializable but is neither
  the frontier nor a patch result is `known`.
- Properties: `parse ∘ serialize == id` for generated valid repositories;
  serialization is canonical (`serialize ∘ parse ∘ serialize == serialize`);
  validation is total.
- Rejection properties: for any generated valid repository, deleting one patch
  from the middle of a contributor's chain, renumbering a revision, adding an
  unreachable patch, or introducing a cycle is always `Left`.

**Exit criterion.** `sbt "testOnly snap.RepositoryJsonTest snap.ValidationTest
snap.ValidationPropertyTest"` green. This phase is what
`15-repository-validation.yaml`, `23-strict-validation-matrix.yaml`,
`27-history-canonicality.yaml`, and `16-dot-collision.yaml` will exercise once a
CLI exists.

---

### Phase 7 — Deterministic replay (§6.1, §6.2, §6.4, §6.5)

**Start here.**
- Read: `../SPEC.md` §6 in full, lines 322–440 — read §6.2 twice.
- Builds on: `Diff.scala`, `Ot.scala`, `Text.scala`, `Paths.scala`, `Versions.snapOrder`. Read `Diff.scala` and `Ot.scala` properly; the plan alone does not carry this phase.
- Acceptance cases it moves: `17-concurrent-creates.yaml`, `18-three-way-convergence.yaml`, `10-merge-conflicts.yaml`, `11-namespace-conflicts.yaml`, `22-ot-matrix.yaml`.

**Goal.** `Replay.scala`: materialize a version from the empty tree, resolving
concurrency deterministically and collecting warnings.

**SPEC references.** §6.1 (selection and the three-key ready ordering), §6.2
(namespace resolution first, then the four per-path cases), §6.3 (via Phase 5),
§6.4 (the six path-level rules and warning collection), §6.5 (the convergence
guarantee).

**Starting status.** Absent. This is the deepest phase; do not start it before
Phases 1–5 are green, since every bug here will otherwise look like a replay bug.

**Steps.**

1. `def materialize(patches: Set[Patch], target: Version): Either[SnapError, (Tree, SortedSet[Warning])]`.
2. Selection: every patch `(c, n)` with `n <= target[c]`; the set must contain
   every selected patch's base. If no ready patch remains before replay
   completes, the history has a cycle or a missing dependency — fail; never
   fuzzily apply.
3. Ready ordering, in this key order: Snap order of result versions, then
   unsigned UTF-8 author, then numeric revision.
4. Per patch: materialize its exact base tree `B`; let `C` be the canonical tree
   so far. **Resolve namespace conflicts for the patch as a whole first** —
   compute `S` (paths `P` makes present) and `C'` (`C` minus paths `P` deletes);
   any path in `S` with a different current ancestor or descendant in `C'` is
   installed as its authored result `T`, every conflicting current path is
   removed, and each removal emits `namespace-wins`. These decisions override
   the per-path rules.
5. For each remaining changed path, against the same `B` and `C`, in order:
   identical in `B` and `C` → apply directly; identical in `C` and `T` → keep
   (this is what collapses identical concurrent changes *before* OT); all three
   text and `P` textual → `Q = Diff.diff(B, C)`, `Ot.transform(P, Q)`, apply to
   `C`; otherwise the §6.4 ladder.
6. §6.4 ladder, in order: `C == T` keep silently; `T` absent →
   `delete-wins`; `B` present and `C` absent → `delete-wins`; `B` absent and
   both `C` and `T` present → `later-create-wins`; incoming `put` →
   `later-put-wins`; otherwise → `put-wins`.
7. Apply all path changes from one patch **together** to form the next canonical
   tree.
8. Return unique warnings sorted by path then reason. Line OT emits none.

**Tests before completion.**

- Examples: one per §6.4 rule; the identical-concurrent-change collapse with no
  warning; concurrent `a` and `a/b` (both directions); a three-patch text merge;
  the aggregate-context-edit rule — transform once against `diff(B, C)`, not
  once per historical patch.
- Properties (the ones SPEC §11 asks for by name, needing a generator for
  **valid causal patch graphs**, not arbitrary patches): every import
  permutation of one patch set yields the same frontier, patch set, warning set,
  and bytes; re-merging is a no-op; merge direction does not change the result;
  import is idempotent, commutative, and associative; the resulting tree is
  always prefix-free.

**Exit criterion.** `sbt "testOnly snap.ReplayTest snap.ReplayPropertyTest"`
green, with the permutation property running at least 200 generated graphs.
Mutate the ready ordering to plain author order and confirm the permutation
property fails.

---

### Phase 8 — Workspace and filesystem (§2, §10)

**Start here.**
- Read: `../SPEC.md` §2, lines 53–85, §7.1, lines 456–464, and §10, lines 706–735.
- Builds on: `Contracts.scala` (`Tree`, `FileBytes`), `Paths.scala`.
- Acceptance cases it moves: `01-init.yaml`, `02-init-paths.yaml`, `08-unsupported-entries.yaml`, `20-dirty-merge.yaml`, `26-portability-and-failure-safety.yaml`.

**Goal.** `Workspace.scala`: repository discovery, working-tree scan,
materialization, and the mutation ordering §10 fixes.

**SPEC references.** §2 (what is tracked, what is unsupported, clean versus
dirty), §7.1 (`init` layout and its two refusals), §10 (validate before mutate;
write files, then atomically replace metadata).

**Starting status.** Absent.

**Steps.**

1. `def discover(from: Path): Either[SnapError, Path]` — walk to the filesystem
   root looking for `.snap/`. Error: `not a Snap repository`.
2. `def scan(root: Path): Either[SnapError, Tree]` — every regular file below
   the root except `.snap/`. **Fail on a symlink, FIFO, or any other
   non-regular entry** rather than following or ignoring it:
   `unsupported working tree entry: <path>`. Use
   `Files.readAttributes(..., NOFOLLOW_LINKS)`; `Files.isRegularFile` follows
   links by default and would silently pass. `08-unsupported-entries.yaml`
   covers both a symlink and a FIFO.
3. `def install(root: Path, target: Tree): Either[SnapError, Unit]` — remove
   files blocking required directories, create the directories, write the target
   files, and remove newly empty directories, so the filesystem represents
   exactly the target path/byte map.
4. `def writeRepository(root: Path, repo: Repository): Either[SnapError, Unit]`
   — serialize to a same-directory temporary file, then `ATOMIC_MOVE` over
   `repository.json`.
5. Enforce the ordering: for `merge` and `revert`, complete parsing, validation,
   replay, the dirty check, and target-tree construction **before any write**.
   `commit` only needs the metadata replacement, since the desired files are
   already on disk.

**Tests before completion.**

- Examples against a temporary directory: discovery from a nested subdirectory;
  discovery failure at the root; a symlink and a FIFO each rejected by name; an
  install that replaces a file with a directory of the same name; an install
  that prunes a newly empty directory; `writeRepository` leaves no temporary
  file behind.
- Property: `scan ∘ install == id` for any generated prefix-free tree — the
  round-trip that proves materialization and scanning agree.
- Failure-safety example: a validation failure leaves both `repository.json` and
  the working tree byte-identical (this is what
  `26-portability-and-failure-safety.yaml` asserts).

**Exit criterion.** `sbt "testOnly snap.WorkspaceTest"` green. Keep every test in
a temporary directory; nothing here may read the process working directory.

---

### Phase 9 — Configuration (§8)

**Start here.**
- Read: `../SPEC.md` §8, lines 661–683, and §7.2, lines 465–471.
- Builds on: `Versions.contributorId`, `Contracts.Configuration`.
- Acceptance cases it moves: `03-configuration.yaml`, `25-config-version-path-boundaries.yaml`.

**Goal.** `Config.scala`: local-over-global resolution.

**SPEC references.** §8 in full; §7.2 (the `config` command's writes).

**Starting status.** Absent. `Configuration` exists as data.

**Steps.**

1. Read `.snap/config.json` first; if it provides an ID, do **not** read global
   configuration. Otherwise read `$HOME/.snapconfig.json`. A missing file means
   no value; a malformed file, a duplicate or unknown field, or an invalid ID in
   a file that *is* read is an error.
2. If `$HOME` is absent, global configuration is unavailable — not an error.
3. Take `HOME` as an injected parameter, not from `System.getenv` at the point
   of use; `03-configuration.yaml` overrides it.
4. Writing replaces the file and preserves no unknown fields.
5. Only `commit` and `revert` require an ID:
   `contributor.id is required; configure it locally or globally`.

**Tests before completion.**

- Examples: local wins over global; local present but without an ID falls
  through to global; a malformed local file is an error even when global is
  valid; absent `$HOME` yields no value rather than a failure; an invalid ID in
  a read file is an error.

**Exit criterion.** `sbt "testOnly snap.ConfigTest"` green.

---

### Phase 10 — Presentation and error rendering (§7.11, §10)

**Start here.**
- Read: `../SPEC.md` §7.11, lines 594–660, and §10, lines 706–735.
- Builds on: `Streams.scala` (the injected TTY flags), `Contracts.Presentation`.
- Acceptance cases it moves: `28-terminal-presentation.yaml`.

**Goal.** `Presentation.scala`: presentation selection and every styled record.

**SPEC references.** §7.11 (the `SNAP_COLOR` / `NO_COLOR` table, `S(n, text)`,
and each layout family), §10 (`snap: <detail>` and the exit codes).

**Starting status.** Absent. `Streams` already carries injected TTY flags and
`Presentation` exists as an enum.

**Steps.**

1. `def select(snapColor: Option[String], noColor: Option[String], isTty: Boolean): Either[SnapError, Presentation]`
   — `always` forces terminal mode on both streams and overrides `NO_COLOR`;
   `never` forces plain; unset or `auto` gives terminal mode per stream when
   that stream is a TTY **unless `NO_COLOR` is present in any form, including
   empty**; any other value is `SNAP_COLOR must be auto, always, or never`,
   raised **before command execution** and printed plain, because no valid
   presentation was selected.
2. `def s(code: Int, text: String): String` = `ESC[` + code + `m` + text +
   `ESC[0m`. This is the only escape-emitting function in the codebase, and test
   expectations must be built from it.
3. One renderer per §7.11 family: the `✓ <label> <version>` success line for
   `init` / `commit` / `revert` / `merge`; the `status` header, clean line, and
   the `(32,"+","added")` / `(31,"−","deleted")` / `(33,"~","modified")` rows —
   note the deleted symbol is U+2212 MINUS SIGN, not a hyphen; the two-line
   `log` entry with a blank line between entries; the diff line-prefix styling;
   `--version`; `⚠` warnings; and `✗` errors.
4. **`snap --version` prints `snap 1.0.0`.** Hold that semver in one constant,
   independent of `build.sbt`'s `version`.
5. The `--serve` startup URL is always plain, and `config` is always silent.

**Tests before completion.**

- Examples: every row of the `SNAP_COLOR` × `NO_COLOR` × TTY table, including
  the TTY combinations the YAML harness cannot reach — SPEC §11 requires
  stdout and stderr to be exercised independently, which is why `Streams` takes
  two flags.
- Examples: byte-exact goldens for each layout family, built with `s(...)` and
  cross-checked against `28-terminal-presentation.yaml`.
- Property: stripping SGR sequences from any terminal-mode record reproduces the
  plain record, except where §7.11 names a deliberate layout difference (the
  `status` header and the `log` entry are the two).
- Property: plain-mode output never contains an ESC byte.

**Exit criterion.** `sbt "testOnly snap.PresentationTest"` green, with the
`auto`-on-TTY cases present and passing — they are the ones no acceptance case
can cover.

---

### Phase 11 — Diff rendering and the command layer (§7.1–§7.8)

**Start here.**
- Read: `../SPEC.md` §7.1–§7.8, lines 456–581, plus §7.6's block format.
- Builds on: every module above — this is the phase that assembles them.
- Acceptance cases it moves: `04-commit-status-log.yaml`, `05-diff-goldens.yaml`, `07-revert.yaml`, `09-merge-text.yaml`, `14-cli-errors.yaml`, `20-dirty-merge.yaml`, `24-cli-grammar-matrix.yaml`.

**Goal.** `DiffRender.scala` and `Commands.scala`: one function per command,
plus the unified-style diff renderer.

**SPEC references.** §7.1–§7.8, §7.6's block format, §10's exit codes.

**Starting status.** Absent. This is the first phase whose completion is visible
to the public suite.

**Steps.**

1. `DiffRender.scala` — for each changed path sorted by path: the `--- a/<path>`
   / `+++ b/<path>` headers with `/dev/null` on an absent side; the
   `@@ -1,<old-count> +1,<new-count> @@` header, always starting at 1 on both
   sides; the operations from §5's canonical script; and, for a token without a
   final LF, an added LF followed by `\ No newline at end of file`. Binary
   changes print the single `Binary files a/<path> and b/<path> differ` line. No
   differences means empty stdout and success.
2. `init` — create `.snap/` under an existing or newly created directory; refuse
   reinitialization and refuse a target inside an existing repository; print
   `()`.
3. `config` — validate before writing; local by default, `--global` to
   `$HOME/.snapconfig.json` with no repository required; silent on success.
4. `status` — the version line, then `A`/`M`/`D` rows sorted by path.
5. `log` — reverse canonical integration order, one tab-separated
   `<result-version>\t<author>\t<message>` line, escaping backslash, tab, and LF
   as `\\`, `\t`, `\n` **in that order**.
6. `commit` — require an ID and a dirty tree; reject a message over
   `Limits.maxCommitMessageBytes` (`invalid commit message`); diff the complete
   current tree against the complete working tree; choose `text` when the new
   content is text and the old path is absent or text, otherwise `put`, and
   `delete` for removed paths; base the patch on the current frontier; replace
   `repository.json` atomically; print the new version. A clean tree is
   `working tree is clean`.
7. `diff` — no arguments compares current versus working tree; two operands
   compare two locally known versions; `--repo` resolves `new` in another local
   or HTTP repository without importing it, and additionally compares every dot
   present in both repositories, failing as corrupt if the parsed values differ.
   Validate every repository and version before producing output. Grammar
   violations here use the `usage: snap diff …` family, not
   `invalid command or arguments`.
8. `revert` — require an ID, a clean tree, and a locally known target; author one
   patch with message `revert to <version>`; install the target; print the
   **new** version. Equal trees give `target tree is already current`. Never
   remove patches or move the frontier backward, and note that the generated
   message may exceed 4096 bytes because it contains a complete version.
9. `merge` — require a clean tree but no ID; load and validate the other
   repository; union the patches and join the frontiers; replay; install;
   update. Create no patch. Print only warnings **present in the joined replay
   and absent from the pre-merge local replay**, one `warning: auto-resolved
   <path>: <reason>` line each, to stderr; print the joined version to stdout.
   Merging contained history changes nothing and prints the unchanged version.
10. `Cli.scala` — replace the stub with the §7 positional grammar. Each option
    appears at most once and only in its documented position; unknown options,
    extra operands, and missing values are `invalid command or arguments`,
    except within `diff`. Resolve `SNAP_COLOR` **before** dispatch. Map results
    to exit 0 / 1, reserving 2 for genuinely unanticipated failures.

**Tests before completion.**

- Unit examples for `DiffRender` against `05-diff-goldens.yaml`'s expectations,
  and for the CLI grammar against every line of `24-cli-grammar-matrix.yaml`.
- Command tests drive a temporary directory through `Cli.run` with captured
  `Streams`, so they assert bytes without spawning a process.

**Exit criterion.** `../verify --lang scala` passes every case that does not
involve HTTP — **24 of 28**. The four that need Phase 12 are
`12-http-server.yaml`, `13-http-client.yaml`,
`26-portability-and-failure-safety.yaml` (its malformed-remote half uses a stub
server), and `28-terminal-presentation.yaml` (its final steps check that the
`--serve` URL stays plain under `SNAP_COLOR=always`). Run it and read the count.

---

### Phase 12 — HTTP (§7.9, §9)

**Start here.**
- Read: `../SPEC.md` §7.9, lines 582–589, and §9, lines 684–705.
- Builds on: `RepositoryJson.scala`, `Validation.scala`, `Streams.scala`.
- Acceptance cases it moves: `12-http-server.yaml`, `13-http-client.yaml`, `26-portability-and-failure-safety.yaml`, `28-terminal-presentation.yaml`.

**Goal.** `Http.scala`: the read-only server and the single-GET client.

**SPEC references.** §7.9 (`--serve` startup, binding, port, signals), §9 (the
one resource, its methods and status codes, and the client's rules).

**Starting status.** Absent.

**Steps.**

1. Server: validate and snapshot the repository at startup, then serve that
   snapshot only. Bind `127.0.0.1` exclusively; the port defaults to 8765 and
   `0` asks the OS. Print and **flush** `http://127.0.0.1:<actual-port>/repository.json`
   — always plain — before serving; `12-http-server.yaml` matches that line with
   a regex to learn the port. `GET` and `HEAD` on `/repository.json` return the
   snapshot with `Content-Type: application/json; charset=utf-8` (`HEAD` without
   a body); other paths return 404; other methods return 405 with
   `Allow: GET, HEAD`. Exit 0 on SIGINT or SIGTERM. An invalid port is
   `invalid port: 65536`.
2. Client: for an operand starting with `http://` or `https://`, perform exactly
   one GET of that exact URL, require status 200, parse the body as a repository
   value, and validate it normally. No redirects, no auth, no caching.

**Tests before completion.**

- Examples on an ephemeral port: 200 with the exact content type; `HEAD` with
  the same status and headers and no body; 404 and 405 with `Allow`; the
  snapshot does not change when the repository does after startup.
- Example: a client pointed at a malformed body fails validation and mutates
  nothing.

**Exit criterion.** `../verify --lang scala` — **all 28 cases pass**.

---

### Phase 13 — Final verification

**Start here.**
- Read: `tracking/progress.md` — what is already checked, and against what evidence.
- Builds on: the whole tree.
- Acceptance cases it moves: all 28.

Run each and read the output; a run reporting `Total 0` or `No tests to run` is
not a pass.

```bash
sbt scalafmtAll                  # formatter, no diff afterwards
sbt "testOnly snap.*"            # examples + properties, both suites
sbt assembly                     # target/scala-3.9.0/snap-assembly-0.1.0.jar
../verify --lang scala           # all 28 public cases
```

Then the checks the harness structurally cannot make:

- `SNAP_COLOR=auto` against a real terminal, and against a terminal with
  `NO_COLOR` set — the suite has no PTY, so verify by hand as well as by unit
  test.
- `snap --serve` interrupted with Ctrl-C exits 0.
- A large binary file survives `commit` and `revert` byte-for-byte.

Finally, re-read the three IRON RULES in `CLAUDE.md`: docs updated in the same
change; any proven, generalisable pattern offered as a skill; bulky subtasks run
in subagents.

## Ordering rationale

Two decisions in the sequence above change implementation choices rather than
just scheduling:

- **Replay (Phase 7) comes after diff and OT (Phases 4–5), not with them.**
  §6.2 defines integration in terms of `diff(B, C)` and the §6.3 transform; if
  those are unverified, every replay divergence looks like a replay bug and the
  §6.5 permutation property gives no signal about where the fault is.
- **The literal §5 recurrence ships before any optimized diff.** §5 defines the
  output by the recurrence and the deletion-on-tie rule, and a faster algorithm
  is conformant only if it agrees everywhere. Having the literal version first
  turns that from an assertion into a differential property.
