package workshop.agent

import java.nio.file.Path
import scala.util.Try

final case class CliConfig(
  config: AgentConfig,
  runMode: RunMode,
  resumeSessionId: Option[String] = None
)

object CliConfig {
  // Flags that consume the next argument as their value. Everything else is either a
  // bare switch (handled inline) or unrecognised.
  private val ValueFlags: Set[String] = Set(
    "--config",
    "--model",
    "--prompt",
    "--role-prompt",
    "--tools",
    "--work-dir",
    "--max-iterations",
    "--temperature",
    "--file",
    "--instructions",
    "--skills-dir",
    "--history-dir",
    "--commands-dir",
    "--max-context-chars",
    "--allow-tools",
    "--deny-tools",
    "--protected-files",
    "--secret-patterns",
    "--audit-log",
    "--input-files",
    "--output-file",
    "--input-format",
    "--output-format",
    "--exit-gate",
    "--resume"
  )

  private sealed trait Acc
  private object Acc {
    final case class Step(
      overrides: AgentConfigOverrides,
      runMode: RunMode,
      modeFlag: Option[String],
      resumeSessionId: Option[String] = None
    ) extends Acc
    final case class Awaiting(flag: String, ctx: Step) extends Acc
    final case class Failed(message: String)           extends Acc
  }

  def parse(args: Array[String]): Either[String, CliConfig] = {
    val initial: Acc = Acc.Step(AgentConfigOverrides(), RunMode.AutoDetect, modeFlag = None)

    val finalAcc = args.foldLeft(initial) { (acc, arg) =>
      acc match {
        case f: Acc.Failed           => f
        case Acc.Awaiting(flag, ctx) => applyValue(flag, arg, ctx)
        case ctx: Acc.Step           => applyArg(arg, ctx)
      }
    }

    finalAcc match {
      case Acc.Failed(msg)       => Left(msg)
      case Acc.Awaiting(flag, _) => Left(s"$flag requires a value")
      case Acc.Step(overrides, runMode, _, resumeSessionId) =>
        Right(CliConfig(AgentConfig.default.overrideWith(overrides), runMode, resumeSessionId))
    }
  }

  private def applyArg(arg: String, ctx: Acc.Step): Acc = arg match {
    case "--verbose"     => ctx.copy(overrides = ctx.overrides.copy(verbose = Some(true)))
    case "--sandbox-vm"  => ctx.copy(overrides = ctx.overrides.copy(sandboxVm = Some(true)))
    case "--interactive" => setRunMode(ctx, RunMode.Interactive, "--interactive")
    case "--protocol"    => setRunMode(ctx, RunMode.Protocol, "--protocol")
    case flag if ValueFlags.contains(flag) => Acc.Awaiting(flag, ctx)
    case _                                 => ctx // unrecognised arg — skipped, as before
  }

