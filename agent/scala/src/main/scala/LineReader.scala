package workshop.agent

import java.util.concurrent.atomic.AtomicReference
import org.jline.reader.{EndOfFileException, UserInterruptException}

/** Reads one line of user input at a time, given a prompt. Concrete instances:
  *   - [[LineReader.Jline]]: the production reader, backed by jline (ANSI editing, history,
  *     completion).
  *   - [[LineReader.Canned]]: the test fake, returning a fixed sequence of results.
  *   - [[LineReader.fromIterator]]: wraps an `Iterator[String]` (each item becomes one `Line`
  *     result, then `EndOfInput`) — used by file mode and protocol mode so the same driver loop
  *     handles every input source.
  *
  * The driver loop ([[run]]) is implemented here in terms of [[readLine]] — implementations vary
  * only in HOW lines arrive, not in the loop's state-threading, continuation, or lifecycle logic.
  * The continuation / Interrupted-retry / `goodbye()`-on-EOF behaviors are silently no-ops for
  * non-interactive sources (the iterator never produces Interrupted, `goodbye()` on a silent
  * terminal does nothing).
  */
trait LineReader {

  /** Read the next line of input. Returns one of:
    *   - [[LineReader.Result.Line]] with the user's text;
    *   - [[LineReader.Result.Interrupted]] if the user signalled Ctrl-C (the loop preserves the
    *     in-progress buffer and re-prompts);
    *   - [[LineReader.Result.EndOfInput]] if there is no more input (EOF, closed pipe, exhausted
    *     fixture). The loop terminates.
    */
  def readLine(prompt: String): LineReader.Result

  /** Drive a state forward by repeatedly calling `readLine` and handing each complete line
    * (post-continuation, post-trim, non-empty) to `body`. `body` returns `(nextState, continue)`.
    * Stops on `EndOfInput` (with `terminal.goodbye()`) or when `body` returns `(_, false)`.
    */
  final def run[S](
    terminal: TerminalOutput,
    initial: S
  )(body: (S, String) => (S, Boolean)): S = {
    @annotation.tailrec
    def loop(state: S, buffer: String): S = {
      val prompt = if (buffer.nonEmpty) "  " else terminal.promptString
      readLine(prompt) match {
        case LineReader.Result.EndOfInput =>
          terminal.goodbye()
          state

        case LineReader.Result.Interrupted =>
          loop(state, buffer)

        case LineReader.Result.Line(line) if line.endsWith("\\") =>
          loop(state, buffer + line.dropRight(1) + "\n")

        case LineReader.Result.Line(line) =>
          val userContent = (buffer + line).trim
          if (userContent.isEmpty) loop(state, "")
          else {
            val (nextState, continue) = body(state, userContent)
            if (continue) loop(nextState, "") else nextState
          }
      }
    }
    loop(initial, "")
  }
}

object LineReader {

  /** The outcome of one [[LineReader.readLine]] call. */
  enum Result {
    case Line(text: String)
    case Interrupted
    case EndOfInput
  }

  /** Production reader: jline. Catches EOF / IllegalState / UserInterrupt and folds them into
    * [[Result]] values so the loop doesn't deal in exceptions.
    */
  final class Jline(reader: org.jline.reader.LineReader) extends LineReader {
    def readLine(prompt: String): Result =
      try {
        val line = reader.readLine(prompt)
        if (line == null) Result.EndOfInput else Result.Line(line)
      } catch {
        case _: EndOfFileException     => Result.EndOfInput
        case _: IllegalStateException  => Result.EndOfInput
        case _: UserInterruptException => Result.Interrupted
      }
  }

  /** Test fake: returns a fixed sequence of results, then [[Result.EndOfInput]] forever. Construct
    * from raw strings via `LineReader.Canned("a", "b")` (each string becomes a `Line`), or from
    * explicit `Result` values for tests that need to inject `Interrupted` etc.
    */
  final class Canned(initial: List[Result]) extends LineReader {
    private val remaining = new AtomicReference[List[Result]](initial)

    def readLine(prompt: String): Result = {
      @annotation.tailrec
      def loop(): Result = {
        val current = remaining.get
        current match {
          case Nil => Result.EndOfInput
          case head :: tail =>
            if (remaining.compareAndSet(current, tail)) head
            else loop()
        }
      }
      loop()
    }
  }

  object Canned {
    def apply(lines: String*): Canned = new Canned(lines.toList.map(Result.Line(_)))
    def of(results: Result*): Canned  = new Canned(results.toList)
  }

  /** Wrap an `Iterator[String]` as a [[LineReader]]. Each item becomes one `Result.Line`;
    * exhaustion yields `Result.EndOfInput`. Lets file/protocol modes share the interactive driver
    * loop without owning a duplicate fold-over-stream implementation.
    */
  def fromIterator(inputs: Iterator[String]): LineReader =
    new LineReader {
      def readLine(prompt: String): Result =
        if (inputs.hasNext) Result.Line(inputs.next())
        else Result.EndOfInput
    }
}
