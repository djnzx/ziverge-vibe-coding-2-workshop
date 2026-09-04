package tabbyshell

/** Smart constructors for the `Value` ADT (SPEC 2). */
object Values:

  /** A `Table` must be rectangular; a ragged input is a `TypeMismatch` (SPEC 2). */
  def table(
    command: String,
    columns: Vector[String],
    rows: Vector[Vector[Value]]
  ): Either[ShellError, Value] =
    if rows.forall(_.sizeIs == columns.size) then Right(Value.Table(columns, rows))
    else Left(ShellError.TypeMismatch(command, "table", "ragged rows"))

extension (value: Value)

  /** The vocabulary used in `TypeMismatch` messages (SPEC 3.3). */
  def typeName: String = value match
    case Value.Null        => "null"
    case Value.Bool(_)     => "bool"
    case Value.Int(_)      => "int"
    case Value.Float(_)    => "float"
    case Value.Str(_)      => "string"
    case Value.Filesize(_) => "filesize"
    case Value.Date(_)     => "date"
    case Value.List(_)     => "list"
    case Value.Record(_)   => "record"
    case Value.Table(_, _) => "table"

  def isScalar: Boolean = value match
    case Value.List(_) | Value.Record(_) | Value.Table(_, _) => false
    case _                                                   => true

  /** Numeric cells right-align in the renderer (SPEC 6.2). */
  def isNumeric: Boolean = value match
    case Value.Int(_) | Value.Float(_) | Value.Filesize(_) => true
    case _                                                 => false
