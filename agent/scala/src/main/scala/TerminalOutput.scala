package workshop.agent

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Presentation-only boundary for interactive mode. Implementations must not mutate agent state.
  * spinnerStart/spinnerStop are expected to be balanced lifecycle calls.
  *
  * In practice there are two implementations: [[TerminalOutput.Real]] (the interactive ANSI
  * renderer) and [[TerminalOutput.Recording]] (the deterministic test fake). Construct via
  * [[TerminalOutput.real]] / [[TerminalOutput.recording]].
  */
trait TerminalOutput {
  def banner(model: String, tools: Seq[ToolName]): Unit
  def promptString: String
  def thinking(text: String): Unit
  def toolCall(call: ToolCall.Any): Unit
  def toolResult(result: ToolResult): Unit
  def answer(text: String): Unit
  def error(text: String): Unit
  def spinnerStart(): Unit
  def spinnerStop(): Unit
  def goodbye(): Unit
}

object TerminalOutput {

  /** Factory: the production ANSI renderer. */
  def real: TerminalOutput = new Real

  /** Factory: the test fake. Returned as the concrete `Recording` type so callers can read
    * `.events` for assertions.
    */
  def recording: Recording = new Recording

  /** Factory: a no-op terminal. Protocol/file modes always pass this through `Agent.step` so the
    * agent loop can call `terminal.X` unconditionally instead of pattern-matching on
    * `Option[TerminalOutput]`. Silent's methods discard their arguments; `promptString` returns the
    * empty string.
    */
  def silent: TerminalOutput = new Silent

  /** No-op TerminalOutput. Used for non-interactive modes (protocol, file) so the agent loop is
    * uniform: always render through a terminal, with Silent acting as the null object.
    */
  final class Silent extends TerminalOutput {
    def banner(model: String, tools: Seq[ToolName]): Unit = ()
    def promptString: String                              = ""
    def thinking(text: String): Unit                      = ()
    def toolCall(call: ToolCall.Any): Unit                = ()
    def toolResult(result: ToolResult): Unit              = ()
    def answer(text: String): Unit                        = ()
    def error(text: String): Unit                         = ()
    def spinnerStart(): Unit                              = ()
    def spinnerStop(): Unit                               = ()
    def goodbye(): Unit                                   = ()
  }

  /** Interactive ANSI renderer: human output to stdout, spinner frames to stderr (avoids corrupting
    * protocol output). NO_COLOR env var disables ANSI styling.
    */
  final class Real extends TerminalOutput {
    private val useColor = sys.env.get("NO_COLOR").isEmpty

    private val Bold   = "\u001b[1m"
    private val Dim    = "\u001b[2m"
    private val Cyan   = "\u001b[36m"
    private val Yellow = "\u001b[33m"
    private val Green  = "\u001b[32m"
    private val Red    = "\u001b[31m"
    private val White  = "\u001b[37m"
    private val Gray   = "\u001b[90m"
    private val Reset  = "\u001b[0m"

    private def style(text: String, codes: String*): String =
      if (useColor) s"${codes.mkString}$text$Reset" else text

    private def stripThinkingPrefix(text: String): String =
      text.replaceFirst("(?is)^\\s*THINKING:\\s*", "").trim

    private val running       = new AtomicBoolean(false)
    private val spinnerThread = new AtomicReference[Option[Thread]](None)

    def banner(model: String, tools: Seq[ToolName]): Unit = {
      val toolText  = tools.map(_.wire).mkString(", ")
      val modelLine = s"model: $model"
      val toolsLine = s"tools: $toolText"
      val width     = math.max(math.max(model.length + 10, toolText.length + 10), 50)
      val pad       = (s: String) => s.padTo(width - 4, ' ')
      println(s"${style(s"╭${"─" * (width - 2)}╮", Dim)}")
      println(s"${style("│", Dim)} ${pad(modelLine)} ${style("│", Dim)}")
      println(s"${style("│", Dim)} ${pad(toolsLine)} ${style("│", Dim)}")
      println(s"${style(s"╰${"─" * (width - 2)}╯", Dim)}")
      println()
    }

    def promptString: String = style("> ", Bold, Cyan)

