package tabbyshell

import io.circe.{Json, JsonObject, Printer}
import io.circe.parser.parse as parseJson

/** Explicit conversions between TabbyShell values and JSON (SPEC 5.2, 5.13). */
object JsonCodec:

  private val twoSpacePrinter = Printer.spaces2.copy(colonLeft = "")

  /** Parses JSON into a TabbyShell value.
    *
    * `command` lets callers such as `open` and the AI adapter retain their command context when JSON input is malformed.
    */
  def parse(input: String, command: String): Either[ShellError, Value] =
    parseJson(input).left
      .map(error => ShellError.BadArg(command, s"invalid JSON: ${error.message}"))
      .flatMap(fromJson(_, command))

  /** Renders a value as pretty JSON with a single trailing newline (SPEC 5.13). */
  def render(value: Value): String = twoSpacePrinter.print(toJson(value)) + "\n"

  private def fromJson(json: Json, command: String): Either[ShellError, Value] =
    json.fold(
      Right(Value.Null),
      value => Right(Value.Bool(value)),
      number => Right(number.toLong.fold(Value.Float(number.toDouble))(Value.Int.apply)),
      value => Right(Value.Str(value)),
      values => valuesToValue(values, command),
      fields => objectToValue(fields, command)
    )

  private def valuesToValue(values: Vector[Json], command: String): Either[ShellError, Value] =
    decodeAll(values, command).flatMap { decoded =>
      val recordFields = decoded.collect { case Value.Record(fields) => fields }
      if decoded.isEmpty || recordFields.size != decoded.size then Right(Value.List(decoded))
      else
        val columns = recordFields.head.map(_._1)
        if recordFields.forall(_.map(_._1) == columns) then Values.table(command, columns, recordFields.map(_.map(_._2)))
        else Right(Value.List(decoded))
    }

  private def objectToValue(fields: JsonObject, command: String): Either[ShellError, Value] =
    fields.toVector
      .foldRight[Either[ShellError, Vector[(String, Value)]]](Right(Vector.empty)) { case ((key, field), decoded) =>
        for
          value <- fromJson(field, command)
          rest  <- decoded
        yield (key -> value) +: rest
      }
      .map(Value.Record.apply)

  private def decodeAll(values: Vector[Json], command: String): Either[ShellError, Vector[Value]] =
    values.foldRight[Either[ShellError, Vector[Value]]](Right(Vector.empty)) { (json, decoded) =>
      for
        value <- fromJson(json, command)
        rest  <- decoded
      yield value +: rest
    }

  private def toJson(value: Value): Json = value match
    case Value.Null                 => Json.Null
    case Value.Bool(bool)           => Json.fromBoolean(bool)
    case Value.Int(number)          => Json.fromLong(number)
    case Value.Float(number)        => Json.fromDoubleOrNull(number)
    case Value.Str(text)            => Json.fromString(text)
    case Value.Filesize(bytes)      => Json.fromLong(bytes)
    case Value.Date(seconds)        => Json.fromLong(seconds)
    case Value.List(items)          => Json.fromValues(items.map(toJson))
    case Value.Record(fields)       => Json.fromFields(fields.map((key, field) => key -> toJson(field)))
    case Value.Table(columns, rows) =>
      Json.fromValues(rows.map(row => Json.fromFields(columns.zip(row).map((column, cell) => column -> toJson(cell)))))
