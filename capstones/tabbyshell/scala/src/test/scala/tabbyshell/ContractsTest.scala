package tabbyshell

class ContractsTest extends munit.FunSuite:

  test("shell state contains all SPEC 9 fields"):
    val state = ShellState(
      cwd = "/workspace",
      prevCwd = None,
      home = "/Users/tabby",
      now = 1725408000L,
      color = false
    )

    assertEquals(state.cwd, "/workspace")
    assertEquals(state.prevCwd, None)
    assertEquals(state.home, "/Users/tabby")
    assertEquals(state.now, 1725408000L)
    assertEquals(state.color, false)

  test("moving directories retains the previous cwd for cd dash"):
    val initial = ShellState("/workspace", None, "/Users/tabby", 1725408000L, color = true)

    assertEquals(
      initial.moveTo("/workspace/subdir"),
      ShellState("/workspace/subdir", Some("/workspace"), "/Users/tabby", 1725408000L, color = true)
    )

  test("execution results carry the command value and resulting state"):
    val updated = ShellState("/workspace/subdir", Some("/workspace"), "/Users/tabby", 1725408000L, color = false)
    val result  = ExecutionResult(Value.Null, updated)

    assertEquals(result.value, Value.Null)
    assertEquals(result.state, updated)

  test("all SPEC 3.3 error variants have stable messages"):
    val errors = Vector(
      ShellError.Parse("unexpected '&'", 4)                 -> "parse error: unexpected '&' at column 4",
      ShellError.TypeMismatch("where", "table", "string")   -> "where: expected table, got string",
      ShellError.MissingColumn("select", "missing")         -> "select: column not found: missing",
      ShellError.MissingArg("where", "column op literal")   -> "where: missing required argument: column op literal",
      ShellError.BadArg("cd", "not a directory: notes.txt") -> "cd: not a directory: notes.txt",
      ShellError.IoError("open", "no such file")            -> "open: no such file",
      ShellError.ExternalFailed("sh", 7)                    -> "sh: external command exited with status 7"
    )

    errors.foreach { case (error, expected) => assertEquals(error.message, expected) }
