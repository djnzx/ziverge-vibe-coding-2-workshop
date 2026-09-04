package snap

/** SPEC §7.11 — the selected output presentation.
  *
  * Choosing one MUST NOT change execution, repository or filesystem effects, warning selection or order, or exit status.
  */
enum Presentation:
  case Plain, Terminal

/** SPEC §7.11 / §10 — selection and rendering for Snap's plain and terminal presentations.
  *
  * Commands choose presentation once at the CLI edge, before dispatch, then pass that selected value here. The renderers only affect bytes written to streams:
  * they do not inspect the environment, terminal, clock, repository, or working tree.
  */
object Presentation:

  /** The user-visible Snap release, deliberately independent of `build.sbt`'s artifact version. */
  val release: String = "1.0.0"

  private val lineFeed = "\n"

  /** §7.11 selection for one stream. Call separately with `Streams.stdoutIsTty` and `Streams.stderrIsTty` when output and error presentation may differ. */
  def select(snapColor: Option[String], noColor: Option[String], isTty: Boolean): Either[SnapError, Presentation] =
    snapColor match
      case Some("always")      => Right(Terminal)
      case Some("never")       => Right(Plain)
      case None | Some("auto") =>
        if noColor.nonEmpty then Right(Plain)
        else if isTty then Right(Terminal)
        else Right(Plain)
      case _ => Left(SnapError("SNAP_COLOR must be auto, always, or never"))

  /** SPEC §7.11's `S(n, text)`. This is the only function that emits an ANSI SGR sequence. */
  def s(code: Int, text: String): String =
    s"\u001b[${code}m${text}\u001b[0m"

  /** Successful `init`, `commit`, `revert`, and `merge` records. Plain mode preserves the §7 command result: only the version. */
  def success(presentation: Presentation, label: SuccessLabel, version: Version): String = presentation match
    case Plain    => Versions.render(version) + lineFeed
    case Terminal => s(32, "✓") + " " + s(1, label.text) + " " + s(36, Versions.render(version)) + lineFeed

  /** `status` in its §7.3 plain form or §7.11 terminal layout. `entries` must already be sorted by tracked path. */
  def status(presentation: Presentation, version: Version, entries: Vector[StatusEntry]): String = presentation match
    case Plain =>
      val rows = entries.map(entry => s"${entry.code.code} ${entry.path.value}${lineFeed}").mkString
      s"version ${Versions.render(version)}${lineFeed}$rows"
    case Terminal =>
      val header = s(1, "Snap status") + "  " + s(36, Versions.render(version)) + lineFeed + lineFeed
      if entries.isEmpty then header + "  " + s(32, "✓") + " Working tree clean" + lineFeed
      else header + entries.map(terminalStatusRow).mkString

  /** `log` in its §7.4 plain form or §7.11 terminal layout. `patches` must already be in reverse canonical integration order. */
  def log(presentation: Presentation, patches: Vector[Patch]): String = presentation match
    case Plain    => patches.map(plainLogEntry).mkString
    case Terminal => patches.map(terminalLogEntry).mkString(lineFeed)

  /** Styles the complete text of each §7.11 diff line while preserving every other byte, including LF terminators. */
  def diff(presentation: Presentation, plain: String): String = presentation match
    case Plain    => plain
    case Terminal => mapLines(plain)(terminalDiffLine)

  /** `snap --version`. */
  def version(presentation: Presentation): String = presentation match
    case Plain    => s"snap $release$lineFeed"
    case Terminal => s(1, s"snap $release") + lineFeed

  /** §6.4 warning record. */
  def warning(presentation: Presentation, detail: String): String = presentation match
    case Plain    => s"warning: $detail$lineFeed"
    case Terminal => s(33, "⚠") + " " + s(33, detail) + lineFeed

  /** §10 expected-error record. `error` is the typed detail, without the `snap:` prefix. */
  def error(presentation: Presentation, error: SnapError): String =
    val plain = s"snap: ${error.detail}"
    presentation match
      case Plain    => plain + lineFeed
      case Terminal => s(31, s"✗ $plain") + lineFeed

  /** The §7.9 URL is deliberately plain in both modes. */
  def serveUrl(url: String): String = url + lineFeed

  /** §7.2 configuration is silent in both modes. */
  val silent: String = ""

  /** Removes SGR sequences emitted by [[s]]. Useful for focused layout tests; it does not interpret any other terminal escape sequence. */
  def stripSgr(text: String): String =
    val introducer = s(0, "").take(2)
    val output     = new StringBuilder
    var index      = 0
    while index < text.length do
      if text.startsWith(introducer, index) then
        val end = text.indexOf('m', index + introducer.length)
        if end >= 0 && text.substring(index + introducer.length, end).forall(_.isDigit) then index = end + 1
        else
          output.append(text.charAt(index))
          index += 1
      else
        output.append(text.charAt(index))
        index += 1
    output.toString

  // ---- terminal layouts ------------------------------------------------------

  private def terminalStatusRow(entry: StatusEntry): String =
    val (color, symbol, label) = entry.code match
      case StatusCode.Added    => (32, "+", "added")
      case StatusCode.Deleted  => (31, "−", "deleted")
      case StatusCode.Modified => (33, "~", "modified")
    "  " + s(color, symbol) + " " + entry.path.value + " " + s(2, s"($label)") + lineFeed

  private def plainLogEntry(patch: Patch): String =
    s"${Versions.render(patch.result)}\t${patch.author.value}\t${escapeMessage(patch.message)}$lineFeed"

  private def terminalLogEntry(patch: Patch): String =
    s(36, "●") + " " + s(1, escapeMessage(patch.message)) + lineFeed +
      "  " + s(36, Versions.render(patch.result)) + " " + s(2, "by") + " " + s(35, patch.author.value) + lineFeed

  private def terminalDiffLine(line: String): String =
    val style =
      if line.startsWith("--- ") || line.startsWith("+++ ") then Some(1)
      else if line.startsWith("@@ ") then Some(36)
      else if line.startsWith("-") then Some(31)
      else if line.startsWith("+") then Some(32)
      else if line.startsWith("\\ ") then Some(2)
      else if line.startsWith("Binary files ") then Some(33)
      else None
    style.fold(line)(code => s(code, line))

  /** Splits without changing newline ownership: a terminal diff must style line content but retain every plain LF byte unmodified. */
  private def mapLines(text: String)(render: String => String): String =
    val output = new StringBuilder
    var start  = 0
    while start < text.length do
      val newline = text.indexOf('\n', start)
      if newline < 0 then
        output.append(render(text.substring(start)))
        start = text.length
      else
        output.append(render(text.substring(start, newline))).append('\n')
        start = newline + 1
    output.toString

  /** §7.4 escapes backslash, tab, and LF in that order so log records remain one line. */
  private def escapeMessage(message: String): String =
    message.flatMap:
      case '\\' => "\\\\"
      case '\t' => "\\t"
      case '\n' => "\\n"
      case char => char.toString

/** The finite success labels §7.11 assigns color and wording to. */
enum SuccessLabel(val text: String):
  case Initialized extends SuccessLabel("Initialized repository")
  case Committed   extends SuccessLabel("Committed")
  case Reverted    extends SuccessLabel("Reverted")
  case Merged      extends SuccessLabel("Merged")
