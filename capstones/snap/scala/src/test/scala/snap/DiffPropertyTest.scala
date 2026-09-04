package snap

import org.scalacheck.{Gen, Prop}

/** SPEC §5 properties.
  *
  * The central one: `Text.apply(diff(a, b), a) == b` for any token sequences. Plus the plan's other three: the script is minimal against an independently
  * computed `D(0, 0)`, the output is always coalesced, and `diff(a, a)` never deletes or inserts.
  */
class DiffPropertyTest extends munit.ScalaCheckSuite:

  // ---- generators ----------------------------------------------------------------

  /** A token that is always canonical regardless of position: nonempty, no internal LF, and LF-terminated. Mirrors `TextPropertyTest.canonicalToken`. */
  private val canonicalToken: Gen[String] =
    Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, Gen.oneOf('a', 'b', 'c'))).map(_.mkString + "\n")

  /** A canonical token sequence: every token but possibly the last ends in LF. A small alphabet keeps repeated-token cases — the ones §5 calls out by name —
    * common rather than accidental.
    */
  private val canonicalTokenSequence: Gen[Vector[String]] =
    for
      n          <- Gen.choose(0, 6)
      body       <- Gen.listOfN(n, canonicalToken)
      dropLastLf <- Gen.oneOf(true, false)
    yield
      if body.isEmpty then Vector.empty
      else body.init.toVector :+ (if dropLastLf then body.last.dropRight(1) else body.last)

  // ---- an independent oracle for §5's D(i, j) -------------------------------------

  /** Recomputes §5's `D(0, 0)` directly from its recurrence, independently of `Diff.distances`, as the oracle for the minimality property. */
  private def minimalCost(a: Vector[String], b: Vector[String]): Long =
    val cache                   = scala.collection.mutable.Map.empty[(Int, Int), Long]
    def d(i: Int, j: Int): Long =
      cache.getOrElseUpdate(
        (i, j),
        if i == a.length && j == b.length then 0L
        else if i == a.length then (b.length - j).toLong
        else if j == b.length then (a.length - i).toLong
        else if a(i) == b(j) then d(i + 1, j + 1)
        else 1L + math.min(d(i + 1, j), d(i, j + 1))
      )
    d(0, 0)

  private def editCost(script: Vector[EditOp]): Long =
    script
      .collect:
        case EditOp.Delete(count) => count
        case EditOp.Insert(toks)  => toks.length.toLong
      .sum

  private def isCoalesced(script: Vector[EditOp]): Boolean =
    script
      .sliding(2)
      .forall:
        case Seq(EditOp.Retain(_), EditOp.Retain(_)) => false
        case Seq(EditOp.Delete(_), EditOp.Delete(_)) => false
        case Seq(EditOp.Insert(_), EditOp.Insert(_)) => false
        case _                                       => true

  // ---- the central property: diff round-trips through Text.apply -----------------

  property("Text.apply(diff(a, b), a) == Right(b) for any canonical token sequences"):
    Prop.forAll(canonicalTokenSequence, canonicalTokenSequence): (a, b) =>
      Text.apply(Diff.diff(a, b), a) == Right(b)

  // ---- minimality, coalescing, and the identity case ------------------------------

  property("the script's insert+delete total equals the independently computed D(0, 0)"):
    Prop.forAll(canonicalTokenSequence, canonicalTokenSequence): (a, b) =>
      editCost(Diff.diff(a, b)) == minimalCost(a, b)

  property("the output is always coalesced"):
    Prop.forAll(canonicalTokenSequence, canonicalTokenSequence): (a, b) =>
      isCoalesced(Diff.diff(a, b))

  property("diff(a, a) never deletes or inserts"):
    Prop.forAll(canonicalTokenSequence): a =>
      Diff
        .diff(a, a)
        .forall:
          case EditOp.Retain(_) => true
          case _                => false
