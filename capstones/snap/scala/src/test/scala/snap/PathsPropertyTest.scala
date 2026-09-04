package snap

import org.scalacheck.{Gen, Prop}

/** SPEC §2 properties.
  *
  * Three axes, per `.claude/skills/property-testing-parsers`: round-trip, rejection, and totality. Plus the two `tracking/plan.md` names for this phase:
  * prefix-freedom surviving a namespace-shaped insertion, and `isPrefixOf` as a strict order compatible with `Ordering[TrackedPath]`.
  */
class PathsPropertyTest extends munit.ScalaCheckSuite:

  // ---- generators ------------------------------------------------------------

  /** §2 excludes control characters, backslash, and the empty/`.`/`..` segments; `.snap` is excluded from the pool so a generated first segment never
    * accidentally collides with the reserved directory.
    */
  private val segment: Gen[String] =
    Gen.oneOf("a", "b", "c", "x", "y", "dir", "file", "é", "😀")

  private val segments: Gen[Vector[String]] =
    Gen.choose(1, 5).flatMap(n => Gen.listOfN(n, segment).map(_.toVector))

  private def render(segs: Vector[String]): String = segs.mkString("/")

  private val validText: Gen[String] = segments.map(render)

  private val validPath: Gen[TrackedPath] = validText.map(TrackedPath(_))

  /** A prefix-free set built by giving every member a distinct numbered top segment, so no two can be segment-prefix related no matter what follows.
    */
  private val distinctRootSet: Gen[Set[TrackedPath]] =
    for
      n      <- Gen.choose(0, 6)
      roots  <- Gen.listOfN(n, Gen.choose(0, 100)).map(_.distinct)
      extras <- Gen.listOfN(roots.length, segments)
    yield roots
      .zip(extras)
      .map((root, extra) => TrackedPath((s"root$root" +: extra).mkString("/")))
      .toSet

  /** Pairs an existing prefix-free set with a candidate that is sometimes independent, sometimes an exact, ancestor, or descendant collision with one of its
    * members — so the removal step in the property below actually has something to remove.
    */
  private val setAndCandidate: Gen[(Set[TrackedPath], TrackedPath)] =
    for
      roots     <- distinctRootSet
      candidate <-
        if roots.isEmpty then validPath
        else
          Gen.oneOf(
            validPath,
            Gen.oneOf(roots.toSeq),
            Gen.oneOf(roots.toSeq).flatMap(r => segments.map(extra => TrackedPath(s"${r.value}/${extra.mkString("/")}")))
          )
    yield (roots, candidate)

  /** Fuzz built from the grammar's own alphabet, biased toward the shapes §2 names as invalid. */
  private val fragment: Gen[String] =
    Gen.oneOf("a", "b", "/", "//", ".", "..", "\\", "", ".snap", "")

  private val fuzz: Gen[String] =
    Gen.choose(0, 8).flatMap(n => Gen.listOfN(n, fragment)).map(_.mkString)

  // ---- round-trip --------------------------------------------------------------

  property("canonical text parses to a path with exactly that spelling"):
    Prop.forAll(validText): text =>
      Paths.trackedPath(text) == Right(TrackedPath(text))

  // ---- rejection -----------------------------------------------------------

  property("the empty string is always rejected"):
    Paths.trackedPath("").isLeft

  property("a doubled slash — an empty segment — is rejected"):
    Prop.forAll(segments): segs =>
      Paths.trackedPath((segs :+ "").mkString("/")).isLeft

  property("a . segment is rejected"):
    Prop.forAll(segments, Gen.choose(0, 4)): (segs, at) =>
      Paths.trackedPath((segs.take(at) ++ Vector(".") ++ segs.drop(at)).mkString("/")).isLeft

  property(".. segment is rejected"):
    Prop.forAll(segments, Gen.choose(0, 4)): (segs, at) =>
      Paths.trackedPath((segs.take(at) ++ Vector("..") ++ segs.drop(at)).mkString("/")).isLeft

  property("a backslash anywhere is rejected"):
    Prop.forAll(validText, Gen.choose(0, 20)): (text, at) =>
      val index = if text.isEmpty then 0 else at % (text.length + 1)
      Paths.trackedPath(text.take(index) + "\\" + text.drop(index)).isLeft

  property("a control character anywhere is rejected"):
    Prop.forAll(validText, Gen.choose(0, 20), Gen.choose(0, 31)): (text, at, code) =>
      val index = if text.isEmpty then 0 else at % (text.length + 1)
      Paths.trackedPath(text.take(index) + code.toChar + text.drop(index)).isLeft

  property(".snap as the first segment is always rejected"):
    Prop.forAll(segments): rest =>
      Paths.trackedPath((".snap" +: rest).mkString("/")).isLeft

  property("every rejection reports the documented message shape"):
    Prop.forAll(fuzz): text =>
      Paths.trackedPath(text) match
        case Right(_)    => Prop.passed
        case Left(error) => Prop(error.detail.matches("invalid path: .+"))

  // ---- totality --------------------------------------------------------------

  property("trackedPath returns a value for arbitrary input, never throws"):
    Prop.forAll: (text: String) =>
      Paths.trackedPath(text).isLeft || Paths.trackedPath(text).isRight

  property("prefixFree returns a value for arbitrary sets, never throws"):
    Prop.forAll(Gen.listOf(validPath).map(_.toSet)): paths =>
      Paths.prefixFree(paths).isLeft || Paths.prefixFree(paths).isRight

  // ---- isPrefixOf as a strict order -----------------------------------------

  property("isPrefixOf is irreflexive"):
    Prop.forAll(validPath): path =>
      !Paths.isPrefixOf(path, path)

  property("isPrefixOf is transitive"):
    Prop.forAll(Gen.choose(3, 6).flatMap(n => Gen.listOfN(n, segment))): segs =>
      Prop.forAll(Gen.choose(1, segs.length - 2)): i =>
        Prop.forAll(Gen.choose(i + 1, segs.length - 1)): j =>
          val a = TrackedPath(segs.take(i).mkString("/"))
          val b = TrackedPath(segs.take(j).mkString("/"))
          val c = TrackedPath(segs.mkString("/"))
          // a prefixes b and b prefixes c by construction (i < j <= length), so
          // this is the interesting direction: does a prefix c too?
          Paths.isPrefixOf(a, b) && Paths.isPrefixOf(b, c) && Paths.isPrefixOf(a, c)

  property("isPrefixOf is compatible with the path ordering: an ancestor always sorts before its descendant"):
    Prop.forAll(Gen.choose(2, 6).flatMap(n => Gen.listOfN(n, segment))): segs =>
      Prop.forAll(Gen.choose(1, segs.length - 1)): i =>
        val ancestor   = TrackedPath(segs.take(i).mkString("/"))
        val descendant = TrackedPath(segs.mkString("/"))
        // Constructed as an ancestor/descendant pair, so isPrefixOf must hold —
        // the property is what it implies about Ordering[TrackedPath].
        Paths.isPrefixOf(ancestor, descendant) &&
        summon[Ordering[TrackedPath]].lt(ancestor, descendant)

  // ---- prefix-freedom survives a namespace-shaped insertion ------------------

  property("inserting a path preserves prefix-freedom once its ancestors and descendants are removed"):
    Prop.forAll(setAndCandidate): (existing, candidate) =>
      val cleaned = existing.filterNot(p => p == candidate || Paths.isPrefixOf(p, candidate) || Paths.isPrefixOf(candidate, p))
      Paths.prefixFree(cleaned + candidate).isRight
