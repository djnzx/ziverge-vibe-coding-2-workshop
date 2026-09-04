package snap

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.immutable.SortedSet

/** Explicit process inputs shared by the Phase 11 command handlers. */
final case class CommandContext(
  cwd: Path,
  home: Option[Path],
  stdoutPresentation: Presentation,
  stderrPresentation: Presentation
)

/** Bytes ready for the two process streams. Commands never write directly. */
final case class CommandOutput(stdout: String = "", stderr: String = "")

/** SPEC §7 command semantics.
  *
  * This is the impure command layer: every filesystem location comes from [[CommandContext]], while replay, validation, diffing, and presentation remain
  * independently testable pure modules.
  */
object Commands:

  private given Ordering[Patch] =
    Ordering.by[Patch, ContributorId](_.author).orElseBy(_.revision)

  def init(context: CommandContext, target: Option[String]): Either[SnapError, CommandOutput] =
    val directory = target.fold(context.cwd)(context.cwd.resolve).toAbsolutePath.normalize
    Workspace.discover(directory) match
      case Right(existing) if existing == directory => Left(SnapError("repository already exists"))
      case Right(_)                                 => Left(SnapError("cannot initialize inside repository"))
      case Left(SnapError("not a Snap repository")) =>
        try
          Files.createDirectories(directory)
          Files.createDirectory(directory.resolve(".snap"))
          Workspace
            .writeRepository(directory, Repository.empty)
            .map(_ => CommandOutput(stdout = Presentation.success(context.stdoutPresentation, SuccessLabel.Initialized, Version.empty)))
        catch
          case error: IOException       => Left(filesystemError(error))
          case error: SecurityException => Left(filesystemError(error))
      case Left(error) => Left(error)

  def config(context: CommandContext, id: ContributorId, global: Boolean): Either[SnapError, CommandOutput] =
    val write =
      if global then context.home.toRight(SnapError("HOME is not set")).flatMap(Config.writeGlobal(_, id))
      else inRepository(context)(root => Config.writeLocal(root, id))
    write.map(_ => CommandOutput())

  def status(context: CommandContext): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (root, repository) =>
      for
        current <- materialize(repository)
        working <- Workspace.scan(root)
      yield
        val paths   = SortedSet.from(current.keySet ++ working.keySet)
        val entries = paths.iterator.flatMap: path =>
          (current.get(path), working.get(path)) match
            case (None, Some(_))                            => Some(StatusEntry(StatusCode.Added, path))
            case (Some(_), None)                            => Some(StatusEntry(StatusCode.Deleted, path))
            case (Some(left), Some(right)) if left != right => Some(StatusEntry(StatusCode.Modified, path))
            case _                                          => None
        CommandOutput(stdout = Presentation.status(context.stdoutPresentation, repository.frontier, entries.toVector))

  def log(context: CommandContext): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (_, repository) =>
      Replay
        .integrationOrder(repository.patches, repository.frontier)
        .map: order =>
          CommandOutput(stdout = Presentation.log(context.stdoutPresentation, order.reverse))

  def commit(context: CommandContext, message: String): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (root, repository) =>
      for
        id      <- Config.resolve(root, context.home).flatMap(Config.requireContributorId)
        current <- materialize(repository)
        working <- Workspace.scan(root)
        _       <- validateCommitMessage(message)
        _       <- if current == working then Left(SnapError("working tree is clean")) else Right(())
        updated <- author(repository, id, message, changes(current, working))
        _       <- Workspace.writeRepository(root, updated)
      yield CommandOutput(stdout = Presentation.success(context.stdoutPresentation, SuccessLabel.Committed, updated.frontier))

  def diffWorkingTree(context: CommandContext): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (root, repository) =>
      for
        current <- materialize(repository)
        working <- Workspace.scan(root)
      yield CommandOutput(stdout = Presentation.diff(context.stdoutPresentation, DiffRender.render(current, working)))

  def diffVersions(
    context: CommandContext,
    oldArgument: String,
    newArgument: String,
    remote: Option[String]
  ): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (_, local) =>
      for
        oldVersion <- Versions.parse(oldArgument)
        _          <- known(local, oldVersion)
        source     <- remote.fold[Either[SnapError, Repository]](Right(local))(loadRemote(context, _))
        newVersion <- Versions.parse(newArgument)
        _          <- known(source, newVersion)
        _          <- Validation.dotCollision(local.patches, source.patches)
        before     <- materialize(local, oldVersion)
        after      <- materialize(source, newVersion)
      yield CommandOutput(stdout = Presentation.diff(context.stdoutPresentation, DiffRender.render(before, after)))

  def revert(context: CommandContext, targetArgument: String): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (root, repository) =>
      for
        current       <- materialize(repository)
        targetVersion <- Versions.parse(targetArgument)
        _             <- known(repository, targetVersion)
        target        <- materialize(repository, targetVersion)
        id            <- Config.resolve(root, context.home).flatMap(Config.requireContributorId)
        working       <- Workspace.scan(root)
        _             <- if current == working then Right(()) else Left(SnapError("working tree is dirty"))
        _             <- if current != target then Right(()) else Left(SnapError("target tree is already current"))
        updated       <- author(repository, id, s"revert to ${Versions.render(targetVersion)}", changes(current, target))
        _             <- Workspace.install(root, target)
        _             <- Workspace.writeRepository(root, updated)
      yield CommandOutput(stdout = Presentation.success(context.stdoutPresentation, SuccessLabel.Reverted, updated.frontier))

  def merge(context: CommandContext, location: String): Either[SnapError, CommandOutput] =
    inLoadedRepository(context): (root, local) =>
      for
        localReplay <- Replay.materialize(local.patches, local.frontier)
        remote      <- loadRemote(context, location)
        _           <- Validation.dotCollision(local.patches, remote.patches)
        joined  = Versions.join(local.frontier, remote.frontier)
        patches = canonicalPatches(local.patches ++ remote.patches)
        mergedReplay <- Replay.materialize(patches, joined)
        working      <- Workspace.scan(root)
        _            <- if working == localReplay._1 then Right(()) else Left(SnapError("working tree is dirty"))
        repository = Repository(Repository.format, joined, patches)
        _ <- Workspace.install(root, mergedReplay._1)
        _ <- Workspace.writeRepository(root, repository)
      yield
        val newWarnings = mergedReplay._2 -- localReplay._2
        val warnings    = newWarnings.iterator.map: warning =>
          Presentation.warning(context.stderrPresentation, s"auto-resolved ${warning.path.value}: ${warning.reason.token}")
        CommandOutput(
          stdout = Presentation.success(context.stdoutPresentation, SuccessLabel.Merged, joined),
          stderr = warnings.mkString
        )

  def serve(context: CommandContext, port: Int, announce: String => Unit): Either[SnapError, Unit] =
    inLoadedRepository(context): (_, repository) =>
      Http.serve(repository, port, announce)

  private def inRepository[A](context: CommandContext)(run: Path => Either[SnapError, A]): Either[SnapError, A] =
    Workspace.discover(context.cwd).flatMap(run)

  private def inLoadedRepository[A](context: CommandContext)(run: (Path, Repository) => Either[SnapError, A]): Either[SnapError, A] =
    inRepository(context): root =>
      loadRepository(root).flatMap(repository => run(root, repository))

  private def loadRemote(context: CommandContext, location: String): Either[SnapError, Repository] =
    if location.startsWith("http://") || location.startsWith("https://") then Http.fetchRepository(location)
    else
      val path = context.cwd.resolve(location).toAbsolutePath.normalize
      Workspace.discover(path).flatMap(loadRepository)

  private def loadRepository(root: Path): Either[SnapError, Repository] =
    try
      RepositoryJson
        .parse(Files.readString(root.resolve(".snap").resolve("repository.json"), StandardCharsets.UTF_8))
        .flatMap(repository => Validation.validate(repository).map(_ => repository))
    catch
      case error: IOException       => Left(filesystemError(error))
      case error: SecurityException => Left(filesystemError(error))

  private def materialize(repository: Repository): Either[SnapError, Tree] =
    materialize(repository, repository.frontier)

  private def materialize(repository: Repository, version: Version): Either[SnapError, Tree] =
    Replay.materialize(repository.patches, version).map(_._1)

  private def known(repository: Repository, version: Version): Either[SnapError, Unit] =
    if Validation.known(repository, version) then Right(())
    else Left(SnapError(s"unknown version: ${Versions.render(version)}"))

  private def changes(before: Tree, after: Tree): Vector[Change] =
    SortedSet
      .from(before.keySet ++ after.keySet)
      .iterator
      .flatMap: path =>
        (before.get(path), after.get(path)) match
          case (Some(left), Some(right)) if left == right                           => None
          case (Some(left), Some(right)) if Text.isText(left) && Text.isText(right) =>
            Some(Change.Text(path, Diff.diff(Text.tokens(left), Text.tokens(right))))
          case (None, Some(content)) if Text.isText(content) =>
            Some(Change.Text(path, Diff.diff(Vector.empty, Text.tokens(content))))
          case (_, Some(content)) => Some(Change.Put(path, content))
          case (Some(_), None)    => Some(Change.Delete(path))
          case (None, None)       => None
      .toVector

  private def author(
    repository: Repository,
    id: ContributorId,
    message: String,
    patchChanges: Vector[Change]
  ): Either[SnapError, Repository] =
    if patchChanges.isEmpty then Left(SnapError("working tree is clean"))
    else if repository.frontier(id) >= Limits.maxSafeInteger then Left(SnapError("revision exceeds positive safe integer"))
    else
      val patch   = Patch(id, repository.frontier(id) + 1, repository.frontier, message, patchChanges)
      val updated = Repository(repository.format, patch.result, canonicalPatches(repository.patches :+ patch))
      Validation.validate(updated).map(_ => updated)

  private def canonicalPatches(patches: Vector[Patch]): Vector[Patch] =
    patches.distinctBy(_.dot).sorted

  private def validateCommitMessage(message: String): Either[SnapError, Unit] =
    val validControls = message.forall(char => !char.isControl || char == '\t' || char == '\n')
    if message.nonEmpty && message.getBytes(StandardCharsets.UTF_8).length <= Limits.maxCommitMessageBytes && validControls then Right(())
    else Left(SnapError("invalid commit message"))

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
