package snap

import org.scalacheck.{Gen, Prop}

/** SPEC §6.3 properties, exercised via `Diff.diff` against a shared base so `p` and `q` are always realistic, validated edit scripts (Phase 4 already proves
  * `diff` produces those).
  */
class OtPropertyTest extends munit.ScalaCheckSuite:

  // ---- generators -------------------------------------------------------------------

  /** Mirrors `DiffPropertyTest.canonicalToken`, drawn from an alphabet disjoint from [[marker]] so a generated base or edit never accidentally produces a
    * marker token.
    */
  private val canonicalToken: Gen[String] =
    Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, Gen.oneOf('a', 'b', 'c'))).map(_.mkString + "\n")

  private val canonicalTokenSequence: Gen[Vector[String]] =
    for
      n          <- Gen.choose(0, 6)
      body       <- Gen.listOfN(n, canonicalToken)
      dropLastLf <- Gen.oneOf(true, false)
    yield
      if body.isEmpty then Vector.empty
      else body.init.toVector :+ (if dropLastLf then body.last.dropRight(1) else body.last)

  /** A token that can only ever enter a generated sequence through deliberate insertion in this file, never through [[canonicalToken]] or a diff against it —
    * the tag the survival property looks for.
    */
  private val marker = "Q\n"

  /** `base` with zero or more [[marker]] tokens spliced into its gaps — never deleting or reordering `base`'s own tokens, so `Diff.diff(base, _)` always yields
    * a pure-insert context edit.
    */
  private def withMarkersSpliced(base: Vector[String]): Gen[Vector[String]] =
    Gen
      .listOfN(base.length + 1, Gen.oneOf(true, false))
      .map: gaps =>
        val builder = Vector.newBuilder[String]
        for i <- base.indices do
          if gaps(i) then builder += marker
          builder += base(i)
        if gaps(base.length) then builder += marker
        builder.result()

  private case class Case(base: Vector[String], incoming: Vector[String], context: Vector[String])

  /** `incoming` is an arbitrary edit of `base`; `context` is `base` with markers spliced in — the shape every transform in Phase 7's replay actually sees: an
    * incoming authored edit against the same base the receiving side's own context edit was taken from.
    */
  private val transformCase: Gen[Case] =
    for
      base     <- canonicalTokenSequence
      incoming <- canonicalTokenSequence
      context  <- withMarkersSpliced(base)
    yield Case(base, incoming, context)

  private def isCoalesced(script: Vector[EditOp]): Boolean =
    script
      .sliding(2)
      .forall:
        case Seq(EditOp.Retain(_), EditOp.Retain(_)) => false
        case Seq(EditOp.Delete(_), EditOp.Delete(_)) => false
        case Seq(EditOp.Insert(_), EditOp.Insert(_)) => false
        case _                                       => true

  // ---- properties ---------------------------------------------------------------------

  property("transform(p, q) is a valid, coalesced script against the token count q's result has"):
    Prop.forAll(transformCase): c =>
      val p           = Diff.diff(c.base, c.incoming)
      val q           = Diff.diff(c.base, c.context)
      val transformed = Ot.transform(p, q)
      isCoalesced(transformed) && Text.validate(transformed, c.context.length) == Right(())

  property("applying transform(p, q) to q's own result never fails"):
    Prop.forAll(transformCase): c =>
      val p           = Diff.diff(c.base, c.incoming)
      val q           = Diff.diff(c.base, c.context)
      val transformed = Ot.transform(p, q)
      Text.apply(transformed, c.context).isRight

  property("text inserted by q always survives transform(p, q): every marker in q's result is still present"):
    Prop.forAll(transformCase): c =>
      val p           = Diff.diff(c.base, c.incoming)
      val q           = Diff.diff(c.base, c.context)
      val transformed = Ot.transform(p, q)
      Text.apply(transformed, c.context).exists(_.count(_ == marker) == c.context.count(_ == marker))
