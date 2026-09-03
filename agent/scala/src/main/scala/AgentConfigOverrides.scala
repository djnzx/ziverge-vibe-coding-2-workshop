package workshop.agent

import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

final case class AgentConfigOverrides(
  model: Option[String] = None,
  systemPrompt: Option[Path] = None,
  rolePrompt: Option[Path] = None,
  tools: Option[ToolSelection] = None,
  workDir: Option[Path] = None,
  maxIterations: Option[Int] = None,
  temperature: Option[Double] = None,
  verbose: Option[Boolean] = None,
  instructions: Option[String] = None,
  skillsDir: Option[Path] = None,
  historyDir: Option[Path] = None,
  commandsDir: Option[Path] = None,
  maxContextChars: Option[Int] = None,
  allowTools: Option[Vector[String]] = None,
  denyTools: Option[Vector[String]] = None,
  protectedFiles: Option[Vector[String]] = None,
  secretPatterns: Option[SecretPatterns] = None,
  auditLog: Option[Path] = None,
  sandboxVm: Option[Boolean] = None,
  inputFiles: Option[Vector[Path]] = None,
  outputFile: Option[Path] = None,
  inputFormat: Option[IoFormat] = None,
  outputFormat: Option[IoFormat] = None,
  currentPhase: Option[String] = None,
  exitGates: Option[Vector[String]] = None
) {

  /** Layer `other` on top of `this`: any field set on `other` takes precedence; any field unset on
    * `other` falls back to `this`.
    */
  def merge(other: AgentConfigOverrides): AgentConfigOverrides =
    AgentConfigOverrides(
      model = other.model.orElse(model),
      systemPrompt = other.systemPrompt.orElse(systemPrompt),
      rolePrompt = other.rolePrompt.orElse(rolePrompt),
      tools = other.tools.orElse(tools),
      workDir = other.workDir.orElse(workDir),
      maxIterations = other.maxIterations.orElse(maxIterations),
      temperature = other.temperature.orElse(temperature),
      verbose = other.verbose.orElse(verbose),
      instructions = other.instructions.orElse(instructions),
      skillsDir = other.skillsDir.orElse(skillsDir),
      historyDir = other.historyDir.orElse(historyDir),
      commandsDir = other.commandsDir.orElse(commandsDir),
      maxContextChars = other.maxContextChars.orElse(maxContextChars),
      allowTools = other.allowTools.orElse(allowTools),
      denyTools = other.denyTools.orElse(denyTools),
      protectedFiles = other.protectedFiles.orElse(protectedFiles),
      secretPatterns = other.secretPatterns.orElse(secretPatterns),
      auditLog = other.auditLog.orElse(auditLog),
      sandboxVm = other.sandboxVm.orElse(sandboxVm),
      inputFiles = other.inputFiles.orElse(inputFiles),
      outputFile = other.outputFile.orElse(outputFile),
      inputFormat = other.inputFormat.orElse(inputFormat),
      outputFormat = other.outputFormat.orElse(outputFormat),
      currentPhase = other.currentPhase.orElse(currentPhase),
      exitGates = other.exitGates.orElse(exitGates)
    )
}

object AgentConfigOverrides {
  def fromFile(filePath: String): Either[String, AgentConfigOverrides] =
    try {
      val content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8)
      val json    = Json.parseUnsafe(content)
      val configDir = Option(Path.of(filePath).toAbsolutePath.normalize.getParent)
        .getOrElse(Path.of(".").toAbsolutePath.normalize)
      def resolveConfigPath(raw: String): Path = {
        val parsed = Path.of(raw)
        if (parsed.isAbsolute) parsed else configDir.resolve(parsed).normalize
      }
      val parsedTools = json.get("tools").as[Vector[String]].toOption match {
        case Some(value) => ToolSelection.parse(value, emptyMeansAll = true).map(Some(_))
        case None        => Right(None)
      }

      parsedTools.map { tools =>
        AgentConfigOverrides(
          model = json.get("model").as[String].toOption,
          systemPrompt = json.get("systemPrompt").as[String].toOption.map(resolveConfigPath),
          rolePrompt = json.get("rolePrompt").as[String].toOption.map(resolveConfigPath),
          tools = tools,
          workDir = json.get("workDir").as[String].toOption.map(resolveConfigPath),
          maxIterations = json.get("maxIterations").as[Int].toOption,
          temperature = json.get("temperature").as[Double].toOption,
          verbose = json.get("verbose").as[Boolean].toOption,
          instructions = json
            .get("instructions")
            .as[String]
            .toOption
            .map(raw => resolveConfigPath(raw).toString),
          skillsDir = json.get("skillsDir").as[String].toOption.map(resolveConfigPath),
          historyDir = json.get("historyDir").as[String].toOption.map(resolveConfigPath),
          commandsDir = json.get("commandsDir").as[String].toOption.map(resolveConfigPath),
          maxContextChars = json.get("maxContextChars").as[Int].toOption,
          allowTools = json.get("allowTools").as[Vector[String]].toOption,
          denyTools = json.get("denyTools").as[Vector[String]].toOption,
          protectedFiles = json.get("protectedFiles").as[Vector[String]].toOption,
          secretPatterns =
            json.get("secretPatterns").as[Vector[String]].toOption.map(SecretPatterns.apply),
          auditLog = json.get("auditLog").as[String].toOption.map(resolveConfigPath),
          inputFiles = json
            .get("inputFiles")
            .as[Vector[String]]
            .toOption
            .map(_.map(resolveConfigPath)),
          outputFile = json.get("outputFile").as[String].toOption.map(resolveConfigPath),
          inputFormat = json.get("inputFormat").as[String].toOption.flatMap(IoFormat.fromString),
          outputFormat = json.get("outputFormat").as[String].toOption.flatMap(IoFormat.fromString),
          currentPhase = json.get("currentPhase").as[String].toOption,
          exitGates = json.get("exitGates").as[Vector[String]].toOption
        )
      }
    } catch {
      case e: Exception => Left(s"Failed to load config file: ${e.getMessage}")
    }
}
