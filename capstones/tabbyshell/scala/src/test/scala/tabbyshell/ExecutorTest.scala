package tabbyshell

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class ExecutorTest extends munit.FunSuite:

  private def state(cwd: Path): ShellState =
    ShellState(cwd.toString, prevCwd = None, home = cwd.toString, now = 1725408000L, color = false)

  private def withTempDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("tabbyshell-executor-")
    try test(directory)
    finally
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.sortBy(path => -path.getNameCount).foreach(Files.deleteIfExists)
      finally paths.close()

  private val rawAi = new AiFormatter:
    def format(command: String, args: Vector[String], stdout: String): AiFormat = AiFormat(Value.Str(stdout))

  test("builtins start from Null and thread cd state through the pipeline"):
    withTempDirectory: directory =>
      val subdirectory = Files.createDirectory(directory.resolve("subdir"))
      val pipeline     = Pipeline(
        Vector(
          Command("cd", Vector(Arg.BareIdent("subdir"))),
          Command("pwd", Vector.empty)
        )
      )

      assertEquals(
        Executor.evaluate(pipeline, state(directory), aiFormatter = rawAi),
        Right(
          ExecutionResult(
            Value.Str(subdirectory.toString),
            state(directory).copy(cwd = subdirectory.toString, prevCwd = Some(directory.toString))
          )
        )
      )

  test("a builtin error short-circuits before a following external command"):
    withTempDirectory: directory =>
      var calls   = 0
      val process = new ProcessRunner:
        def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput] =
          calls += 1
          Right(ProcessOutput("should not run", 0))
      val pipeline = Pipeline(
        Vector(
          Command("where", Vector(Arg.BareIdent("missing"), Arg.Operator("=="), Arg.Literal(Literal.Int(1L)))),
          Command("echo", Vector(Arg.BareIdent("later")))
        )
      )

      assert(
        Executor
          .evaluate(pipeline, state(directory), process, rawAi)
          .left
          .exists:
            case ShellError.TypeMismatch("where", "table", "null") => true
            case _                                                 => false
      )
      assertEquals(calls, 0)

  test("external commands receive reconstructed parsed arguments and shell cwd"):
    withTempDirectory: directory =>
      var invocation = Option.empty[(String, Vector[String], String)]
      val process    = new ProcessRunner:
        def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput] =
          invocation = Some((name, args, cwd))
          Right(ProcessOutput("raw output", 0))
      val ai = new AiFormatter:
        def format(command: String, args: Vector[String], stdout: String): AiFormat =
          AiFormat(Value.Record(Vector("command" -> Value.Str(command), "output" -> Value.Str(stdout))))
      val pipeline = Pipeline(
        Vector(
          Command(
            "tool",
            Vector(
              Arg.BareIdent("input.txt"),
              Arg.Flag("a", None),
              Arg.Flag("verbose", None),
              Arg.Flag("count", Some(Literal.Int(2L))),
              Arg.Operator(">="),
              Arg.Dash
            )
          )
        )
      )

      assertEquals(
        Executor.evaluate(pipeline, state(directory), process, ai).map(_.value),
        Right(Value.Record(Vector("command" -> Value.Str("tool"), "output" -> Value.Str("raw output"))))
      )
      assertEquals(
        invocation,
        Some(("tool", Vector("input.txt", "-a", "--verbose", "--count=2", ">=", "-"), directory.toString))
      )

  test("a nonzero external status becomes the specified ShellError without AI formatting"):
    withTempDirectory: directory =>
      var formatted = false
      val process   = new ProcessRunner:
        def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput] = Right(ProcessOutput("ignored", 7))
      val ai = new AiFormatter:
        def format(command: String, args: Vector[String], stdout: String): AiFormat =
          formatted = true
          AiFormat(Value.Str(stdout))

      assertEquals(
        Executor.evaluate(Pipeline(Vector(Command("fails", Vector.empty))), state(directory), process, ai),
        Left(ShellError.ExternalFailed("fails", 7))
      )
      assertEquals(formatted, false)

  test("the disabled AI formatter keeps stdout and exposes one warning to the terminal boundary"):
    withTempDirectory: directory =>
      val process = new ProcessRunner:
        def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput] = Right(ProcessOutput("  raw output \n\t", 0))
      val unusedTransport = new Ai.Transport:
        def post(request: Ai.Request): Either[String, Ai.Response] = fail("disabled AI must not make an HTTP request")
      val disabledAi = Ai.formatter(Ai.Config(Some("test-key"), disabled = true), unusedTransport)

      assertEquals(
        Executor.evaluate(Pipeline(Vector(Command("echo", Vector.empty))), state(directory), process, disabledAi),
        Right(
          ExecutionResult(
            Value.Str("  raw output"),
            state(directory),
            Vector("(ai formatting unavailable: disabled)")
          )
        )
      )

  test("sort-by accepts the short reverse flag"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("items.json"), "[{\"name\":\"first\",\"rank\":1},{\"name\":\"last\",\"rank\":2}]")
      val pipeline = Pipeline(
        Vector(
          Command("open", Vector(Arg.BareIdent("items.json"))),
          Command("sort-by", Vector(Arg.BareIdent("rank"), Arg.Flag("r", None))),
          Command("get", Vector(Arg.BareIdent("name")))
        )
      )

      assertEquals(
        Executor.evaluate(pipeline, state(directory), aiFormatter = rawAi).map(_.value),
        Right(Value.List(Vector(Value.Str("last"), Value.Str("first"))))
      )

  test("builtin dispatch rejects unsupported flags, surplus arguments, and incomplete where clauses"):
    withTempDirectory: directory =>
      assertEquals(
        Executor.evaluate(Pipeline(Vector(Command("pwd", Vector(Arg.BareIdent("extra"))))), state(directory)).left.map(_.message),
        Left("pwd: too many arguments")
      )
      assertEquals(
        Executor.evaluate(Pipeline(Vector(Command("ls", Vector(Arg.Flag("hidden", None))))), state(directory)).left.map(_.message),
        Left("ls: unsupported flag: --hidden")
      )
      assertEquals(
        Executor.evaluate(Pipeline(Vector(Command("where", Vector.empty))), state(directory)).left.map(_.message),
        Left("where: missing required argument: column op literal")
      )