    def thinking(text: String): Unit = {
      val cleaned = stripThinkingPrefix(text)
      if (cleaned.nonEmpty)
        cleaned.linesIterator.foreach(line => println(s"  ${style(s"● $line", Dim, Gray)}"))
    }

    def toolCall(call: ToolCall.Any): Unit = {
      println(s"  ${style("CALLING:", Bold, Cyan)} ${style(call.name.wire, Bold, Yellow)}")
      call.paramsFields.foreach { case (k, v) =>
        println(s"    $k: ${ArgDisplay.render(v)}")
      }
    }

    def toolResult(result: ToolResult): Unit = {
      val (text, isError) = result match {
        case ToolResult.Success(output) => (output, false)
        case ToolResult.Failure(error)  => (error, true)
      }
      val prefix = if (isError) s"  ${style("✗", Red)} " else s"  ${style("✓", Green, Dim)} "
      def colorLine(line: String): String =
        if (isError) style(line, Red) else style(line, Green, Dim)

      val lines  = text.linesIterator.toVector
      val total  = lines.length
      val shown  = if (total > 10) lines.take(10) else lines
      val hidden = total - shown.length

      shown.headOption match {
        case Some(first) =>
          println(s"$prefix${colorLine(first)}")
          shown.drop(1).foreach(line => println(s"    ${colorLine(line)}"))
        case None =>
          println(s"  ${if (isError) style("✗", Red) else style("✓", Green, Dim)}")
      }
      if (hidden > 0) println(s"    ${style(s"[+$hidden more lines]", Gray)}")
    }

    def answer(text: String): Unit = {
      val lines = text.linesIterator.toVector
      lines.headOption match {
        case Some(first) =>
          println(s"  ${style("■", White, Bold)} $first")
          lines.drop(1).foreach(line => println(s"    $line"))
        case None =>
          println(s"  ${style("■", White, Bold)}")
      }
    }

    def error(text: String): Unit =
      println(style(s"  ✗ $text", Bold, Red))

    def spinnerStart(): Unit = synchronized {
      if (running.compareAndSet(false, true)) {
        val frames = Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        val thread = new Thread(() =>
          Iterator.from(0).takeWhile(_ => running.get).foreach { i =>
            val frame = frames(i % frames.length)
            Console.err.print(s"\r  ${style(frame, Gray)}")
            Console.err.flush()
            Thread.sleep(80)
          }
        )
        thread.setDaemon(true)
        spinnerThread.set(Some(thread))
        thread.start()
      }
    }

    def spinnerStop(): Unit = synchronized {
      if (running.compareAndSet(true, false)) {
        spinnerThread.getAndSet(None).foreach(_.join(200))
        Console.err.print("\r  \r")
        Console.err.flush()
      }
    }

    def goodbye(): Unit =
      println(style("goodbye.", Gray))
  }

  /** Deterministic test double that stores copied event data for assertions, not a renderer. */
  final class Recording extends TerminalOutput {
    private val _events = new AtomicReference[Vector[TerminalEvent]](Vector.empty)

    def events: Vector[TerminalEvent] = _events.get

    private def append(event: TerminalEvent): Unit = {
      @annotation.tailrec
      def loop(): Unit = {
        val current = _events.get
        if (!_events.compareAndSet(current, current :+ event)) loop()
      }
      loop()
    }

    def banner(model: String, tools: Seq[ToolName]): Unit =
      append(TerminalEvent.Banner(model, tools))

    def promptString: String = "> "

    def thinking(text: String): Unit =
      append(TerminalEvent.Thinking(text))

    def toolCall(call: ToolCall.Any): Unit =
      append(TerminalEvent.ToolCall(call))

    def toolResult(result: ToolResult): Unit =
      append(TerminalEvent.ToolResult(result))

    def answer(text: String): Unit =
      append(TerminalEvent.Answer(text))

    def error(text: String): Unit =
      append(TerminalEvent.Error(text))

    def spinnerStart(): Unit =
      append(TerminalEvent.SpinnerStart)

    def spinnerStop(): Unit =
      append(TerminalEvent.SpinnerStop)

    def goodbye(): Unit =
      append(TerminalEvent.Goodbye)
  }
}
