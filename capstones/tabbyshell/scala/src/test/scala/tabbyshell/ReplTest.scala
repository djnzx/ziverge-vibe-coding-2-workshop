package tabbyshell

import scala.collection.mutable.ArrayBuffer

class ReplTest extends munit.FunSuite:

  private val greeting = "tabbyshell 0.1.0 — type 'exit' or Ctrl-D to quit\n"

  private final class FakeTerminal(events: TerminalEvent*) extends Terminal:
    private val queued = ArrayBuffer.from(events)

    val prompts: ArrayBuffer[String] = ArrayBuffer.empty
    val stdout: ArrayBuffer[String]  = ArrayBuffer.empty
    val stderr: ArrayBuffer[String]  = ArrayBuffer.empty

    def readLine(prompt: String): TerminalEvent =
      prompts += prompt
      if queued.nonEmpty then queued.remove(0) else TerminalEvent.EndOfFile

    def writeOut(text: String): Unit = stdout += text
    def writeErr(text: String): Unit = stderr += text

  private def state(
    cwd: String = "/work",
    home: String = "/home/tabby",
    color: Boolean = false
  ): ShellState =
    ShellState(cwd, None, home, now = 1700000000L, color)

  private def run(
    terminal: FakeTerminal,
    evaluator: Repl.Evaluator,
    initial: ShellState = state()
  ): Unit =
    Repl(terminal, evaluator, "banner\n").run(initial)

  test("prints the supplied banner and greeting, then exits with goodbye"):
    val terminal = FakeTerminal(TerminalEvent.Line("exit"))
    run(terminal, (_, current) => Right(ExecutionResult(Value.Null, current)))

    assertEquals(terminal.stdout.mkString, "banner\n" + greeting + "goodbye.\n")
    assertEquals(terminal.prompts.toVector, Vector("/work ❯ "))
    assertEquals(terminal.stderr.toVector, Vector.empty)

  test("does not add a blank banner line when no banner is available"):
    val terminal = FakeTerminal(TerminalEvent.Line("exit"))
    Repl(terminal, (_, current) => Right(ExecutionResult(Value.Null, current)), "").run(state())

    assertEquals(terminal.stdout.mkString, greeting + "goodbye.\n")

  test("quit is accepted with surrounding whitespace"):
    val terminal = FakeTerminal(TerminalEvent.Line("  quit  "))
    run(terminal, (_, current) => Right(ExecutionResult(Value.Null, current)))

    assertEquals(terminal.stdout.last, "goodbye.\n")

  test("short cwd uses home-relative form only for a true child directory"):
    assertEquals(Repl.shortCwd("/home/tabby", "/home/tabby"), "~")
    assertEquals(Repl.shortCwd("/home/tabby/projects/demo", "/home/tabby"), "~/projects/demo")
    assertEquals(Repl.shortCwd("/home/tabby-other", "/home/tabby"), "/home/tabby-other")

  test("continues a pipeline with a two-space prompt and evaluates once"):
    val terminal = FakeTerminal(
      TerminalEvent.Line("pwd \\"),
      TerminalEvent.Line("| length"),
      TerminalEvent.Line("exit")
    )
    val seen = ArrayBuffer.empty[Pipeline]
    run(
      terminal,
      (pipeline, current) =>
        seen += pipeline
        Right(ExecutionResult(Value.Int(5L), current))
    )

    assertEquals(
      seen.toVector,
      Vector(Pipeline(Vector(Command("pwd", Vector.empty), Command("length", Vector.empty))))
    )
    assertEquals(terminal.prompts.toVector, Vector("/work ❯ ", "  ", "/work ❯ "))
    assert(terminal.stdout.mkString.contains("5\n"))

  test("empty and comment-only lines are not evaluated"):
    val terminal = FakeTerminal(
      TerminalEvent.Line(""),
      TerminalEvent.Line("# no command"),
      TerminalEvent.Line("quit")
    )
    var calls = 0
    run(
      terminal,
      (_, current) =>
        calls += 1
        Right(ExecutionResult(Value.Null, current))
    )

    assertEquals(calls, 0)
    assertEquals(terminal.stderr.toVector, Vector.empty)

  test("Ctrl-C discards a continued input buffer and returns to the normal prompt"):
    val terminal = FakeTerminal(
      TerminalEvent.Line("pwd \\"),
      TerminalEvent.Interrupt,
      TerminalEvent.Line("exit")
    )
    var calls = 0
    run(
      terminal,
      (_, current) =>
        calls += 1
        Right(ExecutionResult(Value.Null, current))
    )

    assertEquals(calls, 0)
    assertEquals(terminal.prompts.toVector, Vector("/work ❯ ", "  ", "/work ❯ "))

  test("parser errors go to stderr and the REPL continues"):
    val terminal = FakeTerminal(TerminalEvent.Line("$"), TerminalEvent.Line("exit"))
    run(terminal, (_, current) => Right(ExecutionResult(Value.Null, current)))

    assertEquals(terminal.stderr.toVector, Vector("✗ parse error: unexpected '$' at column 1\n"))
    assertEquals(terminal.stdout.last, "goodbye.\n")

  test("evaluation errors go to stderr and the REPL keeps the previous state"):
    val terminal = FakeTerminal(TerminalEvent.Line("pwd"), TerminalEvent.Line("exit"))
    val initial  = state(cwd = "/home/tabby/work")
    run(
      terminal,
      (_, _) => Left(ShellError.TypeMismatch("where", "table", "string")),
      initial
    )

    assertEquals(terminal.stderr.toVector, Vector("✗ where: expected table, got string\n"))
    assertEquals(terminal.prompts.toVector, Vector("~/work ❯ ", "~/work ❯ "))

  test("successful evaluation threads its returned state into the next prompt"):
    val terminal = FakeTerminal(TerminalEvent.Line("cd next"), TerminalEvent.Line("exit"))
    val initial  = state(cwd = "/home/tabby/work")
    run(
      terminal,
      (_, current) => Right(ExecutionResult(Value.Null, current.moveTo("/home/tabby/next"))),
      initial
    )

    assertEquals(terminal.prompts.toVector, Vector("~/work ❯ ", "~/next ❯ "))

  test("success renders with the returned state and emits dim warnings to stderr"):
    val terminal = FakeTerminal(TerminalEvent.Line("pwd"), TerminalEvent.Line("exit"))
    val colored  = state(color = true)
    run(
      terminal,
      (_, current) => Right(ExecutionResult(Value.Str("done"), current, Vector("AI unavailable"))),
      colored
    )

    assertEquals(terminal.stdout.find(_.contains("done")), Some("done\n"))
    assertEquals(
      terminal.stderr.toVector,
      Vector(fansi.Bold.Faint(fansi.Str("AI unavailable")).render + "\n")
    )

  test("EOF on an empty prompt prints goodbye"):
    val terminal = FakeTerminal(TerminalEvent.EndOfFile)
    run(terminal, (_, current) => Right(ExecutionResult(Value.Null, current)))

    assertEquals(terminal.stdout.last, "goodbye.\n")
