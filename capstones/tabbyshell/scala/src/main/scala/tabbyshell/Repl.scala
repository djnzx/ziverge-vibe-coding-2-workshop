package tabbyshell

import java.nio.file.Path

import scala.annotation.tailrec

/** Interactive shell loop (SPEC 7). Its evaluator is injected so terminal behavior can be tested without executor, filesystem, or JLine dependencies.
  */
final class Repl(
  terminal: Terminal,
  evaluate: Repl.Evaluator,
  banner: String
):

  def run(initialState: ShellState): Unit =
    terminal.writeOut(banner)
    if banner.nonEmpty && !banner.endsWith("\n") then terminal.writeOut("\n")
    terminal.writeOut(Repl.Greeting + "\n")
    loop(initialState, "")

  @tailrec
  private def loop(state: ShellState, buffer: String): Unit =
    val prompt = if buffer.isEmpty then Repl.prompt(state) else Repl.ContinuationPrompt
    terminal.readLine(prompt) match
      case TerminalEvent.Line(line) if buffer.isEmpty && Repl.isExit(line) =>
        writeGoodbye(state)
      case TerminalEvent.Line(line) if Parser.continuesLine(line) =>
        loop(state, buffer + line.dropRight(1) + "\n")
      case TerminalEvent.Line(line) =>
        loop(evaluateInput(buffer + line, state), "")
      case TerminalEvent.Interrupt =>
        loop(state, "")
      case TerminalEvent.EndOfFile if buffer.isEmpty =>
        writeGoodbye(state)
      case TerminalEvent.EndOfFile =>
        loop(state, "")

  private def evaluateInput(input: String, state: ShellState): ShellState =
    Parser.parse(input) match
      case Left(error) =>
        writeError(error, state)
        state
      case Right(Pipeline(commands)) if commands.isEmpty =>
        state
      case Right(pipeline) =>
        evaluate(pipeline, state) match
          case Left(error) =>
            writeError(error, state)
            state
          case Right(result) =>
            result.warnings.foreach(writeWarning(_, result.state))
            terminal.writeOut(Renderer.render(result.value, Repl.renderOpts(result.state)))
            result.state

  private def writeError(error: ShellError, state: ShellState): Unit =
    terminal.writeErr(Renderer.renderError(error, Repl.renderOpts(state)))

  private def writeWarning(warning: String, state: ShellState): Unit =
    terminal.writeErr(Repl.dim(warning, state.color) + "\n")

  private def writeGoodbye(state: ShellState): Unit =
    terminal.writeOut(Repl.dim("goodbye.", state.color) + "\n")

object Repl:
  type Evaluator = (Pipeline, ShellState) => Either[ShellError, ExecutionResult]

  val Greeting: String           = "tabbyshell 0.1.0 — type 'exit' or Ctrl-D to quit"
  val ContinuationPrompt: String = "  "

  def prompt(state: ShellState): String = shortCwd(state.cwd, state.home) + " ❯ "

  /** The prompt form in SPEC 7.2. `Path.startsWith` preserves the directory boundary, unlike a raw string-prefix check (`/home/a` is not parent of `/home/ab`).
    */
  def shortCwd(cwd: String, home: String): String =
    val current = Path.of(cwd).normalize
    val homeDir = Path.of(home).normalize
    if current == homeDir then "~"
    else if current.startsWith(homeDir) then "~/" + homeDir.relativize(current).toString
    else cwd

  private def renderOpts(state: ShellState): RenderOpts =
    RenderOpts(color = state.color, now = state.now)

  private def isExit(line: String): Boolean =
    val trimmed = line.trim
    trimmed == "exit" || trimmed == "quit"

  private def dim(text: String, color: Boolean): String =
    if color then fansi.Bold.Faint(fansi.Str(text)).render else text
