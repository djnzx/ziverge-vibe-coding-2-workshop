package tabbyshell

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

/** The three navigation forms accepted by `cd` (SPEC 5.5). */
enum CdTarget:
  case Home
  case Previous
  case Path(value: String)

/** Filesystem builtins. Every path is resolved from `ShellState`, never the process working directory.
  */
object Builtins:

  private val permissionBits = Vector(
    PosixFilePermission.OWNER_READ     -> 'r',
    PosixFilePermission.OWNER_WRITE    -> 'w',
    PosixFilePermission.OWNER_EXECUTE  -> 'x',
    PosixFilePermission.GROUP_READ     -> 'r',
    PosixFilePermission.GROUP_WRITE    -> 'w',
    PosixFilePermission.GROUP_EXECUTE  -> 'x',
    PosixFilePermission.OTHERS_READ    -> 'r',
    PosixFilePermission.OTHERS_WRITE   -> 'w',
    PosixFilePermission.OTHERS_EXECUTE -> 'x'
  )

  /** Commands are handed absolute paths; a leading `~/` is the only tilde form that expands (SPEC 5.5).
    */
  private def resolve(pathText: String, state: ShellState): Path =
    val input =
      if pathText.startsWith("~/") then Path.of(state.home).resolve(pathText.drop(2))
      else Path.of(pathText)
    if input.isAbsolute then input.normalize
    else Path.of(state.cwd).resolve(input).normalize

  private def osMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)

  private def io[A](command: String)(operation: => A): Either[ShellError, A] =
    try Right(operation)
    catch
      case error: IOException                   => Left(ShellError.IoError(command, osMessage(error)))
      case error: UncheckedIOException          => Left(ShellError.IoError(command, osMessage(error.getCause)))
      case error: SecurityException             => Left(ShellError.IoError(command, osMessage(error)))
      case error: UnsupportedOperationException => Left(ShellError.IoError(command, osMessage(error)))

  def pwd(state: ShellState): ExecutionResult = ExecutionResult(Value.Str(state.cwd), state)

  def cat(path: String, state: ShellState): Either[ShellError, ExecutionResult] =
    io("cat")(Files.readString(resolve(path, state))).map(text => ExecutionResult(Value.Str(text), state))

  /** Opens UTF-8 text and selects the structured decoder solely from the path extension (SPEC 5.2). Other files deliberately remain raw strings.
    */
  def open(path: String, state: ShellState): Either[ShellError, ExecutionResult] =
    io("open")(Files.readString(resolve(path, state))).flatMap: text =>
      val decoded =
        if path.endsWith(".json") then JsonCodec.parse(text, "open")
        else if path.endsWith(".csv") then CsvCodec.parse(text, "open")
        else Right(Value.Str(text))
      decoded.map(value => ExecutionResult(value, state))

  def ls(
    path: Option[String],
    includeAll: Boolean,
    long: Boolean,
    state: ShellState
  ): Either[ShellError, ExecutionResult] =
    val directory = resolve(path.getOrElse("."), state)
    listEntries(directory, includeAll).flatMap: entries =>
      val rows = entries.foldLeft[Either[ShellError, Vector[Vector[Value]]]](Right(Vector.empty)):
        case (result, entry) =>
          for
            built <- result
            row   <- lsRow(entry, long)
          yield built :+ row
      rows.flatMap: values =>
        val columns = Vector("name", "type", "size", "modified") ++ (if long then Vector("mode", "uid") else Vector.empty)
        Values.table("ls", columns, values).map(value => ExecutionResult(value, state))

  def cd(target: CdTarget, state: ShellState): Either[ShellError, ExecutionResult] =
    target match
      case CdTarget.Home     => changeDirectory(Path.of(state.home), state.home, state)
      case CdTarget.Previous =>
        state.prevCwd
          .toRight(ShellError.BadArg("cd", "no previous directory"))
          .flatMap(previous => changeDirectory(Path.of(previous), previous, state))
      case CdTarget.Path(value) => changeDirectory(resolve(value, state), value, state)

  /** Filters a table using the typed comparison rules in SPEC 5.6. */
  def where(
    input: Value,
    column: String,
    operator: String,
    literal: Literal
  ): Either[ShellError, Value] =
    for
      table <- requireTable("where", input)
      index <- columnIndex("where", table.columns, column)
      rows  <- table.rows.foldRight[Either[ShellError, Vector[Vector[Value]]]](Right(Vector.empty)) { (row, kept) =>
        for
          rest <- kept
          cell <- cellAt("where", row, index, column)
          keep <- matchesWhere(cell, operator, literal.toValue)
        yield if keep then row +: rest else rest
      }
      result <- Values.table("where", table.columns, rows)
    yield result

  /** Projects table columns, retaining the requested order (SPEC 5.7). */
  def select(input: Value, columns: Vector[String]): Either[ShellError, Value] =
    for
      table   <- requireTable("select", input)
      indexes <- requestedIndexes("select", table.columns, columns)
      rows    <- table.rows.foldRight[Either[ShellError, Vector[Vector[Value]]]](Right(Vector.empty)) { (row, selected) =>
        for
          rest  <- selected
          cells <- columns.zip(indexes).foldRight[Either[ShellError, Vector[Value]]](Right(Vector.empty)) { case ((column, index), projected) =>
            for
              tail <- projected
              cell <- cellAt("select", row, index, column)
            yield cell +: tail
          }
        yield cells +: rest
      }
      result <- Values.table("select", columns, rows)
    yield result

  /** Stably orders a table by one uniformly comparable column (SPEC 5.8). */
  def sortBy(input: Value, column: String, reverse: Boolean): Either[ShellError, Value] =
    for
      table <- requireTable("sort-by", input)
      index <- columnIndex("sort-by", table.columns, column)
      keyed <- table.rows.zipWithIndex.foldRight[Either[ShellError, Vector[(Vector[Value], Value, Int)]]](Right(Vector.empty)) {
        case ((row, originalIndex), collected) =>
          for
            rest <- collected
            cell <- cellAt("sort-by", row, index, column)
          yield (row, cell, originalIndex) +: rest
      }
      sorted <- stableSort(keyed, reverse)
      result <- Values.table("sort-by", table.columns, sorted.map(_._1))
    yield result

  /** Returns the first element, or the first `count` elements while preserving collection shape (SPEC 5.9). */
  def first(input: Value, count: Option[Long]): Either[ShellError, Value] =
    takeEnd("first", input, count, fromEnd = false)

  /** Returns the last element, or the last `count` elements while preserving collection shape (SPEC 5.10). */
  def last(input: Value, count: Option[Long]): Either[ShellError, Value] =
    takeEnd("last", input, count, fromEnd = true)

  /** Counts collection members, Unicode code points, or null (SPEC 5.11). */
  def length(input: Value): Either[ShellError, Value] = input match
    case Value.Table(_, rows) => Right(Value.Int(rows.size.toLong))
    case Value.List(items)    => Right(Value.Int(items.size.toLong))
    case Value.Str(text)      => Right(Value.Int(text.codePointCount(0, text.length).toLong))
    case Value.Null           => Right(Value.Int(0L))
    case other                => Left(ShellError.TypeMismatch("length", "table, list, string, or null", other.typeName))

  /** Extracts a table column as a list or a record field as a scalar (SPEC 5.12). */
  def get(input: Value, column: String): Either[ShellError, Value] = input match
    case Value.Table(columns, rows) =>
      for
        index  <- columnIndex("get", columns, column)
        values <- rows.foldRight[Either[ShellError, Vector[Value]]](Right(Vector.empty)) { (row, collected) =>
          for
            rest <- collected
            cell <- cellAt("get", row, index, column)
          yield cell +: rest
        }
      yield Value.List(values)
    case Value.Record(fields) =>
      fields.find(_._1 == column).map(_._2).toRight(ShellError.MissingColumn("get", column))
    case other => Left(ShellError.TypeMismatch("get", "table or record", other.typeName))

  /** Serializes every value as JSON or a table as RFC 4180 CSV (SPEC 5.13). */
  def to(input: Value, format: String): Either[ShellError, Value] = format match
    case "json" => Right(Value.Str(JsonCodec.render(input)))
    case "csv"  =>
      input match
        case table @ Value.Table(_, _) => Right(Value.Str(CsvCodec.render(table)))
        case other                     => Left(ShellError.TypeMismatch("to", "table", other.typeName))
    case other => Left(ShellError.BadArg("to", s"unsupported format: $other"))

  /** Saves according to extension precedence, always retaining the current shell state (SPEC 5.14). */
  def save(input: Value, path: String, state: ShellState): Either[ShellError, ExecutionResult] =
    val contents =
      if path.endsWith(".json") then JsonCodec.render(input)
      else
        input match
          case table @ Value.Table(_, _) if path.endsWith(".csv") => CsvCodec.render(table)
          case Value.Str(text)                                    => text
          case other                                              => Renderer.render(other, RenderOpts(color = false, now = state.now))
    io("save")(Files.writeString(resolve(path, state), contents)).map(_ => ExecutionResult(Value.Null, state))

  private def listEntries(directory: Path, includeAll: Boolean): Either[ShellError, Vector[Path]] =
    io("ls"):
      val stream = Files.list(directory)
      try
        stream.iterator.asScala.toVector
          .filter(path => includeAll || !path.getFileName.toString.startsWith("."))
          .sortBy(_.getFileName.toString)
      finally stream.close()

  private def lsRow(path: Path, long: Boolean): Either[ShellError, Vector[Value]] =
    io("ls")(Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)).flatMap: attributes =>
      val kind =
        if Files.isSymbolicLink(path) then "symlink"
        else if attributes.isDirectory then "dir"
        else "file"
      val size = if attributes.isDirectory then 0L else attributes.size
      val base = Vector(
        Value.Str(path.getFileName.toString),
        Value.Str(kind),
        Value.Filesize(size),
        Value.Date(attributes.lastModifiedTime.toMillis / 1000L)
      )
      if long then posixMetadata(path).map(base ++ _)
      else Right(base)

  private def posixMetadata(path: Path): Either[ShellError, Vector[Value]] =
    io("ls"):
      val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
      permissionBits
        .map: (permission, present) =>
          if permissions.contains(permission) then present else '-'
        .mkString
    .flatMap: mode =>
      io("ls")(Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).flatMap:
        case uid: java.lang.Number => Right(Vector(Value.Str(mode), Value.Int(uid.longValue)))
        case other                 => Left(ShellError.IoError("ls", s"unexpected uid metadata: $other"))

  private def changeDirectory(
    target: Path,
    argument: String,
    state: ShellState
  ): Either[ShellError, ExecutionResult] =
    io("cd")(Files.readAttributes(target, classOf[BasicFileAttributes])).flatMap: attributes =>
      if attributes.isDirectory then
        val absolute = target.toAbsolutePath.normalize.toString
        Right(ExecutionResult(Value.Null, state.moveTo(absolute)))
      else Left(ShellError.BadArg("cd", s"not a directory: $argument"))

  private def requireTable(command: String, input: Value): Either[ShellError, Value.Table] = input match
    case table @ Value.Table(_, _) => Right(table)
    case other                     => Left(ShellError.TypeMismatch(command, "table", other.typeName))

  private def columnIndex(command: String, columns: Vector[String], column: String): Either[ShellError, Int] =
    columns.indexOf(column) match
      case -1    => Left(ShellError.MissingColumn(command, column))
      case index => Right(index)

  private def requestedIndexes(
    command: String,
    available: Vector[String],
    requested: Vector[String]
  ): Either[ShellError, Vector[Int]] =
    if requested.isEmpty then Left(ShellError.MissingArg(command, "column"))
    else
      requested.foldRight[Either[ShellError, Vector[Int]]](Right(Vector.empty)) { (column, indexes) =>
        for
          rest  <- indexes
          index <- columnIndex(command, available, column)
        yield index +: rest
      }

  private def cellAt(
    command: String,
    row: Vector[Value],
    index: Int,
    column: String
  ): Either[ShellError, Value] =
    row.lift(index).toRight(ShellError.MissingColumn(command, column))

  private def matchesWhere(cell: Value, operator: String, literal: Value): Either[ShellError, Boolean] =
    val equalityOnly = cell match
      case Value.Bool(_) | Value.Null => true
      case _                          => false
    if equalityOnly && operator != "==" && operator != "!=" then Left(ShellError.TypeMismatch("where", "equality-comparable value", cell.typeName))
    else
      compare("where", cell, literal).flatMap: relation =>
        operator match
          case "=="  => Right(relation == 0)
          case "!="  => Right(relation != 0)
          case "<"   => Right(relation < 0)
          case "<="  => Right(relation <= 0)
          case ">"   => Right(relation > 0)
          case ">="  => Right(relation >= 0)
          case other => Left(ShellError.BadArg("where", s"unsupported operator: $other"))

  private def stableSort(
    keyed: Vector[(Vector[Value], Value, Int)],
    reverse: Boolean
  ): Either[ShellError, Vector[(Vector[Value], Value, Int)]] =
    keyed.headOption match
      case None                => Right(Vector.empty)
      case Some((_, first, _)) =>
        keyed
          .foldLeft[Either[ShellError, Unit]](Right(())) { case (checked, (_, value, _)) =>
            checked.flatMap(_ => compare("sort-by", first, value).map(_ => ()))
          }
          .map: _ =>
            keyed.sortWith: (left, right) =>
              val compared = compareUnsafe("sort-by", left._2, right._2)
              if compared == 0 then left._3 < right._3
              else if reverse then compared > 0
              else compared < 0

  /** Returns a conventional ordering value for values whose comparison is defined by SPEC 5.6/5.8. */
  private def compare(command: String, left: Value, right: Value): Either[ShellError, Int] =
    (left, right) match
      case (numericLeft, numericRight) if numericLeft.isNumeric && numericRight.isNumeric => Right(compareNumeric(numericLeft, numericRight))
      case (Value.Str(a), Value.Str(b))                                                   => Right(a.compareTo(b))
      case (Value.Date(a), Value.Date(b))                                                 => Right(java.lang.Long.compare(a, b))
      case (Value.Bool(a), Value.Bool(b))                                                 => Right(java.lang.Boolean.compare(a, b))
      case (Value.Null, Value.Null)                                                       => Right(0)
      case (Value.List(_), _) | (Value.Record(_), _) | (Value.Table(_, _), _)             =>
        Left(ShellError.TypeMismatch(command, "numeric, string, date, bool, or null", left.typeName))
      case _ => Left(ShellError.TypeMismatch(command, left.typeName, right.typeName))

  private def compareNumeric(left: Value, right: Value): Int =
    (numericDecimal(left), numericDecimal(right)) match
      case (Some(a), Some(b)) => a.compare(b)
      case _                  => java.lang.Double.compare(numericDouble(left), numericDouble(right))

  private def numericDecimal(value: Value): Option[BigDecimal] = value match
    case Value.Int(number)                                        => Some(BigDecimal(number))
    case Value.Filesize(bytes)                                    => Some(BigDecimal(bytes))
    case Value.Float(number) if java.lang.Double.isFinite(number) => Some(BigDecimal(number))
    case _                                                        => None

  private def numericDouble(value: Value): Double = value match
    case Value.Int(number)     => number.toDouble
    case Value.Filesize(bytes) => bytes.toDouble
    case Value.Float(number)   => number
    case _                     => 0.0

  /** `stableSort` validates compatibility first, so this cannot throw while sorting. */
  private def compareUnsafe(command: String, left: Value, right: Value): Int =
    compare(command, left, right).fold(_ => 0, identity)

  private def takeEnd(
    command: String,
    input: Value,
    count: Option[Long],
    fromEnd: Boolean
  ): Either[ShellError, Value] =
    def countFor(size: Int, requested: Long): Either[ShellError, Int] =
      if requested < 0 then Left(ShellError.BadArg(command, "count must be non-negative"))
      else Right(math.min(requested, size.toLong).toInt)

    def slice[A](items: Vector[A], requested: Long): Either[ShellError, Vector[A]] =
      countFor(items.size, requested).map: size =>
        if fromEnd then items.takeRight(size) else items.take(size)

    input match
      case Value.Table(columns, rows) =>
        count match
          case Some(requested) => slice(rows, requested).flatMap(Values.table(command, columns, _))
          case None            =>
            val chosen = if fromEnd then rows.lastOption else rows.headOption
            chosen.map(row => Value.Record(columns.zip(row))).toRight(ShellError.BadArg(command, "input is empty"))
      case Value.List(items) =>
        count match
          case Some(requested) => slice(items, requested).map(Value.List.apply)
          case None            =>
            val chosen = if fromEnd then items.lastOption else items.headOption
            chosen.toRight(ShellError.BadArg(command, "input is empty"))
      case other => Left(ShellError.TypeMismatch(command, "table or list", other.typeName))
