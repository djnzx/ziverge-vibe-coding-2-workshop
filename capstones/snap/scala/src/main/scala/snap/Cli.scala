package snap

import java.nio.file.Path
import scala.util.control.NonFatal

/** SPEC §7 — the argument grammar and command dispatch.
  *
  * The grammar is positional and strict: each option appears at most once, only in the position shown in §7, and unknown options, extra operands, and missing
  * option values are errors. Parse it here by hand rather than with a general option library, whose relaxed placement rules would accept input §7 forbids.
  *
  * Scaffold: this dispatches nothing yet. Replace the body as the commands in `Commands.scala` come online, keeping the exit statuses from SPEC §10 — 0 for
  * success, 1 for an expected error, 2 for an unexpected internal failure.
  */
object Cli:
  def run(args: Vector[String], streams: Streams): Int =
    run(
      args,
      streams,
      Path.of(System.getProperty("user.dir")),
      sys.env.get("HOME").map(value => Path.of(value)),
      sys.env.get("SNAP_COLOR"),
      sys.env.get("NO_COLOR")
    )

  /** Injectable process edge for command and presentation tests. */
  def run(
    args: Vector[String],
    streams: Streams,
    cwd: Path,
    home: Option[Path],
    snapColor: Option[String],
    noColor: Option[String]
  ): Int =
    val outputPresentation = Presentation.select(snapColor, noColor, streams.stdoutIsTty)
    val errorPresentation  = Presentation.select(snapColor, noColor, streams.stderrIsTty)

    (outputPresentation, errorPresentation) match
      case (Left(error), _) =>
        streams.err.print(Presentation.error(Presentation.Plain, error))
        1
      case (_, Left(error)) =>
        streams.err.print(Presentation.error(Presentation.Plain, error))
        1
      case (Right(stdoutPresentation), Right(stderrPresentation)) =>
        val context = CommandContext(cwd, home, stdoutPresentation, stderrPresentation)
        try
          dispatch(args, context) match
            case Right(output) =>
              streams.out.print(output.stdout)
              streams.err.print(output.stderr)
              0
            case Left(error) =>
              streams.err.print(Presentation.error(stderrPresentation, error))
              1
        catch
          case NonFatal(_) =>
            streams.err.print(Presentation.error(stderrPresentation, SnapError("internal error")))
            2

  private def dispatch(args: Vector[String], context: CommandContext): Either[SnapError, CommandOutput] = args match
    case Vector("--version") => Right(CommandOutput(stdout = Presentation.version(context.stdoutPresentation)))

    case Vector("init")                                     => Commands.init(context, None)
    case Vector("init", target) if !target.startsWith("--") => Commands.init(context, Some(target))

    case Vector("config", "contributor.id", rawId) =>
      Versions.contributorId(rawId).flatMap(Commands.config(context, _, global = false))
    case Vector("config", "--global", "contributor.id", rawId) =>
      Versions.contributorId(rawId).flatMap(Commands.config(context, _, global = true))

    case Vector("status")          => Commands.status(context)
    case Vector("log")             => Commands.log(context)
    case Vector("commit", message) => Commands.commit(context, message)

    case Vector("diff")                                             => Commands.diffWorkingTree(context)
    case Vector("diff", oldVersion, newVersion)                     => Commands.diffVersions(context, oldVersion, newVersion, None)
    case Vector("diff", oldVersion, newVersion, "--repo", location) =>
      Commands.diffVersions(context, oldVersion, newVersion, Some(location))
    case Vector("diff", _*) => Left(diffUsage)

    case Vector("revert", version) => Commands.revert(context, version)
    case Vector("merge", location) => Commands.merge(context, location)

    // The server is Phase 12. Retaining its strict Phase 11 grammar prevents
    // its eventual implementation from changing argument behaviour.
    case Vector("--serve")       => Left(SnapError("HTTP server is not implemented"))
    case Vector("--serve", port) =>
      validatePort(port).flatMap(_ => Left(SnapError("HTTP server is not implemented")))

    case _ => Left(SnapError("invalid command or arguments"))

  private val diffUsage: SnapError =
    SnapError("usage: snap diff [<old-version> <new-version> [--repo <repository>]]")

  private def validatePort(port: String): Either[SnapError, Unit] =
    if port.nonEmpty && port.forall(_.isDigit) && BigInt(port) <= BigInt(65535) then Right(())
    else Left(SnapError(s"invalid port: $port"))
