package snap

import java.nio.charset.StandardCharsets
import org.scalacheck.{Gen, Prop}
import org.scalacheck.rng.Seed

/** SPEC §6.5 properties, over a generator for **valid causal patch graphs** — not arbitrary patches. That distinction is the whole phase: a permutation
  * property over patches that do not form a real history proves nothing, because replay refuses them all identically.
  *
  * The generator grows a history one patch at a time. It keeps a pool of versions that are already known to be materializable (`Version.empty` plus every
  * patch's result), bases each new patch on the join of a random subset of that pool — joins of materializable versions are materializable — and forces the
  * author's own latest result into that base so the dot is always fresh and `revision = base[author] + 1` holds by construction. Two of the four paths it draws
  * from are `a` and `a/b`, so concurrent branches routinely collide in the namespace as well as in content.
  *
  * One deliberate circularity: choosing a *valid* change needs the patch's exact base tree, and the only thing that can compute one is `Replay.materialize`
  * itself. The check that keeps this honest is `Validation.validate` on the finished repository — it re-derives every base independently of replay's own
  * bookkeeping and re-checks every change against it — plus the coverage test below, which fails if the generator stops producing genuine conflicts.
  */
class ReplayPropertyTest extends munit.ScalaCheckSuite:

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  // ---- the graph generator ------------------------------------------------------------

  private val authors = Vector("a@x", "b@x", "c@x").map(ContributorId.apply)

  /** `a` and `a/b` are a namespace conflict waiting to happen; `f` is unrelated to every other path, which is what makes it a safe fallback. */
  private val pool = Vector("a", "a/b", "f", "t").map(TrackedPath.apply)

  private val fallbackPath = TrackedPath("f")

  private def uniqueText(dot: Dot, path: TrackedPath): String = s"${dot.author.value}-${dot.revision}-${path.value}\n"

  /** Content no other patch can produce: it names the dot that wrote it, and dots are unique. That is what keeps every generated change off §4.3's `no-op` rule
    * without having to inspect what is already there.
    */
  private def uniqueBytes(dot: Dot, path: TrackedPath): FileBytes = FileBytes.utf8(uniqueText(dot, path))

  /** The same, made non-text by a leading NUL — the ingredient §6.4's `put-wins` and `later-put-wins` rules need. */
  private def binaryBytes(dot: Dot, path: TrackedPath): FileBytes =
    FileBytes(Array[Byte](0) ++ uniqueText(dot, path).getBytes(StandardCharsets.UTF_8))

  private def textEdit(path: TrackedPath, old: Vector[String], dot: Dot): Gen[Change] =
    Gen
      .choose(0, old.length)
      .map: index =>
        val updated = (old.take(index) :+ s"${dot.author.value}-${dot.revision}\n") ++ old.drop(index)
        Change.Text(path, Diff.diff(old, updated))

  private def changeFor(base: Tree, path: TrackedPath, dot: Dot): Gen[Change] =
    base.get(path) match
      case None =>
        Gen.oneOf(
          Gen.const(Change.Put(path, uniqueBytes(dot, path))),
          Gen.const(Change.Put(path, binaryBytes(dot, path))),
          Gen.const(Change.Text(path, Diff.diff(Vector.empty, Text.tokens(uniqueBytes(dot, path)))))
        )
      case Some(existing) =>
        val replacements = Gen.oneOf[Change](Change.Delete(path), Change.Put(path, uniqueBytes(dot, path)), Change.Put(path, binaryBytes(dot, path)))
        if Text.isText(existing) then Gen.oneOf(replacements, textEdit(path, Text.tokens(existing), dot)) else replacements

  private def changesFor(base: Tree, dot: Dot): Gen[Vector[Change]] =
    for
      count   <- Gen.choose(1, 2)
      paths   <- Gen.pick(count, pool)
      changes <- paths.foldLeft(Gen.const(Vector.empty[Change])): (acc, path) =>
        for
          soFar  <- acc
          change <- changeFor(base, path, dot)
        yield soFar :+ change
    yield changes.sortBy(_.targetPath)

  private def nextPatch(patches: Vector[Patch], author: ContributorId, picked: Vector[Version]): Gen[Patch] =
    val own      = patches.filter(_.author == author).maxByOption(_.revision).map(_.result).getOrElse(Version.empty)
    val base     = (picked :+ own).foldLeft(Version.empty)(Versions.join)
    val revision = base(author) + 1
    val dot      = Dot(author, revision)
    val baseTree = Replay.materialize(patches, base).fold(_ => Tree.empty, (tree, _) => tree)
    changesFor(baseTree, dot).map: changes =>
      val candidate = Patch(author, revision, base, s"${author.value} $revision", changes)
      // Some draws are simply not applicable to this base — creating `a/b` under an existing `a` is not prefix-free, for instance. Fall back to a change that
      // is valid against every tree rather than discarding the whole graph.
      if Replay.authoredTree(baseTree, candidate).isRight then candidate
      else candidate.copy(changes = Vector(Change.Put(fallbackPath, uniqueBytes(dot, fallbackPath))))

  private def grow(patches: Vector[Patch], versions: Vector[Version], remaining: Int): Gen[Vector[Patch]] =
    if remaining == 0 then Gen.const(patches)
    else
      for
        author <- Gen.oneOf(authors)
        picked <- Gen.someOf(versions)
        patch  <- nextPatch(patches, author, picked.toVector)
        rest   <- grow(patches :+ patch, versions :+ patch.result, remaining - 1)
      yield rest

  private final case class Graph(patches: Vector[Patch]):
    def frontier: Version = patches.map(_.result).foldLeft(Version.empty)(Versions.join)

    /** Every version this history makes materializable — what a merge could ever be asked to join. */
    def versions: Vector[Version] = Version.empty +: patches.map(_.result)

    /** §4.1 requires `patches` sorted by author then revision; generation order is deliberately not that, which is the point of the permutation property. */
    def repository: Repository = Repository(Repository.format, frontier, patches.sortBy(patch => (patch.author.value, patch.revision)))

    def replay: Either[SnapError, (Tree, scala.collection.immutable.SortedSet[Warning])] = Replay.materialize(patches, frontier)

  private val graph: Gen[Graph] =
    Gen.choose(1, 6).flatMap(steps => grow(Vector.empty, Vector(Version.empty), steps)).map(Graph.apply)

  /** A genuine permutation: a stable sort on independently drawn keys reorders without dropping or duplicating. */
  private def permutation(patches: Vector[Patch]): Gen[Vector[Patch]] =
    Gen.listOfN(patches.length, Gen.choose(0, 1000)).map(keys => patches.zip(keys).sortBy((_, key) => key).map((patch, _) => patch))

  private def pick(versions: Vector[Version], index: Int): Version = versions(math.floorMod(index, versions.length))

  // ---- the generator itself is sound --------------------------------------------------

  property("every generated causal patch graph is a valid repository"):
    Prop.forAll(graph): g =>
      Validation.validate(g.repository) == Right(())

  property("every generated causal patch graph replays"):
    Prop.forAll(graph): g =>
      g.replay.isRight

  // ---- §6.5 convergence ----------------------------------------------------------------

  property("replay depends only on the set of patches, never on the order they arrive in"):
    Prop.forAll(graph.flatMap(g => permutation(g.patches).map((g, _)))): (g, shuffled) =>
      Replay.materialize(shuffled, g.frontier) == g.replay

  property("merge direction does not change the joined result"):
    Prop.forAll(graph, Gen.choose(0, 99), Gen.choose(0, 99)): (g, i, j) =>
      val left  = pick(g.versions, i)
      val right = pick(g.versions, j)
      Replay.materialize(g.patches, Versions.join(left, right)) == Replay.materialize(g.patches, Versions.join(right, left))

  property("merging a version the frontier already contains is a no-op"):
    Prop.forAll(graph, Gen.choose(0, 99)): (g, i) =>
      Replay.materialize(g.patches, Versions.join(g.frontier, pick(g.versions, i))) == g.replay

  property("import is associative: how three versions are bracketed cannot change the result"):
    Prop.forAll(graph, Gen.choose(0, 99), Gen.choose(0, 99), Gen.choose(0, 99)): (g, i, j, k) =>
      val (x, y, z) = (pick(g.versions, i), pick(g.versions, j), pick(g.versions, k))
      val left      = Versions.join(Versions.join(x, y), z)
      val right     = Versions.join(x, Versions.join(y, z))
      Replay.materialize(g.patches, left) == Replay.materialize(g.patches, right)

  // ---- §2/§6.2 the replayed tree is always a legal tree ---------------------------------

  property("the replayed tree is always prefix-free"):
    Prop.forAll(graph): g =>
      g.replay.exists((tree, _) => Paths.prefixFree(tree.keySet) == Right(()))

  property("every warning names a path some patch in the history actually changed"):
    Prop.forAll(graph): g =>
      val changed = g.patches.flatMap(_.changes.map(_.targetPath)).toSet
      g.replay.exists((_, warnings) => warnings.forall(warning => changed.contains(warning.path)))

  // ---- the generator has teeth ------------------------------------------------------------

  test("the generator produces real concurrency: every §6.4 reason shows up across 300 seeded graphs"):
    val observed = (1 to 300).flatMap: n =>
      graph.pureApply(Gen.Parameters.default, Seed(n.toLong)).replay.fold(_ => Set.empty[Reason], (_, warnings) => warnings.unsorted.map(_.reason))
    assertEquals(observed.toSet, Reason.values.toSet)
