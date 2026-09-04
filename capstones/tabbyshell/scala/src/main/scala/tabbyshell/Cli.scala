package tabbyshell

import cats.syntax.all.*
import com.monovore.decline.Command
import com.monovore.decline.Opts

/** The supported application modes from SPEC 8. */
enum CliMode:
  case Interactive
  case Eval(pipeline: String)
  case EvalFile(path: String)
  case Version

final case class CliOptions(mode: CliMode, noColor: Boolean)

/** Strict, side-effect-free Decline parsing.
  *
  * `Command(..., helpFlag = false, ...)` intentionally does not install Decline's conventional help flag: TabbyShell v1 exposes only the five options named in
  * SPEC 8.
  */
object Cli:

  private final case class RawOptions(
    eval: Option[String],
    evalFile: Option[String],
    noColor: Boolean,
    interactive: Boolean,
    version: Boolean
  )

  private val rawOptions: Opts[RawOptions] =
    (
      Opts.option[String]("eval", help = "Evaluate one pipeline").orNone,
      Opts.option[String]("eval-file", help = "Evaluate pipelines from a file").orNone,
      Opts.flag("no-color", help = "Disable color output").orFalse,
      Opts.flag("interactive", help = "Force the interactive REPL").orFalse,
      Opts.flag("version", help = "Print the TabbyShell version").orFalse
    ).mapN(RawOptions.apply)

  private val command = Command("tabbyshell", "", helpFlag = false)(rawOptions)

  def parse(args: Seq[String]): Either[String, CliOptions] =
    command.parse(args, Map.empty) match
      case Left(help)   => Left(help.toString)
      case Right(value) => validate(value)

  private def validate(raw: RawOptions): Either[String, CliOptions] =
    if raw.version then Right(CliOptions(CliMode.Version, raw.noColor))
    else
      val requested = Vector(
        raw.eval.map(CliMode.Eval.apply),
        raw.evalFile.map(CliMode.EvalFile.apply),
        Option.when(raw.interactive)(CliMode.Interactive)
      ).flatten
      requested match
        case Vector()     => Right(CliOptions(CliMode.Interactive, raw.noColor))
        case Vector(mode) => Right(CliOptions(mode, raw.noColor))
        case _            => Left("tabbyshell: application modes are mutually exclusive")
