# Snap — Scala

A Scala 3 implementation of [Snap](../README.md). Behavior is defined by
[`SPEC.md`](../SPEC.md); this document covers only how the Scala workspace is
built, tested, and organised.

## Toolchain

| Component  | Version | Notes                                              |
|------------|---------|----------------------------------------------------|
| sbt        | 2.0.8   | pinned in `project/build.properties`               |
| Scala      | 3.9.0   | `ThisBuild / scalaVersion`                         |
| JDK        | 22+     | verified on 25 and 26; §7.11 `auto` detection uses `Console.isTerminal` |
| circe-parser | 0.14.16 | JSON AST and parser for `repository.json` and configuration |
| MUnit      | 1.3.6   | example-based tests                                |
| ScalaCheck | 1.20.0  | property-based tests, via munit-scalacheck 1.3.1   |

Production code otherwise uses only the JDK standard library:

- `java.nio.file` — repository discovery, working-tree scan, materialization,
  and the same-directory temporary file that SPEC §10 requires for the atomic
  `repository.json` replacement.
- `java.util.Base64` — SPEC §4.3's padded RFC 4648 `put` content.
- `com.sun.net.httpserver` — the read-only server in SPEC §7.9/§9. It ships with
  the JDK, binds where it is told, and needs no dependency.
- `java.net.http.HttpClient` — the single GET a remote repository operand makes.

Two deliberate departures from the TabbyShell Scala workspace, which shares this
toolchain:

- **No `fansi`.** SPEC §7.11 fixes the exact bytes of every styled record as
  `ESC[<n>m<text>ESC[0m`. A library that models SGR state and emits minimal
  transitions does not reproduce that form, and the acceptance suite compares
  bytes. `Presentation.scala` builds the sequences directly and is the only file
  permitted to.
- **No command-line option library.** SPEC §7 gives a positional grammar in
  which each option appears at most once and only in the position shown. A
  general parser accepts more than that, so `Cli.scala` matches the argument
  vector by hand.

## Layout

```
build.sbt
project/
  build.properties           sbt 2.0.8
  plugins.sbt                sbt-assembly, sbt-scalafmt, sbt-wartremover
src/main/scala/Main.scala    entry point — see the note below
src/main/scala/snap/         implementation (package `snap`)
src/test/scala/snap/         tests, mirroring the implementation
```

`Main.scala` sits directly under `src/main/scala/` rather than in the package
directory. `../run` decides whether a Scala implementation exists by testing
exactly that path, and reads its mtime to pick the freshest implementation when
`--lang` is omitted. Everything else lives in `src/main/scala/snap/`.

## Build and run

```bash
sbt compile
sbt assembly                                  # fat JAR
sbt "run --version"

../../snap/run --lang scala --version         # via the shared launcher
```

`assembly` writes `target/scala-3.9.0/snap-assembly-0.1.0.jar`. That path is
pinned in `build.sbt`: sbt 2 defaults to `target/out/jvm/...`, which neither
`../run` nor `../run_tests` scans — both glob
`target/scala-*/*-assembly-*.jar`. Changing `name`, `version`, or
`assemblyOutputPath` breaks the launcher and the verifier together.

