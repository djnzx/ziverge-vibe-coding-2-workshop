package tabbyshell

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property-based coverage of the SPEC 3.1 grammar.
  *
  * Generators emit a source token together with the `Arg` it must parse to, so a whole pipeline can be generated alongside its expected AST.
  */
class ParserPropertyTest extends munit.ScalaCheckSuite:

  private case class Tok(text: String, arg: Arg)

  private val keywords = Set("true", "false", "null")

  private val identStartChar: Gen[Char] = Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ "_./~")
  private val identRestChar: Gen[Char]  =
    Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ "_./~-")

  private val bareIdent: Gen[String] =
    (for
      head <- identStartChar
      tail <- Gen.listOf(identRestChar)
    yield (head :: tail).mkString).retryUntil(s => !keywords.contains(s))

  private val units: List[(String, Long)] = List(
    "b"   -> 1L,
    "kb"  -> 1000L,
    "mb"  -> 1000000L,
    "gb"  -> 1000000000L,
    "kib" -> 1024L,
    "mib" -> 1048576L,
    "gib" -> 1073741824L
  )

  private val genInt: Gen[Tok] =
    Gen.choose(-1000000L, 1000000L).map(n => Tok(n.toString, Arg.Lit(Literal.Int(n))))

  private val genFloat: Gen[Tok] =
    for
      whole <- Gen.choose(-10000L, 10000L)
      frac  <- Gen.choose(0, 9999)
    yield
      val text = s"$whole.${"%04d".format(frac)}"
      Tok(text, Arg.Lit(Literal.Float(text.toDouble)))

  private val genFilesize: Gen[Tok] =
    for
      (unit, mult) <- Gen.oneOf(units)
      magnitude    <- Gen.choose(0L, 100000L)
      upper        <- Gen.oneOf(true, false)
    yield
      val rendered = if upper then unit.toUpperCase else unit
      Tok(s"$magnitude$rendered", Arg.Lit(Literal.Filesize(magnitude * mult)))

  private def escapeDoubleQuoted(s: String): String =
    s.flatMap:
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\t' => "\\t"
      case c    => c.toString

  private val stringChar: Gen[Char] =
    Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(' ', '"', '\\', '\n', '\t', '#', '|', '\'', '=', '-'))

  private val genStr: Gen[Tok] =
    Gen
      .listOf(stringChar)
      .map(_.mkString)
      .map: s =>
        Tok("\"" + escapeDoubleQuoted(s) + "\"", Arg.Lit(Literal.Str(s)))

  private val genKeyword: Gen[Tok] = Gen.oneOf(
    Tok("true", Arg.Lit(Literal.Bool(true))),
    Tok("false", Arg.Lit(Literal.Bool(false))),
    Tok("null", Arg.Lit(Literal.Null))
  )

  private val genBareArg: Gen[Tok]  = bareIdent.map(s => Tok(s, Arg.Lit(Literal.Str(s))))
  private val genOperator: Gen[Tok] =
    Gen.oneOf("==", "!=", "<", "<=", ">", ">=").map(o => Tok(o, Arg.Lit(Literal.Str(o))))
  private val genDash: Gen[Tok]      = Gen.const(Tok("-", Arg.Lit(Literal.Str("-"))))
  private val genLongFlag: Gen[Tok]  = bareIdent.map(n => Tok(s"--$n", Arg.Flag(n, None)))
  private val genShortFlag: Gen[Tok] =
    Gen.alphaChar.map(c => Tok(s"-$c", Arg.Flag(c.toString, None)))

  private val genArg: Gen[Tok] = Gen.oneOf(
    genInt,
    genFloat,
    genFilesize,
    genStr,
    genKeyword,
    genBareArg,
    genOperator,
    genDash,
    genLongFlag,
    genShortFlag
  )

  private case class Cmd(name: String, args: List[Tok]):
    def expected: Command = Command(name, args.map(_.arg).toVector)

  private val genCommand: Gen[Cmd] =
    for
      name <- bareIdent
      args <- Gen.choose(0, 4).flatMap(Gen.listOfN(_, genArg))
    yield Cmd(name, args)

  private val genPipeline: Gen[List[Cmd]] = Gen.choose(1, 4).flatMap(Gen.listOfN(_, genCommand))

  private def source(cs: List[Cmd], sep: String = " "): String =
    cs.map(c => (c.name :: c.args.map(_.text)).mkString(sep)).mkString(s"$sep|$sep")

  private def expected(cs: List[Cmd]): Either[ShellError, Pipeline] =
    Right(Pipeline(cs.map(_.expected).toVector))

  // --- round trips ---

  property("a generated pipeline parses back to the AST it was generated from"):
    forAll(genPipeline)(cs => Parser.parse(source(cs)) == expected(cs))

  property("extra whitespace between tokens does not change the AST"):
    forAll(genPipeline, Gen.choose(1, 6), Gen.oneOf(" ", "\t")): (cs, n, ws) =>
      Parser.parse(source(cs, ws * n)) == expected(cs)

  property("a trailing comment never changes the AST"):
    forAll(genPipeline, Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(' ', '|', '"', '#')))): (cs, tail) =>
      Parser.parse(s"${source(cs)} #${tail.mkString}") == expected(cs)

  property("an integer literal round-trips"):
    forAll(Gen.choose(Long.MinValue, Long.MaxValue)): (n: Long) =>
      Parser.parse(s"x $n").map(_.commands.head.args) == Right(Vector(Arg.Lit(Literal.Int(n))))

  property("a double-quoted string round-trips through escaping"):
    forAll(Gen.listOf(stringChar).map(_.mkString)): s =>
      Parser.parse("x \"" + escapeDoubleQuoted(s) + "\"").map(_.commands.head.args) ==
        Right(Vector(Arg.Lit(Literal.Str(s))))

  property("a filesize literal scales its magnitude by the unit"):
    forAll(Gen.choose(0L, 1000000L), Gen.oneOf(units)): (magnitude, unit) =>
      Parser.parse(s"x $magnitude${unit._1}").map(_.commands.head.args) ==
        Right(Vector(Arg.Lit(Literal.Filesize(magnitude * unit._2))))

  private val genLiteralTok: Gen[Tok] =
    Gen.oneOf(genInt, genFloat, genFilesize, genStr, genKeyword)

  private def literalOf(tok: Tok): Option[Literal] = tok.arg match
    case Arg.Lit(l)     => Some(l)
    case Arg.Flag(_, _) => None

  property("a float literal round-trips"):
    forAll(genFloat)(tok => Parser.parse(s"x ${tok.text}").map(_.commands.head.args) == Right(Vector(tok.arg)))

  property("a single-quoted string is taken raw, with no escape processing"):
    forAll(Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(' ', '\\', 'n', 't', '"', '#', '|', '=')))): chars =>
      val raw = chars.mkString
      Parser.parse(s"x '$raw'").map(_.commands.head.args) ==
        Right(Vector(Arg.Lit(Literal.Str(raw))))

  property("a long flag carrying a literal round-trips"):
    forAll(bareIdent, genLiteralTok): (name, tok) =>
      Parser.parse(s"x --$name=${tok.text}").map(_.commands.head.args) ==
        Right(Vector(Arg.Flag(name, literalOf(tok))))

  property("an ident that begins with a keyword is not split at the keyword"):
    forAll(Gen.oneOf("true", "false", "null"), Gen.nonEmptyListOf(identRestChar)): (kw, tail) =>
      val ident = kw + tail.mkString
      Parser.parse(s"x $ident").map(_.commands.head.args) ==
        Right(Vector(Arg.Lit(Literal.Str(ident))))

  property("a negative filesize magnitude scales like a positive one"):
    forAll(Gen.choose(-1000000L, 0L), Gen.oneOf(units)): (magnitude, unit) =>
      Parser.parse(s"x $magnitude${unit._1}").map(_.commands.head.args) ==
        Right(Vector(Arg.Lit(Literal.Filesize(magnitude * unit._2))))

  property("an operator needs no surrounding whitespace"):
    forAll(bareIdent, genOperator, genLiteralTok): (col, op, lit) =>
      val spaced = Parser.parse(s"where $col ${op.text} ${lit.text}")
      // `isRight` keeps this from passing vacuously when both sides error.
      spaced.isRight && Parser.parse(s"where $col${op.text}${lit.text}") == spaced

  // --- totality ---

  /** Fragments drawn from the grammar itself hit parser edges that `Arbitrary[String]` essentially never reaches: unbalanced quotes, stray backslashes, bare
    * separators.
    */
  private val grammarFragment: Gen[String] = Gen.oneOf(
    "\"",
    "'",
    "\\",
    "|",
    "#",
    "=",
    "<",
    ">",
    "-",
    "--",
    " ",
    "\t",
    "\n",
    "a",
    "1",
    "5kb",
    "true",
    "null",
    ".",
    "/",
    "~",
    "0.5",
    "999999999999999999999",
    "\\n"
  )

  private val fuzz: Gen[String] =
    Gen.choose(0, 14).flatMap(Gen.listOfN(_, grammarFragment)).map(_.mkString)

  property("parse is total on grammar-fragment fuzz"):
    forAll(fuzz)(s => Parser.parse(s).isRight || Parser.parse(s).isLeft)

  property("a parse error column falls inside grammar-fragment fuzz input"):
    forAll(fuzz): s =>
      Parser.parse(s) match
        case Right(_)                       => true
        case Left(ShellError.Parse(_, col)) => col >= 1 && col <= s.length + 1

  private val hugeDigits: Gen[String] =
    for
      lead <- Gen.oneOf('1' to '9')
      rest <- Gen.choose(19, 30).flatMap(Gen.listOfN(_, Gen.numChar))
    yield (lead :: rest).mkString

  property("a number too large for Int64 is a parse error, not a crash"):
    forAll(hugeDigits)(digits => Parser.parse(s"x $digits").isLeft)

  property("a filesize magnitude too large for Int64 is a parse error, not a crash"):
    forAll(hugeDigits, Gen.oneOf(units)): (digits, unit) =>
      Parser.parse(s"x $digits${unit._1}").isLeft

  property("parse is total: arbitrary input yields a result, never an exception"):
    forAll: (s: String) =>
      Parser.parse(s).isRight || Parser.parse(s).isLeft

  property("a parse error column falls inside the input"):
    forAll: (s: String) =>
      Parser.parse(s) match
        case Right(_)                       => true
        case Left(ShellError.Parse(_, col)) => col >= 1 && col <= s.length + 1

  // --- rejection ---
  //
  // The totality properties above only say `parse` returns *something*. These say what it
  // must refuse: a parser that accepted every input would satisfy totality and fail here.

  private def isRejected(input: String): Boolean = Parser.parse(input) match
    case Left(ShellError.Parse(_, _)) => true
    case Right(_)                     => false

  /** Characters the SPEC 3.1 grammar has no production for. */
  private val notInGrammar: Gen[Char] = Gen.oneOf("&;$(){}[]@^*+,:?!`".toSeq)

  /** Any letter that is not a supported escape (`n`, `t`; `"` and `\\` are not letters). */
  private val badEscapeChar: Gen[Char] = Gen.oneOf("abcdefghijklmopqrsuvwxyz".toSeq)

  /** Letters that begin no filesize unit, so `<digits><letter>` can never be a filesize. */
  private val nonUnitLetter: Gen[Char] = Gen.oneOf("acdefhijlnopqrstuvwxyz".toSeq)

  private val alphaNum: Gen[String] = Gen.listOf(Gen.alphaNumChar).map(_.mkString)

  property("an unterminated double-quoted string is rejected"):
    forAll(alphaNum)(body => isRejected("x \"" + body))

  property("an unterminated single-quoted string is rejected"):
    forAll(alphaNum)(body => isRejected("x '" + body))

  property("an unsupported escape inside a double-quoted string is rejected"):
    forAll(alphaNum, badEscapeChar): (body, bad) =>
      isRejected("x \"" + body + "\\" + bad + "\"")

  property("a trailing pipe is rejected"):
    forAll(genPipeline)(cs => isRejected(source(cs) + " |"))

  property("a leading pipe is rejected"):
    forAll(genPipeline)(cs => isRejected("| " + source(cs)))

  property("an empty command between two pipes is rejected"):
    forAll(genPipeline)(cs => isRejected(source(cs) + " | | length"))

  property("a character outside the grammar is rejected"):
    forAll(genPipeline, notInGrammar)((cs, c) => isRejected(source(cs) + c.toString))

  property("a number immediately followed by ident characters is rejected"):
    forAll(Gen.choose(0L, 100000L), nonUnitLetter, alphaNum): (n, letter, rest) =>
      isRejected(s"x $n$letter$rest")

  property("an unknown filesize unit is rejected"):
    forAll(Gen.choose(0L, 100000L), Gen.oneOf("tb", "pb", "bb", "kbs", "q")): (n, unit) =>
      isRejected(s"x $n$unit")

  property("a long flag with `=` but no literal is rejected"):
    forAll(bareIdent)(name => isRejected(s"x --$name="))

  property("a bare double dash is rejected"):
    forAll(genPipeline)(cs => isRejected(source(cs) + " --"))

  /** Every rejection path, not just the ones with a dedicated property above. */
  private val invalidInput: Gen[String] = Gen.oneOf(
    alphaNum.map(body => "x \"" + body),
    alphaNum.map(body => "x '" + body),
    genPipeline.map(cs => source(cs) + " |"),
    genPipeline.map(cs => "| " + source(cs)),
    for cs <- genPipeline; c <- notInGrammar yield source(cs) + c,
    for n <- Gen.choose(0L, 100000L); u <- Gen.oneOf("tb", "pb", "q") yield s"x $n$u",
    bareIdent.map(name => s"x --$name=")
  )

  private val specErrorShape = """^parse error: .+ at column \d+$""".r

  property("every rejection reports the SPEC 3.3 message shape"):
    forAll(invalidInput): input =>
      Parser.parse(input) match
        case Right(_)  => false
        case Left(err) => specErrorShape.matches(err.message)

  property("every rejection points at a column inside the input"):
    forAll(invalidInput): input =>
      Parser.parse(input) match
        case Right(_)                       => false
        case Left(ShellError.Parse(_, col)) => col >= 1 && col <= input.length + 1
