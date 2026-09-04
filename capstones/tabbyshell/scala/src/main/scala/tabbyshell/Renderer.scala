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

  // --- grid layout (SPEC 6.1, 6.2) ---

  private def cellText(value: Value, opts: RenderOpts): String =
    if value.isScalar then scalar(value, opts) else compact(value, opts)

  private def width(s: String): Int = s.codePointCount(0, s.length)

  private def truncate(s: String, max: Int): String =
    if width(s) <= max then s
    else s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…"

  private def pad(s: String, cellWidth: Int, rightAlign: Boolean): String =
    val fill = " " * (cellWidth - width(s))
    if rightAlign then fill + s else s + fill

  private def grid(
    headers: Vector[String],
    rows: Vector[Vector[String]],
    rightAlign: Vector[Boolean],
    opts: RenderOpts
  ): String =
    val capped                                                 = (headers +: rows).map(_.map(truncate(_, opts.maxColWidth)))
    val widths                                                 = headers.indices.map(i => capped.map(row => width(row(i))).max).toVector
    def rule(left: String, mid: String, right: String): String =
      widths.map(w => "─" * (w + 2)).mkString(left, mid, right)
    def line(cells: Vector[String]): String =
      cells.zipWithIndex
        .map((c, i) => " " + pad(c, widths(i), rightAlign(i)) + " ")
        .mkString("│", "│", "│")
    val top    = rule("╭", "┬", "╮")
    val middle = rule("├", "┼", "┤")
    val bottom = rule("╰", "┴", "╯")
    (Vector(top, line(capped.head), middle) ++ capped.tail.map(line) :+ bottom)
      .mkString("", "\n", "\n")

  private def renderTable(
    columns: Vector[String],
    rows: Vector[Vector[Value]],
    opts: RenderOpts
  ): String =
    val body    = rows.zipWithIndex.map((row, i) => i.toString +: row.map(cellText(_, opts)))
    val numeric = columns.indices.map(i => rows.forall(_(i).isNumeric)).toVector
    grid("#" +: columns, body, true +: numeric, opts)

  private def renderRecord(fields: Vector[(String, Value)], opts: RenderOpts): String =
    val body = fields.map((key, v) => Vector(key, cellText(v, opts)))
    grid(Vector("key", "value"), body, Vector(false, fields.forall(_._2.isNumeric)), opts)

  private def renderList(items: Vector[Value], opts: RenderOpts): String =
    if items.forall(_.isScalar) then
      val body = items.zipWithIndex.map((v, i) => Vector(i.toString, cellText(v, opts)))
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
