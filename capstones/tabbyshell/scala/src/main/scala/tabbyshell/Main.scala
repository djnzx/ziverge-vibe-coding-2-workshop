package tabbyshell

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Application boundary for environment, clock, filesystem script input, and terminal output. Command execution itself remains in `Executor`.
  */
object Main:

  private val version = "0.1.0"

  def main(args: Array[String]): Unit =
    val exitCode =
      try run(args.toSeq, sys.env)
      catch
        case NonFatal(error) =>
          Console.err.print(s"tabbyshell: internal error: ${message(error)}\n")
          2
    if exitCode != 0 then sys.exit(exitCode)

  /** Initializes the complete, immutable state once at the application edge (SPEC 8–9). This public seam keeps precedence and time parsing testable.
    */
  def initialState(
    environment: Map[String, String],
    cwd: Path,
    stdoutIsTty: Boolean,
    noColor: Boolean
  ): ShellState =
    val absoluteCwd = cwd.toAbsolutePath.normalize
    val home        = environment
      .get("HOME")
      .filter(_.nonEmpty)
      .map(Path.of(_))
      .getOrElse(Path.of(sys.props.getOrElse("user.home", absoluteCwd.toString)))
      .toAbsolutePath
      .normalize
    val now   = environment.get("TABBY_NOW").flatMap(_.toLongOption).getOrElse(Instant.now.getEpochSecond)
    val color = !noColor && !environment.get("NO_COLOR").exists(_.nonEmpty) && stdoutIsTty
    ShellState(absoluteCwd.toString, prevCwd = None, home.toString, now, color)

  private def run(args: Seq[String], environment: Map[String, String]): Int =
    Cli.parse(args) match
      case Left(detail) =>
        Console.err.print(detail + "\n")
        1
      case Right(options) =>
        val state = initialState(environment, Path.of("."), stdoutIsTty, options.noColor)
        options.mode match
          case CliMode.Version =>
            Console.out.print(s"tabbyshell $version\n")
            0
          case CliMode.Eval(pipeline) => runOne(pipeline, state, Console.out.print, Console.err.print)
          case CliMode.EvalFile(path) =>
            readScript(path) match
              case Left(error) =>
                writeError(error, state, Console.err.print)
                1
              case Right(script) => runScript(script, state, Console.out.print, Console.err.print)
          case CliMode.Interactive =>
            val terminal = JLineTerminal.system(state.home)
            try
              new Repl(terminal, (pipeline, currentState) => Executor.evaluate(pipeline, currentState), banner(environment)).run(state)
              0
            finally terminal.close()

  private def runOne(
    source: String,
    state: ShellState,
    writeOut: String => Unit,
    writeErr: String => Unit
  ): Int =
    evaluateSource(source, state, writeOut, writeErr).fold(identity, _ => 0)

  private def runScript(
    script: String,
    initialState: ShellState,
    writeOut: String => Unit,
    writeErr: String => Unit
  ): Int =
    @tailrec
    def evaluateLines(lines: Vector[String], state: ShellState): Int =
      if lines.isEmpty then 0
      else
        evaluateSource(lines.head, state, writeOut, writeErr) match
          case Left(exitCode) => exitCode
          case Right(next)    => evaluateLines(lines.tail, next)

    evaluateLines(Parser.logicalLines(script), initialState)

  private def evaluateSource(
    source: String,
    state: ShellState,
    writeOut: String => Unit,
    writeErr: String => Unit
  ): Either[Int, ShellState] =
    Parser.parse(source) match
      case Left(error) =>
        writeError(error, state, writeErr)
        Left(1)
      case Right(Pipeline(commands)) if commands.isEmpty => Right(state)
      case Right(pipeline)                               =>
        Executor.evaluate(pipeline, state) match
          case Left(error) =>
            writeError(error, state, writeErr)
            Left(1)
          case Right(result) =>
            result.warnings.foreach(warning => writeErr(warning + "\n"))
            writeOut(Renderer.render(result.value, RenderOpts(color = false, now = result.state.now)))
            Right(result.state)

  private def readScript(path: String): Either[ShellError, String] =
    try
      if path == "-" then Right(String(System.in.readAllBytes(), StandardCharsets.UTF_8))
      else Right(Files.readString(Path.of(path)))
    catch
      case error: IOException       => Left(ShellError.IoError("eval-file", message(error)))
      case error: SecurityException => Left(ShellError.IoError("eval-file", message(error)))

  private def banner(environment: Map[String, String]): String =
    environment
      .get("TABBY_PROJECT_ROOT")
      .filter(_.nonEmpty)
      .flatMap: root =>
        try Some(Files.readString(Path.of(root, "banner.txt")))
        catch
          case _: IOException       => None
          case _: SecurityException => None
      .getOrElse("")

  private def stdoutIsTty: Boolean = Option(System.console).isDefined

  private def writeError(error: ShellError, state: ShellState, writeErr: String => Unit): Unit =
    writeErr(Renderer.renderError(error, RenderOpts(color = false, now = state.now)))

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
