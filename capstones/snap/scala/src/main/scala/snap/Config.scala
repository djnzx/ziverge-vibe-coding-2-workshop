package snap

import io.circe.{Json, JsonObject}
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** SPEC §8 / §7.2 — strict local and global contributor configuration.
  *
  * `home` is always injected by the caller. This module never reads `HOME` from the process environment, which keeps precedence and missing-home behaviour
  * deterministic in tests and lets the CLI own process concerns.
  */
object Config:

  private val localRelativePath  = Vector(".snap", "config.json")
  private val globalRelativePath = ".snapconfig.json"

  /** Resolves local configuration first. A local ID ends resolution immediately; an empty local configuration falls through to the injected global location. */
  def resolve(root: Path, home: Option[Path]): Either[SnapError, Configuration] =
    read(localPath(root)).flatMap: local =>
      local.contributorId match
        case Some(_) => Right(local)
        case None    =>
          home match
            case Some(directory) => read(globalPath(directory))
            case None            => Right(Configuration.empty)

  /** Reads one optional configuration file. A missing file is empty configuration; a present file is parsed strictly. */
  def read(path: Path): Either[SnapError, Configuration] =
    try
      if Files.notExists(path) then Right(Configuration.empty)
      else parse(Files.readString(path, StandardCharsets.UTF_8))
    catch
      case error: IOException       => Left(filesystemError(error))
      case error: SecurityException => Left(filesystemError(error))

  /** Parses the optional `contributor` object and optional `id` string in a strict configuration JSON value. */
  def parse(text: String): Either[SnapError, Configuration] =
    for
      json          <- io.circe.parser.parse(text).left.map(failure => invalid(s"invalid JSON: ${failure.message}"))
      _             <- JsonValidation.duplicateKeys(text)
      root          <- asObject(json, "configuration")
      _             <- rejectUnknown(root, Set("contributor"), "configuration")
      configuration <- root("contributor") match
        case None              => Right(Configuration.empty)
        case Some(contributor) => parseContributor(contributor)
    yield configuration

  /** Replaces a repository's local configuration with exactly one contributor ID. */
  def writeLocal(root: Path, contributorId: ContributorId): Either[SnapError, Unit] =
    write(localPath(root), contributorId)

  /** Replaces the injected global configuration with exactly one contributor ID. */
  def writeGlobal(home: Path, contributorId: ContributorId): Either[SnapError, Unit] =
    write(globalPath(home), contributorId)

  /** The common Phase 11 command check for the two patch-authoring commands. */
  def requireContributorId(configuration: Configuration): Either[SnapError, ContributorId] =
    configuration.contributorId.toRight(SnapError("contributor.id is required; configure it locally or globally"))

  // ---- parsing ---------------------------------------------------------------

  private def parseContributor(json: Json): Either[SnapError, Configuration] =
    for
      contributor <- asObject(json, "contributor")
      _           <- rejectUnknown(contributor, Set("id"), "contributor")
      id          <- contributor("id") match
        case None        => Right(None)
        case Some(value) => asString(value, "contributor.id").flatMap(Versions.contributorId).map(Some(_))
    yield Configuration(id)

  private def asObject(json: Json, context: String): Either[SnapError, JsonObject] =
    json.asObject.toRight(invalid(s"$context must be a JSON object"))

  private def asString(json: Json, context: String): Either[SnapError, String] =
    json.asString.toRight(invalid(s"$context must be a JSON string"))

  private def rejectUnknown(objectValue: JsonObject, allowed: Set[String], context: String): Either[SnapError, Unit] =
    objectValue.keys.find(!allowed.contains(_)) match
      case Some(field) => Left(invalid(s"$context has unknown field: $field"))
      case None        => Right(())

  // ---- writing ---------------------------------------------------------------

  private def write(path: Path, contributorId: ContributorId): Either[SnapError, Unit] =
    Versions
      .contributorId(contributorId.value)
      .flatMap: validated =>
        val json = Json.obj("contributor" -> Json.obj("id" -> Json.fromString(validated.value))).noSpaces + "\n"
        try
          Files.writeString(path, json, StandardCharsets.UTF_8)
          Right(())
        catch
          case error: IOException       => Left(filesystemError(error))
          case error: SecurityException => Left(filesystemError(error))

  private def localPath(root: Path): Path =
    localRelativePath.foldLeft(root)((current, segment) => current.resolve(segment))

  private def globalPath(home: Path): Path = home.resolve(globalRelativePath)

  private def invalid(detail: String): SnapError = SnapError(detail)

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
