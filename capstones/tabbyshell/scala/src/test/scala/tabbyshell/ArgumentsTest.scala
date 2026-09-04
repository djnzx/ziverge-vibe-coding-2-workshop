package tabbyshell

class ArgumentsTest extends munit.FunSuite:

  test("decoder separates positional arguments from flags"):
    val decoded = Arguments.from(
      Command(
        "ls",
        Vector(
          Arg.BareIdent("fixtures"),
          Arg.Flag("all", None),
          Arg.Flag("limit", Some(Literal.Int(3)))
        )
      )
    )

    assertEquals(decoded.positionals, Vector(Arg.BareIdent("fixtures")))
    assertEquals(decoded.flags, Vector(Arg.Flag("all", None), Arg.Flag("limit", Some(Literal.Int(3)))))
    assert(decoded.hasFlag("all"))
    assert(!decoded.hasFlag("long"))

  test("decoder reports a command-specific missing positional argument"):
    val decoded = Arguments.from(Command("where", Vector.empty))

    assertEquals(
      decoded.required(0, "column op literal"),
      Left(ShellError.MissingArg("where", "column op literal"))
    )

  test("decoder requires a bare identifier when a command demands one"):
    val bare   = Arguments.from(Command("where", Vector(Arg.BareIdent("size"))))
    val quoted = Arguments.from(Command("where", Vector(Arg.Literal(Literal.Str("size")))))

    assertEquals(bare.bareIdentifier(0, "column"), Right("size"))
    assertEquals(
      quoted.bareIdentifier(0, "column"),
      Left(ShellError.BadArg("where", "expected bare identifier for column"))
    )

  test("decoder rejects a quoted where column parsed from source"):
    val command = Parser.parse("where \"size\" > 0b").fold(error => fail(error.message), _.commands.head)

    assertEquals(
      Arguments.from(command).bareIdentifier(0, "column"),
      Left(ShellError.BadArg("where", "expected bare identifier for column"))
    )

  test("decoder accepts a column name as either a bare or quoted string"):
    val bare   = Arguments.from(Command("select", Vector(Arg.BareIdent("name"))))
    val quoted = Arguments.from(Command("select", Vector(Arg.Literal(Literal.Str("full name")))))
    val number = Arguments.from(Command("select", Vector(Arg.Literal(Literal.Int(1)))))

    assertEquals(bare.columnName(0, "column"), Right("name"))
    assertEquals(quoted.columnName(0, "column"), Right("full name"))
    assertEquals(number.columnName(0, "column"), Left(ShellError.BadArg("select", "expected column name for column")))

  test("decoder recognizes comparison operators and literals"):
    val decoded = Arguments.from(
      Command("where", Vector(Arg.BareIdent("size"), Arg.Operator(">"), Arg.Literal(Literal.Filesize(0))))
    )

    assertEquals(decoded.operator(1, "operator"), Right(">"))
    assertEquals(decoded.literal(2, "literal"), Right(Literal.Filesize(0)))

  test("decoder rejects a bare identifier where a literal is required"):
    val decoded = Arguments.from(Command("where", Vector(Arg.BareIdent("size"))))

    assertEquals(
      decoded.literal(0, "literal"),
      Left(ShellError.BadArg("where", "expected literal for literal"))
    )
