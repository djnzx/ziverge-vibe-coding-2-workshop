package snap

import scala.collection.immutable.SortedMap

/** SPEC §6.1/§6.2/§6.4 examples, every one worked by hand against a scenario the public suite actually runs: `10-merge-conflicts.yaml` for the six path-level
  * rules, `11-namespace-conflicts.yaml` for namespace resolution in both directions, and `18-three-way-convergence.yaml` for the aggregate context edit.
  *
  * The ready ordering (§6.1) decides which side is "later" in every one of them, so the expected values below are only correct for the order the spec fixes:
  * Snap order of result versions first, then author, then revision.
  */
class ReplayTest extends munit.FunSuite:

  private val seed  = ContributorId("seed@x")
  private val alice = ContributorId("alice@x")
  private val bob   = ContributorId("bob@x")

  private def p(text: String): TrackedPath = TrackedPath(text)

  private def bytes(text: String): FileBytes = FileBytes.utf8(text)

  private def version(pairs: (ContributorId, Long)*): Version = Version(SortedMap.from(pairs))

  private def create(path: String, content: String): Change =
    Change.Text(p(path), Diff.diff(Vector.empty, Text.tokens(bytes(content))))

  private def edit(path: String, from: String, to: String): Change =
    Change.Text(p(path), Diff.diff(Text.tokens(bytes(from)), Text.tokens(bytes(to))))

  private def put(path: String, content: FileBytes): Change = Change.Put(p(path), content)

  private def patch(author: ContributorId, revision: Long, base: Version, changes: Change*): Patch =
    Patch(author, revision, base, "m", changes.toVector.sortBy(_.targetPath))

  /** Replays the join of every patch's result — the frontier a repository holding exactly these patches would carry. Warnings come back in their canonical
    * order so a test can assert the §6.4 sort, not just the set.
    */
  private def replayAll(patches: Patch*): (Tree, Vector[Warning]) =
    val all      = patches.toVector
    val frontier = all.map(_.result).foldLeft(Version.empty)(Versions.join)
    Replay.materialize(all, frontier) match
      case Right((tree, warnings)) => (tree, warnings.toVector)
      case Left(error)             => fail(s"replay failed: ${error.detail}")

  // ---- the six §6.4 path-level rules, shaped like 10-merge-conflicts.yaml -------------
  //
  // seed@x->1 lays down the base; alice@x->1 and bob@x->1 both fork from it. Their result
  // versions are (alice@x->1,seed@x->1) and (bob@x->1,seed@x->1); Snap order compares the
  // alice@x component first, so bob integrates before alice and *alice* is the canonically
  // later patch in every case below.

  private val binaryLeft  = FileBytes(Array[Byte](0, 1))
  private val binaryRight = FileBytes(Array[Byte](0, -1))

  private val seedPatch = patch(
    seed,
    1,
    Version.empty,
    create("clean.txt", "base\n"),
    create("delete.txt", "base\n"),
    create("dropped.txt", "base\n"),
    create("gone.txt", "base\n"),
    create("identical.txt", "base\n"),
    create("incompatible.txt", "base\n"),
    create("later-put.txt", "base\n")
  )

  private val bobPatch = patch(
    bob,
    1,
    version(seed -> 1L),
    Change.Delete(p("delete.txt")),
    edit("dropped.txt", "base\n", "right\n"),
    edit("identical.txt", "base\n", "same\n"),
    put("incompatible.txt", binaryRight),
    edit("later-put.txt", "base\n", "right text\n"),
    create("created.txt", "bob\n")
  )

  private val alicePatch = patch(
    alice,
    1,
    version(seed -> 1L),
    edit("clean.txt", "base\n", "left\n"),
    edit("delete.txt", "base\n", "left\n"),
    Change.Delete(p("dropped.txt")),
    Change.Delete(p("gone.txt")),
    edit("identical.txt", "base\n", "same\n"),
    edit("incompatible.txt", "base\n", "left text\n"),
    put("later-put.txt", binaryLeft),
    create("created.txt", "alice\n")
  )

  private lazy val conflicts: (Tree, Vector[Warning]) = replayAll(seedPatch, bobPatch, alicePatch)

  test("§6.2 case 1: a path identical in B and C takes the authored change directly, with no warning"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("clean.txt")), Some(bytes("left\n")))
    assertEquals(warnings.filter(_.path == p("clean.txt")), Vector.empty)

  test("§6.2 case 1: an uncontested delete removes the path with no warning"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("gone.txt")), None)
    assertEquals(warnings.filter(_.path == p("gone.txt")), Vector.empty)

  test("§6.2 case 2: identical concurrent changes collapse before OT, with no warning"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("identical.txt")), Some(bytes("same\n")))
    assertEquals(warnings.filter(_.path == p("identical.txt")), Vector.empty)

  test("§6.4 rule 2: an incoming delete of a concurrently modified path wins"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("dropped.txt")), None)
    assertEquals(warnings.filter(_.path == p("dropped.txt")), Vector(Warning(p("dropped.txt"), Reason.DeleteWins)))

  test("§6.4 rule 3: an earlier concurrent delete beats the incoming edit"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("delete.txt")), None)
    assertEquals(warnings.filter(_.path == p("delete.txt")), Vector(Warning(p("delete.txt"), Reason.DeleteWins)))

  test("§6.4 rule 4: the canonically later concurrent create wins"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("created.txt")), Some(bytes("alice\n")))
    assertEquals(warnings.filter(_.path == p("created.txt")), Vector(Warning(p("created.txt"), Reason.LaterCreateWins)))

  test("§6.4 rule 5: an incoming put replaces whatever the concurrent side left behind"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("later-put.txt")), Some(binaryLeft))
    assertEquals(warnings.filter(_.path == p("later-put.txt")), Vector(Warning(p("later-put.txt"), Reason.LaterPutWins)))

  test("§6.4 rule 6: an incoming text edit loses to incompatible non-text current content"):
    val (tree, warnings) = conflicts
    assertEquals(tree.get(p("incompatible.txt")), Some(binaryRight))
    assertEquals(warnings.filter(_.path == p("incompatible.txt")), Vector(Warning(p("incompatible.txt"), Reason.PutWins)))

  test("§6.4: warnings are unique and sorted by path, then reason"):
    val (_, warnings) = conflicts
    assertEquals(
      warnings,
      Vector(
        Warning(p("created.txt"), Reason.LaterCreateWins),
        Warning(p("delete.txt"), Reason.DeleteWins),
        Warning(p("dropped.txt"), Reason.DeleteWins),
        Warning(p("incompatible.txt"), Reason.PutWins),
        Warning(p("later-put.txt"), Reason.LaterPutWins)
      )
    )

  // ---- §6.2 namespace resolution, both directions, from 11-namespace-conflicts.yaml -----

  test("§6.2 namespace: the canonically later patch creating an ancestor removes the concurrent descendant"):
    // alice@x->1 creates `a`; bob@x->1 creates `a/b`. Snap order puts bob first, so alice's `a` is the later namespace.
    val (tree, warnings) = replayAll(
      patch(alice, 1, Version.empty, create("a", "ancestor\n")),
      patch(bob, 1, Version.empty, create("a/b", "descendant\n"))
    )
    assertEquals(tree, SortedMap(p("a") -> bytes("ancestor\n")): Tree)
    assertEquals(warnings, Vector(Warning(p("a/b"), Reason.NamespaceWins)))

  test("§6.2 namespace: the canonically later patch creating a descendant removes the concurrent ancestor"):
    // bob@x->1 creates `x`; alice@x->1 creates `x/y`. Snap order puts bob first, so alice's `x/y` is the later namespace.
    val (tree, warnings) = replayAll(
      patch(bob, 1, Version.empty, create("x", "ancestor\n")),
      patch(alice, 1, Version.empty, create("x/y", "descendant\n"))
    )
    assertEquals(tree, SortedMap(p("x/y") -> bytes("descendant\n")): Tree)
    assertEquals(warnings, Vector(Warning(p("x"), Reason.NamespaceWins)))

  // ---- three-way text merge, from 18-three-way-convergence.yaml -------------------------

  private val a2 = ContributorId("a@x")
  private val b2 = ContributorId("b@x")
  private val c2 = ContributorId("c@x")

  private val storySeed = patch(seed, 1, Version.empty, create("story.txt", "start\nend\n"))
  private val storyA    = patch(a2, 1, version(seed -> 1L), edit("story.txt", "start\nend\n", "start\nA\nend\n"))
  private val storyB    = patch(b2, 1, version(seed -> 1L), edit("story.txt", "start\nend\n", "start\nB\nend\n"))
  private val storyC    = patch(c2, 1, version(seed -> 1L), edit("story.txt", "start\nend\n", "end\n"))

  test("§6.2 case 3: three concurrent text edits merge by line OT, emitting no warning"):
    val (tree, warnings) = replayAll(storySeed, storyA, storyB, storyC)
    assertEquals(tree.get(p("story.txt")), Some(bytes("B\nA\nend\n")))
    assertEquals(warnings, Vector.empty)

  test("§6.2 case 3: the context the last patch sees is the aggregate of both earlier integrations"):
    // Ready order is seed, c, b, a — so by the time a@x->1 is integrated the canonical tree already carries c's delete *and* b's insert.
    val (tree, warnings) = replayAll(storySeed, storyB, storyC)
    assertEquals(tree.get(p("story.txt")), Some(bytes("B\nend\n")))
    assertEquals(warnings, Vector.empty)

  test("§6.3 is applied once against the aggregate context edit diff(B, C), not once per historical patch"):
    val base     = Text.tokens(bytes("start\nend\n"))
    val context  = Text.tokens(bytes("B\nend\n"))
    val incoming = Diff.diff(base, Text.tokens(bytes("start\nA\nend\n")))
    assertEquals(Text.apply(Ot.transform(incoming, Diff.diff(base, context)), context), Right(Text.tokens(bytes("B\nA\nend\n"))))
    val (tree, _) = replayAll(storySeed, storyA, storyB, storyC)
    assertEquals(tree.get(p("story.txt")), Some(bytes("B\nA\nend\n")))

  // ---- §6.2/§6.4 whose warnings a replay reports ----------------------------------------

  test("§6.4: warnings come from this replay's own integrations, never from the nested replay of a patch's base"):
    // z@x->1 is authored on top of the merged state (a@x->1,b@x->1,seed@x->1), whose own replay resolves `f` as `later-put-wins`. The target adds d@x->1,
    // which deletes `f` and integrates *before* both, so in this replay `f` resolves as `delete-wins` twice and `later-put-wins` never happens at all.
    // Ready order is seed, d, b, a, z.
    val d      = ContributorId("d@x")
    val z      = ContributorId("z@x")
    val a      = ContributorId("a@x")
    val b      = ContributorId("b@x")
    val binary = FileBytes(Array[Byte](0, 7))

    val root    = patch(seed, 1, Version.empty, create("f", "base\n"))
    val putter  = patch(a, 1, version(seed -> 1L), put("f", binary))
    val editor  = patch(b, 1, version(seed -> 1L), edit("f", "base\n", "b\n"))
    val deleter = patch(d, 1, version(seed -> 1L), Change.Delete(p("f")))
    val onMerge = patch(z, 1, version(a -> 1L, b -> 1L, seed -> 1L), create("g", "z\n"))

    // The base z@x->1 was authored against does resolve `f` as later-put-wins, on its own.
    assertEquals(replayAll(root, putter, editor)._2, Vector(Warning(p("f"), Reason.LaterPutWins)))

    val (tree, warnings) = replayAll(root, putter, editor, deleter, onMerge)
    assertEquals(tree, SortedMap(p("g") -> bytes("z\n")): Tree)
    assertEquals(warnings, Vector(Warning(p("f"), Reason.DeleteWins)))

  // ---- §6.1 selection failures ----------------------------------------------------------

  test("§6.1: a target selecting a patch whose base is absent is not materializable"):
    val orphan = patch(alice, 1, version(bob -> 1L), create("f", "x\n"))
    assertEquals(
      Replay.materialize(Vector(orphan), version(alice -> 1L, bob -> 1L)),
      Left(SnapError("unknown version: (alice@x->1,bob@x->1)"))
    )

  test("§6.1: a history whose patches never become ready is rejected rather than fuzzily applied"):
    val cycleA = patch(alice, 1, version(bob -> 1L), create("f", "a\n"))
    val cycleB = patch(bob, 1, version(alice -> 1L), create("g", "b\n"))
    assertEquals(
      Replay.materialize(Vector(cycleA, cycleB), version(alice -> 1L, bob -> 1L)),
      Left(SnapError("cyclic or incomplete patch history"))
    )
