package tabbyshell

import java.nio.file.Path

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal as JLineTerminalHandle
import org.jline.terminal.TerminalBuilder

/** A typed result from one attempt to read interactive input.
  *
  * Keeping Ctrl-C and Ctrl-D distinct from ordinary lines lets the REPL recover without treating terminal control flow as parser input.
  */
enum TerminalEvent:
  case Line(value: String)
  case Interrupt
  case EndOfFile

/** Narrow terminal boundary used only by the interactive REPL.
  *
  * The evaluator and renderer never depend on JLine. Tests provide a scripted implementation of this trait instead of allocating a real TTY.
  */
trait Terminal:
  def readLine(prompt: String): TerminalEvent
  def writeOut(text: String): Unit
  def writeErr(text: String): Unit
  def close(): Unit = ()

/** JLine implementation for the application boundary.
  *
  * History is loaded and persisted by JLine at `~/.tabbyshell_history`, with a maximum of 1,000 retained entries. Shell-style event expansion is disabled so
  * that a `!` in TabbyShell input remains ordinary input.
  */
final class JLineTerminal private (
  terminal: JLineTerminalHandle,
  reader: LineReader
) extends Terminal:

  def readLine(prompt: String): TerminalEvent =
    try TerminalEvent.Line(reader.readLine(prompt))
    catch
      case _: UserInterruptException => TerminalEvent.Interrupt
      case _: EndOfFileException     => TerminalEvent.EndOfFile

  def writeOut(text: String): Unit =
    terminal.writer().print(text)
    terminal.flush()

  def writeErr(text: String): Unit =
    System.err.print(text)
    System.err.flush()

  override def close(): Unit = terminal.close()

object JLineTerminal:

  private val historyLimit = 1000

  /** Constructs the system terminal; `home` comes from the application state, rather than being re-read by the REPL.
    */
  def system(home: String): JLineTerminal =
    val terminal    = TerminalBuilder.builder().system(true).build()
    val historyFile = Path.of(home, ".tabbyshell_history")
    val reader      = LineReaderBuilder
      .builder()
      .terminal(terminal)
      .variable(LineReader.HISTORY_FILE, historyFile)
      .variable(LineReader.HISTORY_SIZE, historyLimit)
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .build()
    new JLineTerminal(terminal, reader)
