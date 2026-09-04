package tabbyshell

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Invariants of `render` (SPEC 6). The contract is byte-exactness with colour off, so these pin *shape* — width, line count, escape bytes, scalar formats —
  * while the golden examples in `RendererTest` pin the exact strings.
  */
class RendererPropertyTest extends munit.ScalaCheckSuite:

  private val now  = 1700000000L
  private val opts = RenderOpts(color = false, now = now)

  private def width(s: String): Int = s.codePointCount(0, s.length)

  private def renderedLines(out: String): Vector[String] =
    out.stripSuffix("\n").split("\n", -1).toVector

  /** Cell text without control characters. SPEC 6.3 says `Str` renders "unquoted, raw" and says nothing about a newline *inside* a cell, which would break the
    * box. The shape properties therefore stay on text that cannot contain one; `genAnyText` covers the rest under totality only.
    */
  private val genPlainText: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(' ', '-', '.', '_'))).map(_.mkString)

  private val genAnyText: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(' ', '\n', '\t', '"', '\\'))).map(_.mkString)

  private def genScalar(text: Gen[String]): Gen[Value] = Gen.oneOf(
    Gen.const(Value.Null),
    Gen.oneOf(true, false).map(Value.Bool.apply),
    Gen.choose(-1000000L, 1000000L).map(Value.Int.apply),
    Gen.choose(-1000.0, 1000.0).map(Value.Float.apply),
    text.map(Value.Str.apply),
    Gen.choose(-2000000000000L, 2000000000000L).map(Value.Filesize.apply),
    Gen.choose(0L, 2000000000L).map(Value.Date.apply)
  )

  private def genTable(text: Gen[String]): Gen[Value] =
    for
      cols <- Gen.choose(1, 4).flatMap(Gen.listOfN(_, Gen.identifier))
      n    <- Gen.choose(0, 5)
      rows <- Gen.listOfN(n, Gen.listOfN(cols.size, genScalar(text)))
    yield Value.Table(cols.toVector, rows.map(_.toVector).toVector)

  private def genRecord(text: Gen[String]): Gen[Value] =
    Gen
      .choose(0, 5)
      .flatMap(Gen.listOfN(_, Gen.zip(Gen.identifier, genScalar(text))))
      .map(fs => Value.Record(fs.toVector))

  private def genValue(text: Gen[String], depth: Int): Gen[Value] =
    if depth <= 0 then genScalar(text)
    else
      Gen.frequency(
        6 -> genScalar(text),
        1 -> Gen
          .choose(0, 4)
          .flatMap(Gen.listOfN(_, genValue(text, depth - 1)))
          .map(xs => Value.List(xs.toVector)),
        1 -> genRecord(text),
        1 -> genTable(text)
      )

  private val genAnyValue: Gen[Value] = genValue(genAnyText, 3)

  private val genShapedValue: Gen[Value] = Gen.oneOf(
    genTable(genPlainText),
    genRecord(genPlainText),
    Gen
      .choose(0, 5)
      .flatMap(Gen.listOfN(_, genScalar(genPlainText)))
      .map(xs => Value.List(xs.toVector))
  )

  // --- byte-exactness ---

  private val colorOpts = opts.copy(color = true)

  property("colour-off output is already plain text"):
    forAll(genAnyValue): v =>
      val out = Renderer.render(v, opts)
      fansi.Str(out).plainText == out

  /** The SPEC 10 guarantee, stated directly: colour decorates, it never rewrites. */
  property("stripping colour from a coloured render returns the colour-off bytes"):
    forAll(genAnyValue): v =>
      fansi.Str(Renderer.render(v, colorOpts)).plainText == Renderer.render(v, opts)

  property("every render ends with a newline"):
    forAll(genAnyValue)(v => Renderer.render(v, opts).endsWith("\n"))

  property("only a string may render a trailing blank line"):
    forAll(genAnyValue): v =>
      val out = Renderer.render(v, opts)
      v match
        case Value.Str(_) => true
        case _            => !out.endsWith("\n\n")

  // --- grid shape (SPEC 6.2) ---

  property("every line of a rendered grid has the same display width"):
    forAll(genShapedValue): v =>
      renderedLines(Renderer.render(v, opts)).map(width).distinct.sizeIs == 1

  property("a table renders one line per row plus a header and three rules"):
    forAll(genTable(genPlainText)): v =>
      v match
        case Value.Table(_, rows) => renderedLines(Renderer.render(v, opts)).sizeIs == rows.size + 4
        case _                    => false

  property("a truncated cell ends in the ellipsis and fits the cap exactly"):
    forAll(Gen.choose(5, 10), Gen.choose(20, 60)): (cap, len) =>
      val out = Renderer.render(
        Value.Table(Vector("c"), Vector(Vector(Value.Str("x" * len)))),
        opts.copy(maxColWidth = cap)
      )
      out.contains("x" * (cap - 1) + "…") && !out.contains("x" * cap)

  property("a numeric column is right-aligned against its padding"):
    forAll(Gen.listOf(Gen.choose(0L, 999999L))): ns =>
      // seed both extremes so the column is always wider than its narrowest cell
      val values = (1L +: 999999L +: ns).map(n => Vector(Value.Int(n): Value)).toVector
      val body   = renderedLines(Renderer.render(Value.Table(Vector("n"), values), opts))
        .drop(3)
        .dropRight(1)
      body.forall(l => l.stripSuffix(" \u2502").lastOption.exists(_.isDigit))

  // --- scalar formats ---

  private val isoShape      = """^\d{4}-\d{2}-\d{2}$""".r
  private val filesizeShape = """^-?\d+(\.\d)? (B|KB|MB|GB|TB)$""".r
  private val floatShape    = """^-?\d+(\.\d+)?$""".r

  private def scalarText(v: Value): String = Renderer.render(v, opts).stripSuffix("\n")

  property("a date at least a year old renders as an ISO day"):
    forAll(Gen.choose(0L, now - 86400L * 400L))(ts => isoShape.matches(scalarText(Value.Date(ts))))

  property("a filesize always renders as a magnitude and an SI unit"):
    forAll(Gen.choose(Long.MinValue + 1, Long.MaxValue)): bytes =>
      filesizeShape.matches(scalarText(Value.Filesize(bytes)))

  property("a float never renders in scientific notation"):
    forAll(Gen.choose(-1000000.0, 1000000.0)): d =>
      floatShape.matches(scalarText(Value.Float(d)))

  // --- totality ---

  property("render is total on nested values, control characters included"):
    forAll(genAnyValue): v =>
      val out = Renderer.render(v, opts)
      out.nonEmpty || out.isEmpty

  property("the compact form of a table never spans lines"):
    forAll(genTable(genPlainText))(v => !Renderer.compact(v, opts).contains('\n'))
