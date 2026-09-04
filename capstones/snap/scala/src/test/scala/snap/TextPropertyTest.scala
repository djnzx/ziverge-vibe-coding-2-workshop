package snap

import org.scalacheck.{Gen, Prop}

/** SPEC §4.4 properties.
  *
  * Three axes, per `.claude/skills/property-testing-parsers`: round-trip (`tokens`/`untokens` each way), rejection (adjacency, zero counts, short retains), and
  * totality (`isText` never throws, is always false on NUL). Plus the plan's name for this phase: a validated script applied to its declared old length always
  * succeeds and always yields a canonical result.
  */
class TextPropertyTest extends munit.ScalaCheckSuite:

  // ---- generators --------------------------------------------------------------

  /** A token that is always canonical regardless of position: nonempty, no internal LF (alphanumeric body), and LF-terminated. §4.4 only *requires* the LF on
    * all but possibly the last token, but ending every generated token in LF sidesteps needing to track "am I the last emission" inside the generator while
    * still exercising validate/apply honestly — the one position-sensitive exemption is covered by dedicated examples in `TextTest`.
    */
  private val canonicalToken: Gen[String] =
    Gen.choose(1, 5).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar)).map(_.mkString + "\n")

  /** Builds a script with no two adjacent operations of the same kind, bounded by `fuel` so recursion always terminates. Every inserted token is a
    * [[canonicalToken]], so any script this produces is accepted by [[Text.validate]] against the old length its own retain/delete counts sum to.
    */
  private def scriptGen(fuel: Int, previousKind: Option[String]): Gen[Vector[EditOp]] =
    if fuel == 0 then Gen.const(Vector.empty)
    else
      val availableKinds = List("R", "D", "I").filterNot(previousKind.contains)
      Gen.frequency(
        2 -> Gen.const(Vector.empty[EditOp]),
        3 -> Gen
          .oneOf(availableKinds)
          .flatMap: k =>
            val opGen: Gen[EditOp] = k match
              case "R" => Gen.choose(1, 4).map(n => EditOp.Retain(n.toLong))
              case "D" => Gen.choose(1, 4).map(n => EditOp.Delete(n.toLong))
              case _   => canonicalToken.map(t => EditOp.Insert(Vector(t)))
            for
              op   <- opGen
              rest <- scriptGen(fuel - 1, Some(k))
            yield op +: rest
      )

  private val validScript: Gen[Vector[EditOp]] = scriptGen(6, None)

  private def oldLengthOf(script: Vector[EditOp]): Int =
    script
      .collect:
        case EditOp.Retain(count) => count
        case EditOp.Delete(count) => count
      .sum
      .toInt

  private def canonicalOldTokens(length: Int): Gen[Vector[String]] =
    Gen.listOfN(length, canonicalToken).map(_.toVector)

  /** A canonical token sequence built for the round-trip property: every token but possibly the last ends in LF. */
  private val canonicalTokenSequence: Gen[Vector[String]] =
    for
      n          <- Gen.choose(0, 6)
      body       <- Gen.listOfN(n, canonicalToken)
      dropLastLf <- Gen.oneOf(true, false)
    yield
      if body.isEmpty then Vector.empty
      else body.init.toVector :+ (if dropLastLf then body.last.dropRight(1) else body.last)

  private val text: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.const('\n'), Gen.const('\r'), Gen.const(' '))).map(_.mkString)

  private def isCanonicalSequence(result: Vector[String]): Boolean =
    result.forall(token => token.nonEmpty && !token.dropRight(1).contains('\n'))
      && (result.isEmpty || result.init.forall(_.endsWith("\n")))

  // ---- round-trip: tokens / untokens ---------------------------------------------

  property("untokens(tokens(bytes)) == bytes for arbitrary UTF-8 text"):
    Prop.forAll(text): t =>
      val bytes = FileBytes.utf8(t)
      Text.untokens(Text.tokens(bytes)) == bytes

  property("tokens(untokens(ts)) == ts for any canonical token sequence"):
    Prop.forAll(canonicalTokenSequence): ts =>
      Text.tokens(Text.untokens(ts)) == ts

  property("every token but the last produced by tokens ends in LF"):
    Prop.forAll(text): t =>
      val toks = Text.tokens(FileBytes.utf8(t))
      toks.isEmpty || toks.init.forall(_.endsWith("\n"))

  // ---- totality ------------------------------------------------------------------

  private val byteGen: Gen[Byte] = Gen.choose(-128, 127).map(_.toByte)

  property("isText is false for any byte sequence containing NUL"):
    Prop.forAll(Gen.listOf(byteGen), Gen.choose(0, 20)): (bs, at) =>
      val array   = bs.toArray
      val index   = if array.isEmpty then 0 else at % (array.length + 1)
      val withNul = (array.take(index) :+ 0.toByte) ++ array.drop(index)
      !Text.isText(FileBytes(withNul))

  property("isText returns a value for arbitrary bytes, never throws"):
    Prop.forAll(Gen.listOf(byteGen)): bs =>
      Text.isText(FileBytes(bs.toArray)) || !Text.isText(FileBytes(bs.toArray))

  // ---- validate / apply: acceptance -----------------------------------------------

  property("a validated script applied to its declared old length always succeeds and yields a canonical result"):
    Prop.forAll(validScript): script =>
      val oldLength = oldLengthOf(script)
      Prop.forAll(canonicalOldTokens(oldLength)): old =>
        Text.validate(script, oldLength) == Right(())
          && (Text.apply(script, old) match
            case Right(result) => isCanonicalSequence(result)
            case Left(_)       => false)

  // ---- rejection -------------------------------------------------------------------

  property("adjacent operations of the same kind are always rejected"):
    Prop.forAll(Gen.oneOf("R", "D", "I")): k =>
      val op: EditOp = k match
        case "R" => EditOp.Retain(1)
        case "D" => EditOp.Delete(1)
        case _   => EditOp.Insert(Vector("a\n"))
      Text.validate(Vector(op, op), 0).isLeft

  property("a zero-count retain or delete is always rejected"):
    Prop.forAll(Gen.oneOf(true, false)): asRetain =>
      val op: EditOp = if asRetain then EditOp.Retain(0) else EditOp.Delete(0)
      Text.validate(Vector(op), 0).isLeft

  property("a count beyond Limits.maxSafeInteger is always rejected"):
    Prop.forAll(Gen.oneOf(true, false), Gen.choose(1L, 1000L)): (asRetain, extra) =>
      val op: EditOp =
        if asRetain then EditOp.Retain(Limits.maxSafeInteger + extra) else EditOp.Delete(Limits.maxSafeInteger + extra)
      Text.validate(Vector(op), 0).isLeft

  property("a script that does not consume the full declared old length is always rejected"):
    Prop.forAll(validScript): script =>
      Text.validate(script, oldLengthOf(script) + 1).isLeft
