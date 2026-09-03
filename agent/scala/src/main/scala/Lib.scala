package workshop.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** A single agent-phase run. Encapsulates everything needed to drive one `Exercises.handleTurn`
  * invocation: provider construction, system-prompt assembly, conversation setup, input-file
  * augmentation, and the agent loop.
  *
  * Instances are short-lived — one phase per instance. State held by the instance (temp role file,
  * provider) is cleaned up by `run()`.
  *
  * The in-process library entry point. The CLI in [[Main]] uses [[Agent]] directly; workflows and
  * other in-process orchestrators construct a `PhaseRun` (or call the [[Lib.runPhase]] forwarder).
  */
final class PhaseRun(opts: PhaseRun.RunPhaseOptions) {
  import PhaseRun.*

  def run(): RunPhaseResult = {
    val apiKey = opts.apiKey
      .orElse(sys.env.get("OPENROUTER_API_KEY").filter(_.nonEmpty))
      .getOrElse(throw new RuntimeException("OPENROUTER_API_KEY not set"))
    val baseUrl = opts.baseUrl.getOrElse("https://openrouter.ai/api/v1")

    val tmp = Files.createTempFile(s"phase-role-${ProcessHandle.current().pid()}-", ".md")
    Files.writeString(tmp, opts.rolePromptText, StandardCharsets.UTF_8)

    val registry = ToolRegistry.default
    val selection: ToolSelection =
      if (opts.tools.isEmpty) ToolSelection.All
      else {
        ToolSelection.parse(opts.tools, registry) match {
          case Right(sel) => sel
          case Left(err) =>
            Files.deleteIfExists(tmp)
            throw new RuntimeException(err)
        }
      }

    val config = AgentConfig.default.copy(
      model = opts.model.getOrElse(AgentConfig.default.model),
      systemPrompt = resolveSystemPrompt(opts.systemPromptPath),
      rolePrompt = Some(tmp),
      tools = selection,
      workDir = opts.workDir,
      maxIterations = opts.maxIterations,
      temperature = opts.temperature,
      denyTools = opts.denyTools,
      inputFiles = opts.inputFiles,
      outputFile = opts.outputFile,
      inputFormat = IoFormat.Text,
      outputFormat = IoFormat.Text,
      currentPhase = opts.phase
    )

    val provider = ChatProvider.openAi(apiKey, baseUrl, config.model, config.temperature)

    val enabledTools = config.enabledTools(registry.tools)
    val systemPrompt = Exercises.buildSystemPrompt(config, enabledTools)
    val agent = Agent(
      state = AgentState.initial(systemPrompt, SessionId.fresh),
      config = config,
      tools = enabledTools,
      provider = provider
    )
    val augmented = config.augmentInput(opts.userMessage)

    val (_, turn) =
      try Exercises.handleTurn(agent, augmented, TerminalOutput.silent, opts.decorators)
      finally Files.deleteIfExists(tmp)

    config.outputFile.foreach { path =>
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(path, turn.content + "\n", StandardCharsets.UTF_8)
    }

    RunPhaseResult(turn.content, turn.toolCalls.map(_.wire))
  }
}

object PhaseRun {

  final case class RunPhaseOptions(
    rolePromptText: String,
    userMessage: String,
    tools: Vector[String],
    maxIterations: Int = 20,
    temperature: Double = 0.0,
    denyTools: Vector[String] = Vector.empty,
    workDir: Path = Paths.get(System.getProperty("user.dir")),
    inputFiles: Vector[Path] = Vector.empty,
    outputFile: Option[Path] = None,
    model: Option[String] = None,
    systemPromptPath: Option[Path] = None,
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    decorators: Vector[ToolDecorator] = Vector.empty,
    /** Explicit phase id surfaced to decorators via [[AgentConfig.currentPhase]]. Workflow
      * orchestrators MUST set this; they materialize the role prompt to a temp file whose basename
      * can't recover the phase id.
      */
    phase: Option[String] = None
  )

  final case class RunPhaseResult(content: String, toolCalls: Vector[String])

  /** Walk a small set of candidate locations to find `system-prompt.txt` next to the agent crate.
    * Mirrors the TS resolvePromptPath ladder.
    */
  private def resolveSystemPrompt(explicit: Option[Path]): Path =
    explicit match {
      case Some(p) => p
      case None =>
        val candidates = Vector(
          Paths.get("..", "system-prompt.txt"),
          Paths.get("system-prompt.txt")
        )
        candidates.find(p => Files.exists(p)).getOrElse(candidates.head)
    }
}

/** Thin forwarder kept for API parity. Existing workflow integrations that imported `Lib.runPhase`
  * continue to work; new code should use `new PhaseRun(opts).run()`.
  */
object Lib {
  type RunPhaseOptions = PhaseRun.RunPhaseOptions
  val RunPhaseOptions: PhaseRun.RunPhaseOptions.type = PhaseRun.RunPhaseOptions

  type RunPhaseResult = PhaseRun.RunPhaseResult
  val RunPhaseResult: PhaseRun.RunPhaseResult.type = PhaseRun.RunPhaseResult

  def runPhase(opts: PhaseRun.RunPhaseOptions): PhaseRun.RunPhaseResult =
    new PhaseRun(opts).run()
}