  private def applyValue(flag: String, value: String, ctx: Acc.Step): Acc = {
    def requireNonEmpty(next: => Acc): Acc =
      if (value.nonEmpty) next else Acc.Failed(s"$flag requires a value")

    def setOverride(update: AgentConfigOverrides => AgentConfigOverrides): Acc =
      ctx.copy(overrides = update(ctx.overrides))

    flag match {
      case "--config" =>
        AgentConfigOverrides.fromFile(value) match {
          case Right(fileOverrides) => setOverride(o => fileOverrides.merge(o))
          case Left(err)            => Acc.Failed(err)
        }
      case "--model"  => requireNonEmpty(setOverride(_.copy(model = Some(value))))
      case "--prompt" => requireNonEmpty(setOverride(_.copy(systemPrompt = Some(Path.of(value)))))
      case "--role-prompt" =>
        requireNonEmpty(setOverride(_.copy(rolePrompt = Some(Path.of(value)))))
      case "--tools" =>
        ToolSelection.parse(parseCsv(value)) match {
          case Right(sel) => setOverride(_.copy(tools = Some(sel)))
          case Left(err)  => Acc.Failed(err)
        }
      case "--work-dir" => requireNonEmpty(setOverride(_.copy(workDir = Some(Path.of(value)))))
      case "--max-iterations" =>
        requireNonEmpty {
          Try(value.toInt).toEither.left.map(_ =>
            s"Invalid integer for --max-iterations: $value"
          ) match {
            case Right(parsed) => setOverride(_.copy(maxIterations = Some(parsed)))
            case Left(err)     => Acc.Failed(err)
          }
        }
      case "--temperature" =>
        requireNonEmpty {
          Try(value.toDouble).toEither.left.map(_ =>
            s"Invalid number for --temperature: $value"
          ) match {
            case Right(parsed) => setOverride(_.copy(temperature = Some(parsed)))
            case Left(err)     => Acc.Failed(err)
          }
        }
      case "--file" => requireNonEmpty(setRunMode(ctx, RunMode.FileMode(Path.of(value)), "--file"))
      case "--instructions" => requireNonEmpty(setOverride(_.copy(instructions = Some(value))))
      case "--skills-dir" => requireNonEmpty(setOverride(_.copy(skillsDir = Some(Path.of(value)))))
      case "--history-dir" =>
        requireNonEmpty(setOverride(_.copy(historyDir = Some(Path.of(value)))))
      case "--resume" => requireNonEmpty(ctx.copy(resumeSessionId = Some(value)))
      case "--commands-dir" =>
        requireNonEmpty(setOverride(_.copy(commandsDir = Some(Path.of(value)))))
      case "--max-context-chars" =>
        requireNonEmpty {
          Try(value.toInt).toEither.left.map(_ =>
            s"Invalid integer for --max-context-chars: $value"
          ) match {
            case Right(parsed) => setOverride(_.copy(maxContextChars = Some(parsed)))
            case Left(err)     => Acc.Failed(err)
          }
        }
      case "--allow-tools" =>
        requireNonEmpty(setOverride(_.copy(allowTools = Some(parseCsv(value)))))
      case "--deny-tools" => requireNonEmpty(setOverride(_.copy(denyTools = Some(parseCsv(value)))))
      case "--protected-files" =>
        requireNonEmpty(setOverride(_.copy(protectedFiles = Some(parseCsv(value)))))
      case "--secret-patterns" =>
        requireNonEmpty(setOverride(_.copy(secretPatterns = Some(SecretPatterns(parseCsv(value))))))
      case "--audit-log" => requireNonEmpty(setOverride(_.copy(auditLog = Some(Path.of(value)))))
      case "--exit-gate" =>
        requireNonEmpty {
          setOverride { o =>
            val current = o.exitGates.getOrElse(Vector.empty)
            o.copy(exitGates = Some(current :+ value))
          }
        }
      case "--input-files" =>
        requireNonEmpty(setOverride(_.copy(inputFiles = Some(parseCsv(value).map(Path.of(_))))))
      case "--output-file" =>
        requireNonEmpty(setOverride(_.copy(outputFile = Some(Path.of(value)))))
      case "--input-format" =>
        requireNonEmpty {
          IoFormat.fromString(value) match {
            case Some(fmt) => setOverride(_.copy(inputFormat = Some(fmt)))
            case None =>
              Acc.Failed(s"Invalid value for --input-format: $value (must be json or text)")
          }
        }
      case "--output-format" =>
        requireNonEmpty {
          IoFormat.fromString(value) match {
            case Some(fmt) => setOverride(_.copy(outputFormat = Some(fmt)))
            case None =>
              Acc.Failed(s"Invalid value for --output-format: $value (must be json or text)")
          }
        }
      case _ => ctx // unknown value-flag — shouldn't reach here (gated by ValueFlags)
    }
  }

  private def setRunMode(ctx: Acc.Step, nextMode: RunMode, nextFlag: String): Acc =
    if (ctx.runMode == RunMode.AutoDetect)
      ctx.copy(runMode = nextMode, modeFlag = Some(nextFlag))
    else {
      val existing = ctx.modeFlag.getOrElse(ctx.runMode.toString)
      Acc.Failed(s"Conflicting mode flags: $existing and $nextFlag")
    }

  private def parseCsv(value: String): Vector[String] =
    value.split(",").map(_.trim).filter(_.nonEmpty).toVector
}
