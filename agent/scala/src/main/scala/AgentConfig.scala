package workshop.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class AgentConfig(
  model: String,
  systemPrompt: Path,
  rolePrompt: Option[Path] = None,
  tools: ToolSelection,
  workDir: Path,
  maxIterations: Int,
  temperature: Double,
  verbose: Boolean = false,
  instructions: Option[String] = None,
  skillsDir: Option[Path] = None,
  historyDir: Option[Path] = None,
  commandsDir: Option[Path] = None,
  maxContextChars: Option[Int] = None,
  allowTools: Vector[String] = Vector.empty,
  denyTools: Vector[String] = Vector.empty,
  protectedFiles: Vector[String] = Vector.empty,
  secretPatterns: SecretPatterns = SecretPatterns.default,
  auditLog: Option[Path] = None,
  sandboxVm: Boolean = false,
  inputFiles: Vector[Path] = Vector.empty,
  outputFile: Option[Path] = None,
  inputFormat: IoFormat = IoFormat.Json,
  outputFormat: IoFormat = IoFormat.Json,
  instructionsFileName: String = "AGENTS.md",
  skillFileName: String = "SKILL.md",
  memoryDirectoryName: String = ".agent",
  memoryFileName: String = "memories.md",
  sessionSummaryMaxChars: Int = 80,
  /** Phase identifier surfaced to decorators (see [[ToolDecorator]]) so their `appliesTo` predicate
    * can dispatch on the current phase. Workflow orchestrators set this; the bare CLI leaves it
    * `None`.
    */
  currentPhase: Option[String] = None,
  /** Shell commands that must each exit 0 before the agent may terminate via `message_user`. When
    * non-empty, an exit-gate decorator (see [[Exercises.exitGate]]) denies premature `message_user`
    * calls and feeds the gate's exit code + stdout/stderr back as a turn for the LLM to fix.
    */
  exitGates: Vector[String] = Vector.empty,
  /** Path to the compaction prompt template used when summarising a long conversation. Defaults to
    * `../compaction-prompt.txt` (the workshop's conventional location next to the agent crate);
    * orchestrators may override at the system boundary.
    */
  compactionPrompt: Path = Path.of("../compaction-prompt.txt")
) {

  /** The file where durable memories are stored, derived from `workDir` and the memory
    * directory/filename config. Tools and exercises that read/write memory go through this so the
    * convention has exactly one home.
    */
  def memoryFile: Path = workDir.resolve(memoryDirectoryName).resolve(memoryFileName)

  def enabledTools(allTools: Vector[Tool]): Vector[Tool] =
    tools match {
      case ToolSelection.All => allTools
      case ToolSelection.Only(selected) =>
        val requested = selected + ToolName.MessageUser
        allTools.filter(tool => requested.contains(tool.name))
    }

  /** Apply non-empty overrides on top of this config. Orthogonal to choice of base config — callers
    * compose: `AgentConfig.default.overrideWith(o)`, or `myConfig.overrideWith(o)`.
    */
  def overrideWith(overrides: AgentConfigOverrides): AgentConfig =
    copy(
      model = overrides.model.getOrElse(model),
      systemPrompt = overrides.systemPrompt.getOrElse(systemPrompt),
      rolePrompt = overrides.rolePrompt.orElse(rolePrompt),
      tools = overrides.tools.getOrElse(tools),
      workDir = overrides.workDir.getOrElse(workDir),
      maxIterations = overrides.maxIterations.getOrElse(maxIterations),
      temperature = overrides.temperature.getOrElse(temperature),
      verbose = overrides.verbose.getOrElse(verbose),
      instructions = overrides.instructions.orElse(instructions),
      skillsDir = overrides.skillsDir.orElse(skillsDir),
      historyDir = overrides.historyDir.orElse(historyDir),
      commandsDir = overrides.commandsDir.orElse(commandsDir),
      maxContextChars = overrides.maxContextChars.orElse(maxContextChars),
      allowTools = overrides.allowTools.getOrElse(allowTools),
      denyTools = overrides.denyTools.getOrElse(denyTools),
      protectedFiles = overrides.protectedFiles.getOrElse(protectedFiles),
      secretPatterns = overrides.secretPatterns.getOrElse(secretPatterns),
      auditLog = overrides.auditLog.orElse(auditLog),
      sandboxVm = overrides.sandboxVm.getOrElse(sandboxVm),
      inputFiles = overrides.inputFiles.filter(_.nonEmpty).getOrElse(inputFiles),
      outputFile = overrides.outputFile.orElse(outputFile),
      inputFormat = overrides.inputFormat.getOrElse(inputFormat),
      outputFormat = overrides.outputFormat.getOrElse(outputFormat),
      currentPhase = overrides.currentPhase.orElse(currentPhase),
      exitGates = overrides.exitGates.getOrElse(exitGates)
    )

  def parseInput(line: String): String = inputFormat.parseInput(line)

  /** Augment a user message by prepending the contents of any configured `inputFiles`. */
  def augmentInput(userContent: String): String =
    if (inputFiles.isEmpty) userContent
    else {
      val sections = inputFiles.flatMap { filePat =>
        val resolved =
          if (filePat.isAbsolute) filePat
          else workDir.resolve(filePat).normalize

        val dir  = Option(resolved.getParent).getOrElse(Path.of("."))
        val base = Option(resolved.getFileName).map(_.toString).getOrElse("")

        val files: Vector[Path] =
          if (base.contains("*")) {
            if (!Files.isDirectory(dir)) Vector.empty
            else
              Using.resource(Files.list(dir)) { stream =>
                stream
                  .iterator()
                  .asScala
                  .toVector
                  .filter(path =>
                    Option(path.getFileName)
                      .exists(name => AgentConfig.globMatches(base, name.toString))
                  )
                  .sortBy(_.toString)
              }
          } else Vector(resolved)

        files.flatMap { filePath =>
          if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            Try(Files.readString(filePath, StandardCharsets.UTF_8)).toOption.map { fileContent =>
              s"--- ${filePath.getFileName.toString} ---\n$fileContent"
            }
          } else None
        }
      }

      if (sections.nonEmpty) sections.mkString("\n\n") + "\n\n" + userContent
      else userContent
    }

  /** Write tool output to the configured output file, if set. */
  def writeOutputFile(content: String): Unit =
    outputFile.foreach { path =>
      Try {
        Option(path.getParent).foreach(parent => Files.createDirectories(parent))
        Files.writeString(path, content + "\n", StandardCharsets.UTF_8)
      }.getOrElse(())
    }
}

object AgentConfig {
  private def globMatches(pattern: String, fileName: String): Boolean = {
    val regex = "^" + pattern.split("\\*", -1).map(Pattern.quote).mkString(".*") + "$"
    Try(regex.r.matches(fileName)).getOrElse(false)
  }

  def default: AgentConfig = AgentConfig(
    model = sys.env.getOrElse("MODEL", "anthropic/claude-sonnet-4"),
    systemPrompt = Path.of("../system-prompt.txt"),
    tools = ToolSelection.All,
    workDir = Path.of(System.getProperty("user.dir")),
    maxIterations = 20,
    temperature = 0.0
  )
}
