package tabbyshell

import java.io.File
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Result of one operating-system process invocation before AI formatting. */
final case class ProcessOutput(stdout: String, status: Int)

/** Injectable external-process boundary. The error string is converted to the SPEC IoError by Executor. */
trait ProcessRunner:
  def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput]

object ProcessRunner:
  val live: ProcessRunner = new ProcessRunner:
    def run(name: String, args: Vector[String], cwd: String): Either[String, ProcessOutput] =
      try
        val process = new ProcessBuilder((name +: args).asJava)
          .directory(File(cwd))
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
        val stdout = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        Right(ProcessOutput(stdout, process.waitFor()))
      catch case error: Throwable => Left(errorMessage(error))

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

/** Typed, terminal-free pipeline interpreter (SPEC 5.15 and 9). */
object Executor:

  private type Builtin = (Command, ExecutionResult) => Either[ShellError, ExecutionResult]

  /** Exactly the 14 heads defined by SPEC 5.1–5.14. */
  private val builtins: Map[String, Builtin] = Map(
    "ls"      -> runLs,
    "open"    -> runOpen,
    "cat"     -> runCat,
    "pwd"     -> runPwd,
    "cd"      -> runCd,
    "where"   -> runWhere,
    "select"  -> runSelect,
    "sort-by" -> runSortBy,
    "first"   -> runFirst,
    "last"    -> runLast,
    "length"  -> runLength,
    "get"     -> runGet,
    "to"      -> runTo,
    "save"    -> runSave
  )

  /** Evaluates from Null, threading state and warning data left-to-right. */
  def evaluate(
    pipeline: Pipeline,
    state: ShellState,
    processRunner: ProcessRunner = ProcessRunner.live,
    aiFormatter: AiFormatter = Ai.live
  ): Either[ShellError, ExecutionResult] =
    pipeline.commands.foldLeft[Either[ShellError, ExecutionResult]](Right(ExecutionResult(Value.Null, state))): (current, command) =>
      current.flatMap: result =>
        execute(command, result, processRunner, aiFormatter).map(next => next.copy(warnings = result.warnings ++ next.warnings))

  /** Alias for callers that use the interpreter as a command runner. */
  def run(
    pipeline: Pipeline,
    state: ShellState,
    processRunner: ProcessRunner = ProcessRunner.live,
    aiFormatter: AiFormatter = Ai.live
  ): Either[ShellError, ExecutionResult] = evaluate(pipeline, state, processRunner, aiFormatter)

  private def execute(
    command: Command,
    input: ExecutionResult,
    processRunner: ProcessRunner,
    aiFormatter: AiFormatter
  ): Either[ShellError, ExecutionResult] =
    builtins.get(command.name) match
      case Some(runBuiltin) => runBuiltin(command, input)
      case None             => runExternal(command, input, processRunner, aiFormatter)

  private def runLs(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1, allowedFlags = Set("a", "all", "l", "long")).flatMap { _ =>
      optionalPath(arguments, "path").flatMap { path =>
        Builtins.ls(path, arguments.hasFlag("a", "all"), arguments.hasFlag("l", "long"), input.state)
      }
    }

  private def runOpen(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1).flatMap(_ => requiredPath(arguments, "path")).flatMap(Builtins.open(_, input.state))

  private def runCat(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1).flatMap(_ => requiredPath(arguments, "path")).flatMap(Builtins.cat(_, input.state))

  private def runPwd(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    validate(Arguments.from(command), maximumPositionals = 0).map(_ => Builtins.pwd(input.state))

  private def runCd(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1).flatMap(_ => cdTarget(arguments)).flatMap(Builtins.cd(_, input.state))

  private def runWhere(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    for
      _        <- validate(arguments, maximumPositionals = 3)
      _        <- Option.when(arguments.positionals.size >= 3)(()).toRight(ShellError.MissingArg("where", "column op literal"))
      column   <- arguments.bareIdentifier(0, "column")
      operator <- arguments.operator(1, "operator")
      literal  <- arguments.literal(2, "literal")
      value    <- Builtins.where(input.value, column, operator, literal)
    yield ExecutionResult(value, input.state)

  private def runSelect(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments).flatMap(_ => decodeColumns(arguments)).flatMap(Builtins.select(input.value, _)).map(value => ExecutionResult(value, input.state))

  private def runSortBy(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    for
      _      <- validate(arguments, maximumPositionals = 1, allowedFlags = Set("reverse", "r"))
      column <- arguments.columnName(0, "column")
      value  <- Builtins.sortBy(input.value, column, arguments.hasFlag("reverse", "r"))
    yield ExecutionResult(value, input.state)

  private def runFirst(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1)
      .flatMap(_ => optionalCount(arguments, "count"))
      .flatMap(Builtins.first(input.value, _))
      .map(value => ExecutionResult(value, input.state))

  private def runLast(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1)
      .flatMap(_ => optionalCount(arguments, "count"))
      .flatMap(Builtins.last(input.value, _))
      .map(value => ExecutionResult(value, input.state))

  private def runLength(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    validate(Arguments.from(command), maximumPositionals = 0).flatMap(_ => Builtins.length(input.value)).map(value => ExecutionResult(value, input.state))

  private def runGet(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1)
      .flatMap(_ => arguments.columnName(0, "column"))
      .flatMap(Builtins.get(input.value, _))
      .map(value => ExecutionResult(value, input.state))

  private def runTo(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1)
      .flatMap(_ => arguments.bareIdentifier(0, "format"))
      .flatMap(Builtins.to(input.value, _))
      .map(value => ExecutionResult(value, input.state))

  private def runSave(command: Command, input: ExecutionResult): Either[ShellError, ExecutionResult] =
    val arguments = Arguments.from(command)
    validate(arguments, maximumPositionals = 1).flatMap(_ => requiredPath(arguments, "path")).flatMap(Builtins.save(input.value, _, input.state))

  private def runExternal(
    command: Command,
    input: ExecutionResult,
    processRunner: ProcessRunner,
    aiFormatter: AiFormatter
  ): Either[ShellError, ExecutionResult] =
    val args = command.args.map(renderExternalArgument)
    processRunner
      .run(command.name, args, input.state.cwd)
      .left
      .map(ShellError.IoError(command.name, _))
      .flatMap: output =>
        if output.status != 0 then Left(ShellError.ExternalFailed(command.name, output.status))
        else
          val formatted = aiFormatter.format(command.name, args, output.stdout)
          Right(ExecutionResult(formatted.value, input.state, formatted.warnings))

  private def optionalPath(arguments: Arguments, name: String): Either[ShellError, Option[String]] =
    arguments.positionals.headOption match
      case None    => Right(None)
      case Some(_) => pathAt(arguments, 0, name).map(Some.apply)

  private def requiredPath(arguments: Arguments, name: String): Either[ShellError, String] = pathAt(arguments, 0, name)

  private def pathAt(arguments: Arguments, index: Int, name: String): Either[ShellError, String] =
    arguments
      .required(index, name)
      .flatMap:
        case Arg.BareIdent(value)            => Right(value)
        case Arg.Literal(Literal.Str(value)) => Right(value)
        case _                               => Left(ShellError.BadArg(arguments.commandName, s"expected path for $name"))

  private def cdTarget(arguments: Arguments): Either[ShellError, CdTarget] =
    arguments.positionals.headOption match
      case None                                 => Right(CdTarget.Home)
      case Some(Arg.Dash)                       => Right(CdTarget.Previous)
      case Some(Arg.BareIdent(value))           => Right(CdTarget.Path(value))
      case Some(Arg.Literal(Literal.Str(path))) => Right(CdTarget.Path(path))
      case Some(_)                              => Left(ShellError.BadArg("cd", "expected path"))

  private def decodeColumns(arguments: Arguments): Either[ShellError, Vector[String]] =
    arguments.positionals.indices.foldRight[Either[ShellError, Vector[String]]](Right(Vector.empty)): (index, decoded) =>
      for
        rest   <- decoded
        column <- arguments.columnName(index, "column")
      yield column +: rest

  private def optionalCount(arguments: Arguments, name: String): Either[ShellError, Option[Long]] =
    arguments.positionals.headOption match
      case None                                  => Right(None)
      case Some(Arg.Literal(Literal.Int(value))) => Right(Some(value))
      case Some(_)                               => Left(ShellError.BadArg(arguments.commandName, s"expected integer for $name"))

  private def validate(
    arguments: Arguments,
    maximumPositionals: Int = Int.MaxValue,
    allowedFlags: Set[String] = Set.empty
  ): Either[ShellError, Unit] =
    arguments.flags.find(flag => !allowedFlags.contains(flag.name) || flag.value.nonEmpty) match
      case Some(flag) => Left(ShellError.BadArg(arguments.commandName, s"unsupported flag: --${flag.name}"))
      case None       =>
        if arguments.positionals.size > maximumPositionals then Left(ShellError.BadArg(arguments.commandName, "too many arguments"))
        else Right(())

  /** Restores every parser argument category for an OS process invocation. */
  private def renderExternalArgument(argument: Arg): String = argument match
    case Arg.BareIdent(value)        => value
    case Arg.Literal(value)          => renderLiteral(value)
    case Arg.Operator(value)         => value
    case Arg.Dash                    => "-"
    case Arg.Flag(name, None)        => if name.length == 1 then s"-$name" else s"--$name"
    case Arg.Flag(name, Some(value)) => s"--$name=${renderLiteral(value)}"

  private def renderLiteral(literal: Literal): String = literal match
    case Literal.Str(value)      => value
    case Literal.Int(value)      => value.toString
    case Literal.Float(value)    => value.toString
    case Literal.Bool(value)     => value.toString
    case Literal.Filesize(bytes) => bytes.toString
    case Literal.Null            => "null"
