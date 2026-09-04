package tabbyshell

import cats.parse.Parser as P
import cats.parse.Parser0 as P0

/** Tokenizer + recursive-descent pipeline grammar (SPEC 3.1). */
object Parser:

  private def isIdentStart(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '.' || c == '/' || c == '~'

  private def isIdentRest(c: Char): Boolean =
    isIdentStart(c) || (c >= '0' && c <= '9') || c == '-'

  private def isFlagLetter(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  /** `#` runs to end of line; lexically it is just more whitespace. */
  private val comment: P[Unit] = P.char('#') *> P.charsWhile0(_ != '\n').void

  /** Inter-token whitespace, comments included. */
  private val sp0: P0[Unit] =
    (P.charsWhile(c => c == ' ' || c == '\t' || c == '\n').void | comment).rep0.void

  /** Every token consumes its own trailing whitespace. */
  private def tok[A](p: P[A]): P[A] = p <* sp0

  /** A token must end at a non-ident character: `nullish` is one ident, not `null` + `ish`. */
  private val tokenEnd: P0[Unit] = P.not(P.charWhere(isIdentRest))

  private val bareIdent: P[String] =
    (P.charWhere(isIdentStart) ~ P.charsWhile0(isIdentRest)).string

  // --- literals ---

  private val digits: P[String] = P.charsWhile(c => c >= '0' && c <= '9')

  private val numberText: P[String] =
    (P.char('-').?.with1 ~ digits ~ (P.char('.') ~ digits).backtrack.?).string

  private def unit(name: String, multiplier: Long): P[Long] =
    P.ignoreCase(name).backtrack.as(multiplier)

  private val filesizeUnit: P[Long] = P.oneOf(
    List(
      unit("kib", 1024L),
      unit("mib", 1048576L),
      unit("gib", 1073741824L),
      unit("kb", 1000L),
      unit("mb", 1000000L),
      unit("gb", 1000000000L),
      unit("b", 1L)
    )
  )

  /** Magnitudes are widened before scaling so an out-of-range literal fails the parse rather than silently wrapping (or throwing) on `toLong`.
    */
  private val filesize: P[Literal] =
    ((numberText ~ filesizeUnit) <* tokenEnd).flatMap { (text, multiplier) =>
      val bytes =
        if text.contains('.') then
          val scaled = (BigDecimal(text) * BigDecimal(multiplier))
            .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          Option.when(scaled.isValidLong)(scaled.toLong)
        else
          val scaled = BigInt(text) * BigInt(multiplier)
          Option.when(scaled.isValidLong)(scaled.toLong)
      bytes.fold(P.failWith[Literal]("filesize out of range"))(b => P.pure(Literal.Filesize(b)))
    }

  private val number: P[Literal] =
    (numberText <* tokenEnd).flatMap { text =>
      if text.contains('.') then P.pure(Literal.Float(text.toDouble))
      else text.toLongOption.fold(P.failWith[Literal]("integer out of range"))(n => P.pure(Literal.Int(n)))
    }

  private val doubleQuoted: P[String] =
    val escape = P.char('\\') *> P.oneOf(
      List(
        P.char('"').as('"'),
        P.char('\\').as('\\'),
        P.char('n').as('\n'),
        P.char('t').as('\t')
      )
    )
    val plain = P.charWhere(c => c != '"' && c != '\\')
    (P.char('"') *> (escape | plain).rep0 <* P.char('"')).map(_.mkString)

  private val singleQuoted: P[String] =
    P.char('\'') *> P.charsWhile0(_ != '\'') <* P.char('\'')

  private val keyword: P[Literal] =
    P.oneOf(
      List(
        P.string("true").as(Literal.Bool(true)),
        P.string("false").as(Literal.Bool(false)),
        P.string("null").as(Literal.Null)
      )
    ) <* tokenEnd

  private val literal: P[Literal] = P.oneOf(
    List(
      doubleQuoted.map(Literal.Str.apply),
      singleQuoted.map(Literal.Str.apply),
      filesize.backtrack,
      number.backtrack,
      keyword.backtrack
    )
  )

  // --- flags, operators, dash ---

  private val longFlag: P[Arg] =
    (P.string("--").backtrack *> bareIdent).flatMap: name =>
      (P.char('=') *> literal)
        .map(value => Arg.Flag(name, Some(value)))
        .orElse(P.pure(Arg.Flag(name, None)))

  private val shortFlag: P[Arg] =
    (P.char('-') *> P.charWhere(isFlagLetter) <* tokenEnd).map(c => Arg.Flag(c.toString, None))

  /** A standalone `-`, as in `cd -`. */
  private val dash: P[Arg] = (P.char('-') <* tokenEnd).as(Arg.Dash)

  private val operator: P[Arg] = P
    .oneOf(
      List(
        P.string("==").backtrack,
        P.string("!=").backtrack,
        P.string("<=").backtrack,
        P.string(">=").backtrack,
        P.char('<').void,
        P.char('>').void
      )
    )
    .string
    .map(Arg.Operator.apply)

  // --- grammar ---

  private val argT: P[Arg] = tok(
    P.oneOf(
      List(
        longFlag,
        shortFlag.backtrack,
        dash.backtrack,
        literal.map(Arg.Literal.apply),
        operator,
        bareIdent.map(Arg.BareIdent.apply)
      )
    )
  )

  private val commandT: P[Command] =
    (tok(bareIdent) ~ argT.rep0).map((name, args) => Command(name, args.toVector))

  private val pipeT: P[Unit] = tok(P.char('|')).void

  /** A blank or comment-only line yields an empty pipeline, which the executor skips. */
  private val pipelineP: P0[Pipeline] =
    (sp0 *> P.repSep(commandT, pipeT).?)
      .map(cs => Pipeline(cs.fold(Vector.empty[Command])(_.toList.toVector)))

  /** True when `line` ends in a backslash, i.e. the next physical line continues it. */
  def continuesLine(line: String): Boolean = line.endsWith("\\")

  /** Split a script into logical lines, resolving line continuations (SPEC 3.1).
    *
    * A physical line ending in `\` is joined with the next: the backslash is dropped and a newline takes its place, so the parser sees the join as inter-token
    * whitespace.
    */
  def logicalLines(script: String): Vector[String] =
    val physical = script.split("\n", -1).toVector
    // A script ending in a newline has one trailing empty field that is not a line.
    val lines               = if physical.sizeIs > 0 && physical.last.isEmpty then physical.init else physical
    val (complete, pending) = lines.foldLeft((Vector.empty[String], "")) { case ((done, buffer), line) =>
      if continuesLine(line) then (done, buffer + line.dropRight(1) + "\n")
      else (done :+ (buffer + line), "")
    }
    if pending.isEmpty then complete else complete :+ pending

  def parse(input: String): Either[ShellError, Pipeline] =
    pipelineP.parseAll(input) match
      case Right(p) => Right(p)
      case Left(e)  =>
        val offset = e.failedAtOffset
        val detail =
          if offset >= input.length then "unexpected end of input"
          else s"unexpected '${input.charAt(offset)}'"
        Left(ShellError.Parse(detail, offset + 1))
