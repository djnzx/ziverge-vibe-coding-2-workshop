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
