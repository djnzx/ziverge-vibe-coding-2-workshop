package snap

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor, StandardCopyOption}
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** SPEC §2/§10 — the filesystem boundary for one Snap repository.
  *
  * This module receives every path explicitly: it never reads the process working directory, environment, or clock. Commands must scan and compare the working
  * tree before calling [[install]], and must call [[writeRepository]] only after [[install]] succeeds. That ordering is what makes merge and revert
  * validate-before-mutate operations (§10); Phase 11's command handlers assemble it.
  */
object Workspace:

  private val controlDirectory = ".snap"
  private val repositoryFile   = "repository.json"

  /** Finds the containing repository by walking from `from` towards the filesystem root. */
  def discover(from: Path): Either[SnapError, Path] =
    try
      @tailrec
      def search(current: Path): Either[SnapError, Path] =
        if Files.isDirectory(current.resolve(controlDirectory), LinkOption.NOFOLLOW_LINKS) then Right(current)
        else
          Option(current.getParent) match
            case Some(parent) => search(parent)
            case None         => Left(SnapError("not a Snap repository"))

      search(from.toAbsolutePath.normalize)
    catch
      case error: IOException       => Left(filesystemError(error))
      case error: SecurityException => Left(filesystemError(error))

  /** Scans every tracked regular file beneath `root`.
    *
    * The control directory is skipped as a subtree. All other entries are inspected with `NOFOLLOW_LINKS`: a symlink, FIFO, socket, or device is reported
    * rather than followed or silently ignored.
    */
  def scan(root: Path): Either[SnapError, Tree] =
    val base    = root.toAbsolutePath.normalize
    val entries = ArrayBuffer.empty[(TrackedPath, FileBytes)]
    var problem = Option.empty[SnapError]

    def stop(error: SnapError): FileVisitResult =
      problem = Some(error)
      FileVisitResult.TERMINATE

    try
      Files.walkFileTree(
        base,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(directory: Path, ignored: BasicFileAttributes): FileVisitResult =
            if isControlDirectory(base, directory) then FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

          override def visitFile(file: Path, ignored: BasicFileAttributes): FileVisitResult =
            try
              val attributes = Files.readAttributes(file, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
              trackedPath(base, file) match
                case Left(error)                             => stop(error)
                case Right(path) if attributes.isRegularFile =>
                  entries += path -> FileBytes(Files.readAllBytes(file))
                  FileVisitResult.CONTINUE
                case Right(path) => stop(unsupported(path))
            catch
              case error: IOException       => stop(filesystemError(error))
              case error: SecurityException => stop(filesystemError(error))

          override def visitFileFailed(file: Path, error: IOException): FileVisitResult =
            stop(filesystemError(error))
      )
      problem match
        case Some(error) => Left(error)
        case None        => Right(SortedMap.from(entries))
    catch
      case error: IOException       => Left(filesystemError(error))
      case error: SecurityException => Left(filesystemError(error))

  /** Makes the non-control portion of `root` represent exactly `target`'s tracked path/byte map.
    *
    * A target file can replace an obsolete directory and an obsolete file can be removed to make room for a required directory. Empty directories which become
    * empty because their obsolete contents were removed are pruned; pre-existing empty directories are left alone because they are untracked.
    */
  def install(root: Path, target: Tree): Either[SnapError, Unit] =
    validateTarget(target).flatMap: _ =>
      val base = root.toAbsolutePath.normalize
      try
        target.foreach: (path, content) =>
          ensureParents(base, path)
          prepareFile(resolve(base, path))
          Files.write(resolve(base, path), content.toArray)

        pruneObsolete(base, base, target.keySet).map(_ => ())
      catch
        case error: IOException       => Left(filesystemError(error))
        case error: SecurityException => Left(filesystemError(error))

  /** Serializes the repository to a same-directory temporary file, then atomically replaces `.snap/repository.json`.
    *
    * There is intentionally no non-atomic fallback: §10 requires `ATOMIC_MOVE`. The `finally` removes the temporary file after either a failed write/move or a
    * successful replacement (where it has already been moved away).
    */
  def writeRepository(root: Path, repository: Repository): Either[SnapError, Unit] =
    val base        = root.toAbsolutePath.normalize
    val metadataDir = base.resolve(controlDirectory)
    val destination = metadataDir.resolve(repositoryFile)
    try
      val temporary = Files.createTempFile(metadataDir, ".repository-", ".tmp")
      try
        Files.write(temporary, RepositoryJson.serialize(repository).getBytes(StandardCharsets.UTF_8))
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Right(())
      finally Files.deleteIfExists(temporary)
    catch
      case error: IOException       => Left(filesystemError(error))
      case error: SecurityException => Left(filesystemError(error))

  // ---- tracked-tree installation ---------------------------------------------

  /** Although ordinary callers receive a tree from replay, [[Tree]] is an alias and can be assembled directly in a test or command. Keep the filesystem edge
    * from writing an invalid path or a tree whose file/directory shape cannot exist.
    */
  private def validateTarget(target: Tree): Either[SnapError, Unit] =
    target.keys
      .foldLeft[Either[SnapError, Unit]](Right(())): (acc, path) =>
        acc.flatMap: _ =>
          Paths
            .trackedPath(path.value)
            .flatMap: parsed =>
              if parsed == path then Right(()) else Left(SnapError(s"invalid path: ${path.value}"))
      .flatMap(_ => Paths.prefixFree(target.keySet))

  private def resolve(root: Path, path: TrackedPath): Path =
    path.value.split('/').foldLeft(root)((current, segment) => current.resolve(segment))

  private def ensureParents(root: Path, path: TrackedPath): Unit =
    path.value
      .split('/')
      .dropRight(1)
      .foldLeft(root): (current, segment) =>
        val next = current.resolve(segment)
        if Files.exists(next, LinkOption.NOFOLLOW_LINKS) then
          val attributes = Files.readAttributes(next, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if !attributes.isDirectory then remove(next, attributes)
        if !Files.exists(next, LinkOption.NOFOLLOW_LINKS) then Files.createDirectory(next)
        next

  private def prepareFile(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      if attributes.isDirectory || !attributes.isRegularFile then remove(path, attributes)

  private def remove(path: Path, attributes: BasicFileAttributes): Unit =
    if attributes.isDirectory then deleteTree(path) else Files.delete(path)

  /** Removes obsolete entries and only the directories made empty by those removals. The control directory is never traversed or removed. */
  private def pruneObsolete(root: Path, directory: Path, targetPaths: Set[TrackedPath]): Either[SnapError, Boolean] =
    val children =
      val stream = Files.newDirectoryStream(directory)
      try stream.iterator.asScala.toVector
      finally stream.close()

    children.foldLeft[Either[SnapError, Boolean]](Right(false)): (acc, child) =>
      acc.flatMap: changed =>
        if isControlDirectory(root, child) then Right(changed)
        else
          val attributes = Files.readAttributes(child, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if attributes.isDirectory then
            pruneObsolete(root, child, targetPaths).map: childChanged =>
              if childChanged && directoryIsEmpty(child) then Files.delete(child)
              changed || childChanged
          else
            trackedPath(root, child) match
              case Right(path) if targetPaths.contains(path) => Right(changed)
              case Right(_)                                  =>
                Files.delete(child)
                Right(true)
              case Left(error) => Left(error)

  private def directoryIsEmpty(directory: Path): Boolean =
    val stream = Files.newDirectoryStream(directory)
    try !stream.iterator.hasNext
    finally stream.close()

  /** Deletes a file tree without following links. It is only used for a target path that must change kind (file ↔ directory), never for the control directory.
    */
  private def deleteTree(root: Path): Unit =
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
          Files.delete(file)
          FileVisitResult.CONTINUE

        override def postVisitDirectory(directory: Path, error: IOException): FileVisitResult =
          if Option(error).nonEmpty then throw error
          Files.delete(directory)
          FileVisitResult.CONTINUE
    )

  // ---- path and error rendering ----------------------------------------------

  private def isControlDirectory(root: Path, candidate: Path): Boolean =
    val relative = root.relativize(candidate)
    relative.getNameCount == 1 && relative.getFileName.toString == controlDirectory

  private def trackedPath(root: Path, file: Path): Either[SnapError, TrackedPath] =
    val text = root.relativize(file).iterator.asScala.map(_.toString).mkString("/")
    Paths.trackedPath(text)

  private def unsupported(path: TrackedPath): SnapError =
    SnapError(s"unsupported working tree entry: ${path.value}")

  private def filesystemError(error: Exception): SnapError =
    val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
    SnapError(s"filesystem error: ${escape(detail)}")

  private def escape(text: String): String =
    text.flatMap:
      case '\\'                   => "\\\\"
      case '\t'                   => "\\t"
      case '\n'                   => "\\n"
      case char if char.isControl => f"\\u${char.toInt}%04x"
      case char                   => char.toString
