package tabbyshell

import java.util.Locale

/** `now` is threaded in rather than read from the clock, so `color = false` output is byte-identical across implementations (SPEC 6).
  */
final case class RenderOpts(color: Boolean, now: Long, maxColWidth: Int = 40)

object Renderer:

  def render(value: Value, opts: RenderOpts): String = value match
    // A string already ending in a newline is emitted as-is, never doubled (SPEC 6.3).
    case Value.Str(s) if s.endsWith("\n") => s
    case Value.Table(columns, rows)       => renderTable(columns, rows, opts)
    case Value.Record(fields)             => renderRecord(fields, opts)
    case Value.List(items)                => renderList(items, opts)
    case scalarValue                      => scalar(scalarValue, opts) + "\n"

  /** Errors render as `✗ <message>`, bold red when colour is on (SPEC 6.6, 7.5). */
  def renderError(error: ShellError, opts: RenderOpts): String =
    paint("\u2717 " + error.message, errorAttrs, opts) + "\n"

  /** The compact JSON-ish form used inside cells and for non-uniform lists (SPEC 6.3). */
  def compact(value: Value, opts: RenderOpts): String = value match
    case Value.Str(s)     => "\"" + jsonEscape(s) + "\""
    case Value.Record(fs) => fs.map((k, v) => s"$k: ${compact(v, opts)}").mkString("{", ", ", "}")
    case Value.List(xs)   => xs.map(compact(_, opts)).mkString("[", ", ", "]")
    // Nested tables never recurse into their cells.
    case Value.Table(columns, rows) => s"<table ${rows.size}×${columns.size}>"
    case other                      => scalar(other, opts)

  // --- per-variant scalar text (SPEC 6.3) ---

  private def scalar(value: Value, opts: RenderOpts): String = value match
    case Value.Null        => ""
    case Value.Bool(b)     => b.toString
    case Value.Int(n)      => n.toString
    case Value.Float(d)    => float(d)
    case Value.Str(s)      => s
    case Value.Filesize(b) => filesize(b)
    case Value.Date(t)     => date(t, opts.now)
    case other             => compact(other, opts)

  private def float(d: Double): String =
    val fixed   = String.format(Locale.ROOT, "%.4f", d)
    val trimmed = fixed.reverse.dropWhile(_ == '0').reverse
    if trimmed.endsWith(".") then trimmed.dropRight(1) else trimmed

  /** SI units on output, one decimal, round half away from zero (SPEC 6.5). */
  private def filesize(bytes: Long): String =
    val magnitude       = BigInt(bytes).abs
    val (divisor, unit) =
      if magnitude < BigInt(1000L) then (1L, "B")
      else if magnitude < BigInt(1000000L) then (1000L, "KB")
      else if magnitude < BigInt(1000000000L) then (1000000L, "MB")
      else if magnitude < BigInt(1000000000000L) then (1000000000L, "GB")
      else (1000000000000L, "TB")
    if divisor == 1L then s"$bytes B"
    else
      val scaled = (BigDecimal(bytes) / BigDecimal(divisor))
        .setScale(1, BigDecimal.RoundingMode.HALF_UP)
      val text    = scaled.bigDecimal.toPlainString
      val trimmed = if text.endsWith(".0") then text.dropRight(2) else text
      s"$trimmed $unit"

  /** Relative buckets, else ISO (SPEC 6.4). */
  private def date(ts: Long, now: Long): String =
    val delta = now - ts
    if delta < 0 then iso(ts)
    else if delta < 60 then "just now"
    else if delta < 3600 then s"${delta / 60} minutes ago"
    else if delta < 86400 then s"${delta / 3600} hours ago"
    else if delta < 86400 * 2 then "yesterday"
    else if delta < 86400 * 30 then s"${delta / 86400} days ago"
    else if delta < 86400 * 365 then s"${delta / 86400 / 30} months ago"
    else iso(ts)

  /** Howard Hinnant's civil_from_days; no library required (SPEC 6.4). */
  private def iso(ts: Long): String =
    val days  = (if ts >= 0 then ts / 86400 else -((-ts + 86399) / 86400)) + 719468
    val era   = if days >= 0 then days / 146097 else (days - 146096) / 146097
    val doe   = days - era * 146097
    val yoe   = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy   = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp    = (5 * doy + 2) / 153
    val day   = doy - (153 * mp + 2) / 5 + 1
    val month = if mp < 10 then mp + 3 else mp - 9
    val year  = (yoe + era * 400) + (if month <= 2 then 1 else 0)
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

  // --- colour (SPEC 6.6) ---
  //
  // fansi owns the escape sequences; nothing here writes ANSI by hand. Attributes are
  // applied only to a cell's content, never to its padding, so widths stay computed
  // from plain text and colour-off output is unchanged byte for byte.

  private val dimAttrs    = fansi.Bold.Faint
  private val headerAttrs = fansi.Bold.On ++ fansi.Color.Cyan
  private val errorAttrs  = fansi.Bold.On ++ fansi.Color.Red

  private def paint(text: String, attrs: fansi.Attrs, opts: RenderOpts): String =
    if !opts.color then text
    else attrs(fansi.Str(text, errorMode = fansi.ErrorMode.Sanitize)).render

  /** `Null`, `Float` and `Str` are deliberately left uncoloured (SPEC 6.6). */
  private def attrsFor(value: Value): fansi.Attrs = value match
    case Value.Int(_) | Value.Filesize(_) => fansi.Color.Green
    case Value.Date(_)                    => fansi.Color.Yellow
    case Value.Bool(true)                 => fansi.Color.Green
    case Value.Bool(false)                => fansi.Color.Red
    case _                                => fansi.Attrs.Empty

  // --- grid layout (SPEC 6.1, 6.2) ---

  private final case class Cell(text: String, attrs: fansi.Attrs)

  private def cellOf(value: Value, opts: RenderOpts): Cell =
    Cell(if value.isScalar then scalar(value, opts) else compact(value, opts), attrsFor(value))

  private def plainCell(text: String): Cell = Cell(text, fansi.Attrs.Empty)
  private def indexCell(text: String): Cell = Cell(text, dimAttrs)

  private def width(s: String): Int = s.codePointCount(0, s.length)

  private def truncate(s: String, max: Int): String =
    if width(s) <= max then s
    else s.substring(0, s.offsetByCodePoints(0, max - 1)) + "\u2026"

  private def grid(
    headers: Vector[String],
    rows: Vector[Vector[Cell]],
    rightAlign: Vector[Boolean],
    opts: RenderOpts
  ): String =
    val cappedHeaders = headers.map(truncate(_, opts.maxColWidth))
    val cappedRows    = rows.map(_.map(c => c.copy(text = truncate(c.text, opts.maxColWidth))))
    val widths        = headers.indices
      .map(i => (width(cappedHeaders(i)) +: cappedRows.map(r => width(r(i).text))).max)
      .toVector
    val bar                                                    = paint("\u2502", dimAttrs, opts)
    def rule(left: String, mid: String, right: String): String =
      paint(widths.map(w => "\u2500" * (w + 2)).mkString(left, mid, right), dimAttrs, opts)
    def line(cells: Vector[Cell]): String =
      cells.zipWithIndex
        .map: (c, i) =>
          val fill = " " * (widths(i) - width(c.text))
          val body = paint(c.text, c.attrs, opts)
          if rightAlign(i) then " " + fill + body + " " else " " + body + fill + " "
        .mkString(bar, bar, bar)
    val header = line(cappedHeaders.map(h => Cell(h, headerAttrs)))
    val top    = rule("\u256d", "\u252c", "\u256e")
    val middle = rule("\u251c", "\u253c", "\u2524")
    val bottom = rule("\u2570", "\u2534", "\u256f")
    (Vector(top, header, middle) ++ cappedRows.map(line) :+ bottom).mkString("", "\n", "\n")

  private def renderTable(
    columns: Vector[String],
    rows: Vector[Vector[Value]],
    opts: RenderOpts
  ): String =
    val body    = rows.zipWithIndex.map((row, i) => indexCell(i.toString) +: row.map(cellOf(_, opts)))
    val numeric = columns.indices.map(i => rows.forall(_(i).isNumeric)).toVector
    grid("#" +: columns, body, true +: numeric, opts)

  private def renderRecord(fields: Vector[(String, Value)], opts: RenderOpts): String =
    val body = fields.map((key, v) => Vector(plainCell(key), cellOf(v, opts)))
    grid(Vector("key", "value"), body, Vector(false, fields.forall(_._2.isNumeric)), opts)

  private def renderList(items: Vector[Value], opts: RenderOpts): String =
    if items.forall(_.isScalar) then
      val body = items.zipWithIndex.map((v, i) => Vector(indexCell(i.toString), cellOf(v, opts)))
      grid(Vector("#", "value"), body, Vector(true, items.forall(_.isNumeric)), opts)
    else
      uniformRecordKeys(items) match
        case Some(keys) =>
          renderTable(keys, items.collect { case Value.Record(fs) => fs.map(_._2) }, opts)
        case None =>
          items.zipWithIndex.map((v, i) => s"$i: ${compact(v, opts)}").mkString("", "\n", "\n")

  /** `Some(keys)` when every element is a `Record` carrying the same keys in the same order. */
  private def uniformRecordKeys(items: Vector[Value]): Option[Vector[String]] =
    val keys = items.collect { case Value.Record(fs) => fs.map(_._1) }
    Option.when(items.nonEmpty && keys.sizeIs == items.size && keys.distinct.sizeIs == 1)(keys.head)

  private def jsonEscape(s: String): String =
    s.flatMap:
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\t'         => "\\t"
      case '\r'         => "\\r"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case c if c < ' ' => String.format(Locale.ROOT, "\\u%04x", c.toInt)
      case c            => c.toString
