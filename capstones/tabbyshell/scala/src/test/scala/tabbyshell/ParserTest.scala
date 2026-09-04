package tabbyshell

class ParserTest extends munit.FunSuite:

  private def pipeline(cs: Command*): Either[ShellError, Pipeline] = Right(Pipeline(cs.toVector))
  private def cmd(name: String, args: Arg*): Command               = Command(name, args.toVector)

  private def bare(s: String): Arg  = Arg.BareIdent(s)
  private def str(s: String): Arg   = Arg.Literal(Literal.Str(s))
  private def int(n: Long): Arg     = Arg.Literal(Literal.Int(n))
  private def flt(d: Double): Arg   = Arg.Literal(Literal.Float(d))
  private def size(b: Long): Arg    = Arg.Literal(Literal.Filesize(b))
  private def bool(b: Boolean): Arg = Arg.Literal(Literal.Bool(b))
  private def op(s: String): Arg    = Arg.Operator(s)
  private val dash: Arg             = Arg.Dash
  private val nul: Arg              = Arg.Literal(Literal.Null)

  private def argsOf(input: String): Vector[Arg] =
    Parser.parse(input).fold(e => fail(e.message), _.commands.head.args)

  // --- pipeline skeleton ---

  test("a bare command parses to a single-command pipeline"):
    assertEquals(Parser.parse("ls"), pipeline(cmd("ls")))

  test("commands are joined by a pipe"):
    assertEquals(Parser.parse("ls | length"), pipeline(cmd("ls"), cmd("length")))

  test("surrounding whitespace is ignored"):
    assertEquals(Parser.parse("   ls   |   length   "), pipeline(cmd("ls"), cmd("length")))

  test("a bare-ident argument preserves its lexical category"):
    assertEquals(Parser.parse("cd subdir"), pipeline(cmd("cd", bare("subdir"))))

  test("bare idents carry path characters as one token"):
    assertEquals(Parser.parse("open ~/notes/a.txt"), pipeline(cmd("open", bare("~/notes/a.txt"))))

  test("a hyphenated command name is a single ident"):
    assertEquals(Parser.parse("sort-by name"), pipeline(cmd("sort-by", bare("name"))))

  // --- numbers ---

  test("an integer argument parses as Int"):
    assertEquals(argsOf("first 3"), Vector(int(3)))

  test("a decimal argument parses as Float"):
    assertEquals(argsOf("x 1.5"), Vector(flt(1.5)))

  test("a leading minus makes a negative number, not a flag"):
    assertEquals(argsOf("x -3"), Vector(int(-3)))

  test("a negative decimal parses as Float"):
    assertEquals(argsOf("x -2.25"), Vector(flt(-2.25)))

  // --- filesizes ---

  test("SI filesize units multiply by powers of 1000"):
    assertEquals(
      argsOf("x 1b 2kb 3mb 4gb"),
      Vector(size(1), size(2000), size(3000000), size(4000000000L))
    )

  test("IEC filesize units multiply by powers of 1024"):
    assertEquals(argsOf("x 1kib 1mib 1gib"), Vector(size(1024), size(1048576), size(1073741824)))

  test("filesize units are case-insensitive"):
    assertEquals(argsOf("x 10KB 1MiB"), Vector(size(10000), size(1048576)))

  test("a filesize may have a fractional magnitude"):
    assertEquals(argsOf("x 1.5kb"), Vector(size(1500)))

  test("zero filesize parses"):
    assertEquals(argsOf("x 0b"), Vector(size(0)))

  // --- strings ---

  test("a double-quoted string parses without its quotes"):
    assertEquals(argsOf("""select "full name""""), Vector(str("full name")))

  test("double-quoted strings honour escapes"):
    assertEquals(argsOf("x \"a\\\"b\\\\c\\nd\\te\""), Vector(str("a\"b\\c\nd\te")))

  test("a single-quoted string is raw"):
    assertEquals(argsOf("x 'a b'"), Vector(str("a b")))

  test("a single-quoted string does not process backslashes"):
    assertEquals(argsOf("x 'a\\nb'"), Vector(str("a\\nb")))

  // --- keywords ---

  test("true and false parse as Bool"):
    assertEquals(argsOf("x true false"), Vector(bool(true), bool(false)))

  test("null parses as the null literal"):
    assertEquals(argsOf("x null"), Vector(nul))

  test("a keyword prefix does not swallow a longer bare ident"):
    assertEquals(
      argsOf("get nullish trueish falsehood"),
      Vector(bare("nullish"), bare("trueish"), bare("falsehood"))
    )

  // --- flags, operators, dash ---

  test("a long flag parses as a flag"):
    assertEquals(argsOf("ls --all"), Vector(flag("all")))

  test("a short flag parses as a flag"):
    assertEquals(argsOf("ls -a -l"), Vector(flag("a"), flag("l")))

  test("a long flag may carry a value"):
    assertEquals(argsOf("x --width=40"), Vector(flagOf("width", Literal.Int(40))))

  test("a standalone dash is a string argument"):
    assertEquals(argsOf("cd -"), Vector(dash))

  test("comparison operators surface as string arguments"):
    assertEquals(
      argsOf("x == != < <= > >="),
      Vector(op("=="), op("!="), op("<"), op("<="), op(">"), op(">="))
    )

  test("a where clause parses column, operator and literal"):
    assertEquals(
      Parser.parse("ls | where size > 0b"),
      pipeline(cmd("ls"), cmd("where", bare("size"), op(">"), size(0)))
    )

  test("sort-by mixes an ident argument with a flag"):
    assertEquals(
      Parser.parse("sort-by size --reverse"),
      pipeline(cmd("sort-by", bare("size"), flag("reverse")))
    )

  test("quoted strings remain distinct from bare identifiers"):
    assertEquals(
      Parser.parse("select name \"display name\""),
      pipeline(cmd("select", bare("name"), str("display name")))
    )

  test("a quoted where column remains a literal rather than a bare identifier"):
    assertEquals(
      Parser.parse("where \"size\" > 0b"),
      pipeline(cmd("where", str("size"), op(">"), size(0)))
    )

  test("a quoted dash is distinct from cd dash"):
    assertEquals(Parser.parse("cd \"-\""), pipeline(cmd("cd", str("-"))))

  // --- comments and blank input ---

  test("a trailing comment is ignored"):
    assertEquals(
      Parser.parse("ls | first 3 # take three"),
      pipeline(cmd("ls"), cmd("first", int(3)))
    )

  test("a comment-only line parses to an empty pipeline"):
    assertEquals(Parser.parse("# just a comment"), pipeline())

  test("blank input parses to an empty pipeline"):
    assertEquals(Parser.parse("   "), pipeline())

  test("a hash inside a string is not a comment"):
    assertEquals(argsOf("""x "a # b""""), Vector(str("a # b")))

  // --- errors ---

  test("an unexpected character reports its 1-based column"):
    assertEquals(Parser.parse("ls & length"), Left(ShellError.Parse("unexpected '&'", 4)))

  test("a trailing pipe is a parse error"):
    assert(Parser.parse("ls |").left.exists(_.message.startsWith("parse error")))

  test("the parse error message follows the SPEC 3.3 format"):
    assertEquals(
      Parser.parse("ls & length").left.map(_.message),
      Left("parse error: unexpected '&' at column 4")
    )

  test("an integer literal beyond Int64 is a parse error at the literal's column"):
    assertEquals(
      Parser.parse("x 99999999999999999999").left.map(_.message),
      Left("parse error: unexpected '9' at column 3")
    )

  test("a filesize magnitude beyond Int64 is a parse error"):
    assert(
      Parser.parse("x 99999999999999999999kb").left.exists(_.message.startsWith("parse error"))
    )

  test("a filesize whose byte count overflows Int64 is a parse error"):
    assert(Parser.parse("x 9223372036854775807gb").left.exists(_.message.startsWith("parse error")))

  test("a filesize magnitude overflow reports the literal's own column"):
    assertEquals(
      Parser.parse("x 9223372036854775808b").left.map(_.message),
      Left("parse error: unexpected '9' at column 3")
    )

  test("a scaled filesize overflow reports its literal's own column in a pipeline"):
    assertEquals(
      Parser.parse("ls | where size > 9223372036854775807gb").left.map(_.message),
      Left("parse error: unexpected '9' at column 19")
    )

  test("a valued flag with no literal reports the end-of-input column"):
    assertEquals(
      Parser.parse("ls --limit=").left.map(_.message),
      Left("parse error: unexpected end of input at column 12")
    )

  test("a trailing pipe reports the offending pipe column"):
    assertEquals(
      Parser.parse("ls |").left.map(_.message),
      Left("parse error: unexpected '|' at column 4")
    )

  private def flag(name: String): Arg                   = Arg.Flag(name, None)
  private def flagOf(name: String, value: Literal): Arg = Arg.Flag(name, Some(value))

  // --- line continuation (SPEC 3.1, 7.3) ---

  test("a line with no trailing backslash does not continue"):
    assert(!Parser.continuesLine("ls | first 3"))

  test("a line ending in a backslash continues"):
    assert(Parser.continuesLine("ls \\"))

  test("a script without continuations yields one logical line per physical line"):
    assertEquals(Parser.logicalLines("ls\npwd\n"), Vector("ls", "pwd"))

  test("a trailing backslash joins the next line with a newline in its place"):
    assertEquals(Parser.logicalLines("ls \\\n| first 3"), Vector("ls \n| first 3"))

  test("continuations chain across several physical lines"):
    assertEquals(
      Parser.logicalLines("ls \\\n  | sort-by name \\\n  | get name"),
      Vector("ls \n  | sort-by name \n  | get name")
    )

  test("a continuation at end of input drops the backslash and keeps the newline"):
    assertEquals(Parser.logicalLines("ls \\"), Vector("ls \n"))

  test("a blank line is its own logical line"):
    assertEquals(Parser.logicalLines("ls\n\npwd"), Vector("ls", "", "pwd"))

  // --- newline as inter-token whitespace ---

  test("a newline separates tokens like a space"):
    assertEquals(Parser.parse("ls\n| first 3"), pipeline(cmd("ls"), cmd("first", int(3))))

  test("a continued pipeline parses as a single pipeline"):
    val logical = Parser.logicalLines("ls \\\n  | sort-by name \\\n  | get name")
    assertEquals(logical.size, 1)
    assertEquals(
      Parser.parse(logical.head),
      pipeline(cmd("ls"), cmd("sort-by", bare("name")), cmd("get", bare("name")))
    )

  test("a comment ends at the newline, not at the end of a continued buffer"):
    assertEquals(Parser.parse("ls # note\n| first 3"), pipeline(cmd("ls"), cmd("first", int(3))))