`build.sbt`'s `version` names that JAR and nothing else. The semver `snap
--version` reports is **`1.0.0`**, fixed by
`../tests/28-terminal-presentation.yaml`; keep it in its own constant rather
than deriving it from the build.

## Testing

Two layers.

### 1. Unit tests — MUnit examples and ScalaCheck properties

```bash
sbt "testOnly snap.*"                  # the whole suite, examples + properties
sbt "testOnly snap.ContractsTest"      # one suite
```

> **`sbt test` is not the full suite here.** sbt 2.0.8 routes `test` through
> `testQuick`, which skips suites it considers unchanged and prints
> `No tests to run for Test / testQuick` — and that record survives `clean`.
> Run the suite through `testOnly`, which always executes.

Write them test-first: the failing test, run it, confirm it fails for the right
reason, then the smallest code that passes.

Use an **example** when the claim is "for *this* input, exactly these bytes" —
the golden diff for repeated lines, the exact text of `snap: SNAP_COLOR must be
auto, always, or never`. Use a **property** when the claim is "for *any* …".
Snap is unusually rich in universal claims, and SPEC §11 asks for them
explicitly:

| Area                | Property                                                        |
|---------------------|-----------------------------------------------------------------|
| versions (§3.3)     | `join` is idempotent, commutative, associative, and a least upper bound |
| versions (§3.3)     | exactly one of equal / before / after / concurrent holds for any pair |
| Snap order (§3.4)   | a total order that extends causal order                          |
| canonical diff (§5) | `apply(diff(a, b), a) == b` for any token sequences              |
| canonical diff (§5) | the script is minimal in inserts + deletes, and ties delete first |
| edit scripts (§4.4) | every produced script consumes its whole old sequence and coalesces |
| OT (§6.3)           | both scripts consume the same base token count; no unmatched op remains |
| replay (§6.5)       | any import permutation of one patch set yields the same frontier, patches, warnings, and bytes |
| replay (§6.5)       | re-merging is a no-op and merge direction does not change the result |
| paths (§2)          | tracked trees stay prefix-free by segment through replay         |
| presentation (§7.11)| stripping SGR sequences from terminal output reproduces the plain bytes, except where §7.11 names a layout difference |

Two Snap-specific generator notes, both learned here:

- **`Arbitrary[String]` is not a Unicode generator.** It essentially never emits
  a supplementary character or one from U+E000..U+FFFF — exactly the range where
  UTF-16 order and UTF-8 byte order disagree. `ContractsPropertyTest.pathText`
  draws code points from a pool that straddles every UTF-8 length boundary. A
  property using the default generator passed a `compareTo` ordering that the
  example test caught; the strengthened one catches it too.
- **Generate causal patch graphs, not patches.** SPEC §11 asks for permutation
  properties, which need a *valid* history: one patch per dot, contiguous
  revisions, bases present. Build the graph, then permute the import order.
  `ReplayPropertyTest` grows one patch at a time from a pool of versions already
  known to be materializable, joining a random subset of that pool for each new
  base; joins of materializable versions are materializable, so validity is
  structural rather than filtered for.
- **A permutation property only falsifies order-*dependence*.** Replacing §6.1's
  ready ordering with a different but still deterministic one (plain author
  order, say) leaves permutation invariance perfectly true — the golden examples
  are what catch it. Only a selection that reads arrival order, such as taking
  the ready set's head, makes the permutation property fail. Both mutations are
  recorded below because they falsify different things.

Prove the suite has teeth by mutation: break an invariant on purpose, confirm
the tests fail, revert. See `.claude/skills/mutation-checking-tests/` at the
repository root. Recorded so far:

| Mutation | Tests it fails |
|---|---|
| `Contracts`: UTF-16 path order instead of unsigned UTF-8 | 2 |
| `Contracts`: `FileBytes` compares by reference | 3 |
| `Contracts`: a patch result advances the wrong component | 1 |
| `Versions`: echo untrusted text raw in error details | 1 |
| `Versions`: accept leading zeroes | 2 |
| `Versions`: narrow revisions with `toLong` instead of `BigInt` | 2 |
| `Versions`: drop the noncanonical-ordering check | 2 |
| `Versions`: drop the duplicate-contributor check | 2 |
| `Versions`: fold `Concurrent` into `Before` | 5 |
| `Versions`: allow comma and parens in ids | 2 |
| `Versions`: `split(",")` without keeping trailing empty fields | 1 |
| `Versions`: allow an explicit zero revision | 3 |
| `Versions`: read the Snap-order union descending | 3 |
| `Versions`: `compare` ignores contributors absent from the left | 7 |
| `Versions`: `join` takes the minimum | 4 |
| `Paths`: `isPrefixOf` as a plain `startsWith` | 7 |
| `Paths`: `prefixFree` via sorted-adjacency instead of pairwise | 1 |
| `Text`: `isText` drops the explicit NUL-byte check | 2 |
| `Text`: `tokens` splits before LF instead of after | 4 |
| `Ot`: demote the `Q insert` row below `P insert` | 4 |
| `Replay`: ready ordering by author alone, dropping §6.1's Snap-order key | 9 |
| `Replay`: take the ready set's head instead of its least element | 2 |
| `Replay`: resolve namespace conflicts per path instead of per patch | 7 |
| `Replay`: drop §6.2's identical-`C`-and-`T` collapse before OT | 1 |
| `Replay`: swap §6.4's `later-put-wins` and `put-wins` rules | 3 |
| `Replay`: fold a nested base replay's warnings into the outer set | 1 |

The last `Replay` row is there because the mutation **survived at first** — no
test noticed a base replay's warnings leaking into the result. That was a real
gap, not an equivalent mutant: a base can resolve `f` as `later-put-wins` while
the replay containing it sees an earlier concurrent delete and resolves
`delete-wins`, never reaching that rule at all. `ReplayTest` now builds exactly
that history, and the mutation fails it.

One mutation **survived** and is worth recording because it is not a gap:
dropping the explicit `.sorted` from Snap order's union changes nothing, because
`SortedMap.keySet` is a `TreeSet` and `union` preserves it — an equivalent
mutant. The response was to make that guarantee structural rather than
accidental (`contributors` now returns `SortedSet`) and to add an oracle
property that pins *which* order §3.4 asks for, since the algebraic properties
hold for any deterministic total order.

### 2. Cross-language conformance (YAML)

```bash
../verify --lang scala
```

Builds the assembly and runs the 28-case public suite in [`../tests/`](../tests)
against it. This is the contract shared with the TypeScript and Rust
implementations; a green unit suite does not imply conformance.

One requirement the YAML suite structurally cannot cover: it captures both
candidate streams through pipes and offers no portable PTY, so it never
exercises `SNAP_COLOR=auto` against a TTY. SPEC §11 therefore requires this
implementation to unit-test `auto` selection for TTY and non-TTY stdout and
stderr independently. That is why `Streams` carries injected `stdoutIsTty` /
`stderrIsTty` flags instead of asking the JVM at the point of use.

## Planning

[`tracking/plan.md`](tracking/plan.md) sequences the implementation into twelve
phases plus a verification phase, each with its binding SPEC clauses, the tests
that gate it, and the acceptance cases it unlocks.
[`tracking/progress.md`](tracking/progress.md) is the live checklist.

## Style

```bash
sbt scalafmtAll
```

WartRemover fails the build on `return`, `asInstanceOf`, `isInstanceOf`, and
`null`. Errors are values (`Either[SnapError, _]`), not exceptions.

## Keeping the docs honest

**IRON RULE: any change to an approach, convention, code-style recommendation,
tool, or version is not done until the docs that state it are updated in the
same change.**

- `README.md` (this file) — toolchain, versions, commands, layout, testing strategy
- [`CLAUDE.md`](CLAUDE.md) — the working agreement: how to work here, what "done" means
- [`AGENTS.md`](AGENTS.md) — module decomposition and per-language conventions
- [`../SPEC.md`](../SPEC.md) — behavior binding all three implementations; change deliberately

A rule stated in more than one of these must be changed in all of them.
Correcting a documented statement that turns out to be wrong is part of the fix,
not an optional tidy-up: a doc that lies costs more than no doc at all.

**IRON RULE: a pattern that proves itself and generalises gets offered as a
skill.** Propose it — the decision to add a skill is the human's.

**IRON RULE: bulky subtasks run in subagents.** When a subtask's byproduct dwarfs
its answer — broad code sweeps, long conformance logs, exploratory reads — run it
in a subagent so the transcript stays out of the main thread.
