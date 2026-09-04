package tabbyshell

import kantan.csv.*
import kantan.csv.ops.*

/** RFC 4180 CSV parsing and rendering for the file-facing commands (SPEC 5.2, 5.13).
  *
  * Kantan owns CSV grammar, escaping, and quoted-newline handling. This module owns the TabbyShell boundary: string-valued cells, rectangular tables, stable LF
  * output, and user-facing `ShellError` values.
  */
object CsvCodec:

  /** Parses a header row and string-valued data rows into a rectangular table. */
  def parse(input: String, command: String): Either[ShellError, Value] =
    decodeRows(input, command).flatMap: records =>
      if records.isEmpty then Left(malformed(command, "missing header row"))
      else Values.table(command, records.head, records.tail.map(_.map(Value.Str.apply)))

  /** Renders a table as RFC 4180 CSV with TabbyShell LF record terminators. */
  def render(table: Value.Table): String =
    val Value.Table(columns, rows) = table
    val stringRows                 = rows.map(_.map(cellText).toList)
    normalizeRecordTerminators(stringRows.asCsv(rfc.withHeader(columns*)))

  private def decodeRows(input: String, command: String): Either[ShellError, Vector[Vector[String]]] =
    input
      .asCsvReader[List[String]](rfc)
      .foldLeft[Either[ShellError, Vector[Vector[String]]]](Right(Vector.empty)): (decoded, row) =>
        for
          rows  <- decoded
          cells <- row.left.map(error => malformed(command, error.toString))
        yield rows :+ cells.toVector

  /** Kantan deliberately writes RFC CRLF record delimiters. TabbyShell instead standardizes only those delimiters to LF; CRLF occurring inside a quoted field
    * is data and remains untouched.
    */
  private def normalizeRecordTerminators(csv: String): String =
    val normalized = new StringBuilder
    var offset     = 0
    var quoted     = false

    while offset < csv.length do
      csv.charAt(offset) match
        case '"' if quoted && offset + 1 < csv.length && csv.charAt(offset + 1) == '"' =>
          normalized.append("\"\"")
          offset += 2
        case '"' =>
          quoted = !quoted
          normalized.append('"')
          offset += 1
        case '\r' if !quoted && offset + 1 < csv.length && csv.charAt(offset + 1) == '\n' =>
          normalized.append('\n')
          offset += 2
        case character =>
          normalized.append(character)
          offset += 1

    normalized.result()

  private def cellText(value: Value): String = value match
    case Value.Null                                          => ""
    case Value.Bool(boolean)                                 => boolean.toString
    case Value.Int(number)                                   => number.toString
    case Value.Float(number)                                 => number.toString
    case Value.Str(text)                                     => text
    case Value.Filesize(bytes)                               => bytes.toString
    case Value.Date(seconds)                                 => seconds.toString
    case Value.List(_) | Value.Record(_) | Value.Table(_, _) => Renderer.compact(value, RenderOpts(color = false, now = 0L))

  private def malformed(command: String, detail: String): ShellError =
    ShellError.BadArg(command, s"malformed CSV: $detail")
