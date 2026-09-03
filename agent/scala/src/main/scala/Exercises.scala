package workshop.agent

import sttp.client4.{asStringAlways, basicRequest, UriContext}
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}
import zio.blocks.schema.json.Json

import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.regex.{Matcher, Pattern}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

import workshop.agent.internal.Frontmatter.{parseFrontmatter, stripFrontmatter}
import workshop.agent.internal.TextUtils.truncateSingleLine
import workshop.agent.internal.FileIO.readFileIfPresent

object Exercises {

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 1: Build the system prompt
  // ---------------------------------------------------------------------------

  // Implementation: Load the prompt template and render enabled tool descriptions and schemas.
  // Failure mode: Missing or vague affordances make the model guess tool names and arguments.
  // Agentic coding lesson: Tool schemas are delegation contracts, not incidental documentation.

  private val TtyHint =
    "The user is in an interactive terminal with colored output. " +
      "Do NOT use markdown formatting (no **, `, #, or ```) in your message_user responses. " +
      "Write plain text — the terminal UI handles all visual formatting."

  /** Builds the system prompt that teaches the LLM the tool protocol.
    *
    * Reads the template from `../system-prompt.txt` (relative to the agent's cwd) and replaces
    * `{{TOOL_DESCRIPTIONS}}` with the formatted tool descriptions. Each tool's JSON Schema is
    * embedded so the LLM knows the expected argument shape.
    *
    * @param interactive
    *   if true, injects TTY formatting hints into the prompt
    */
  def buildSystemPrompt(
    config: AgentConfig,
    enabledTools: Vector[Tool],
    interactive: Boolean = false
  ): String =
    // TODO — Module 01, Exercise 1. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 1 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 2: Parse tool calls from LLM text
  // ---------------------------------------------------------------------------

  // Implementation: Parse canonical and flat tool envelopes while preserving valid calls and errors.
  // Failure mode: Silently discarded malformed output leaves the loop unable to recover.
  // Agentic coding lesson: Treat model output as untrusted protocol input and return repairable errors.

  // Defines the exact tool-call envelope contract. Changes require keeping prompt text
  // and tests aligned.
  private val ToolCallRegex = "(?s)<tool_call>\\s*(.*?)\\s*</tool_call>".r
  // Terminal only surfaces reasoning in this exact side-channel shape. Prompt/model
  // changes must preserve this layout for visible thinking.
  private val ThinkingRegex = "(?si)THINKING:\\s*(.*?)(?:ACTION:|<tool_call>|$)".r

  /** Extracts `name` and `arguments` from a JSON object representing a single tool call. The
    * `arguments` field is kept as raw Json for downstream deserialization by the tool's execute
    * function.
    */
  /** Valid calls and parse errors can coexist. The loop feeds errors back to the model only when no
    * valid call is executable.
    */
  private def parseToolCallPayload(json: Json): Either[String, RawToolCall] =
    json.get("name").as[String].toOption.filter(_.nonEmpty) match {
      case Some(name) =>
        val arguments = json.get("arguments").one.toOption.getOrElse {
          json match {
            case Json.Object(fields) =>
              Json.Object(fields.filter { case (k, _) => k != "name" })
            case other => other
          }
        }
        Right(RawToolCall(name, arguments))
      case None =>
        Left(s"Tool call JSON missing \"name\" field: ${json.print.take(100)}")
    }

  def parseToolCalls(text: String): (Vector[RawToolCall], Vector[String]) =
    // TODO — Module 01, Exercise 2. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 2 — see 01-foundations/exercises.md")

  private def extractThinking(text: String): Option[String] =
    ThinkingRegex.findFirstMatchIn(text).map(_.group(1).trim).filter(_.nonEmpty)

  private def preview(text: String, maxChars: Int): String = {
    val shortened = text.take(maxChars)
    if (text.length > maxChars) s"$shortened..." else shortened
  }

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 3: Implement the agent loop
  // ---------------------------------------------------------------------------

  // Implementation: Iterate model calls, execute one tool, return its result, and terminate explicitly.
  // Failure mode: A one-shot or lossy loop acts on guesses and cannot learn from execution.
  // Agentic coding lesson: An agent is a feedback loop, not a single model response.

  /** Runs the agent's internal loop for a single user turn.
    *
    * Appends the user message to the conversation, then iterates: send to LLM, parse any tool
    * calls, execute them, and feed the result back. Continues until the LLM calls `message_user` or
    * the iteration limit (20) is reached.
    *
    * If multiple tool calls are present in one model response, only the first is executed.
    *
    * If the LLM responds with no tool call, a nudge message is injected to keep it on track.
    *
    * The assistant message history is truncated at the first `</tool_call>` tag to avoid including
    * multiple tool calls in the conversation.
    *
    * @return
    *   a [[TurnResult]] containing the final response content, ordered list of tool names invoked
    *   (including the successful terminating tool), and the updated conversation history.
    */
  def handleTurn(
    agent: Agent,
    userContent: String,
    terminal: TerminalOutput = TerminalOutput.silent,
    decorators: Vector[ToolDecorator] = Vector.empty
  ): (Agent, TurnResult) =
    // TODO — Module 01, Exercise 3. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 3 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 4: Implement read_file
  // ---------------------------------------------------------------------------

  // Implementation: Validate a path and return file contents or a visible read failure.
  // Failure mode: Without readable evidence, the model can invent facts about the repository.
  // Agentic coding lesson: Require agents to inspect source evidence before accepting their claims.

  // Paths used exactly as the params supply — workDir resolution happens at the
  // ToolCall.execute boundary via `Tool.ReadFile.Params.withResolvedPath`.
  def executeReadFile(params: Tool.ReadFile.Params): ToolResult =
    // TODO — Module 01, Exercise 4. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 4 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 5: Implement shell
  // ---------------------------------------------------------------------------

  // Implementation: Execute commands with a cwd, timeout, bounded streams, and explicit exit evidence.
  // Failure mode: Missing stderr, status, or output bounds turns failed checks into ambiguous feedback.
  // Agentic coding lesson: Shell commands make claims observable only when all evidence returns to the loop.

  def executeShell(params: Tool.Shell.Params, workDir: Path): ToolResult =
    // TODO — Module 01, Exercise 5. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 5 — see 01-foundations/exercises.md")
  private val ShellOutputByteLimit = 1024 * 1024

  private def truncateUtf8Bytes(value: String, maxBytes: Int): String = {
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    if (bytes.length <= maxBytes) value
    else new String(bytes.take(maxBytes), StandardCharsets.UTF_8)
  }

  // Cross-language contract: 30s timeout, 1MB output cap, failures return error strings.
  // Stdout and stderr are drained in separate threads so the timeout fires independently
  // of output volume (readAllBytes would block until process exits, defeating the timeout).
  def runShell(command: String, workDir: Path): ToolResult =
    runProcessWithTimeout(
      builder = new ProcessBuilder("sh", "-c", command).directory(workDir.toFile),
      timeoutMessage = "command timed out after 30s",
      errorPrefix = ""
    )

  // Shared process-runner: drains stdout/stderr on background threads, enforces a 30s timeout,
  // and caps output at ShellOutputByteLimit bytes. Failures return ToolResult.Failure with the
  // given prefix (or the empty prefix's exit-code format for runShell).
  private def runProcessWithTimeout(
    builder: ProcessBuilder,
    timeoutMessage: String,
    errorPrefix: String
  ): ToolResult =
    Try {
      val process   = builder.start()
      val stdoutBuf = new java.io.ByteArrayOutputStream()
      val stderrBuf = new java.io.ByteArrayOutputStream()
      val stdoutReader = new Thread(new Runnable {
        def run(): Unit =
          try { val _ = process.getInputStream.transferTo(stdoutBuf) }
          catch { case _: Exception => () }
      })
      val stderrReader = new Thread(new Runnable {
        def run(): Unit =
          try { val _ = process.getErrorStream.transferTo(stderrBuf) }
          catch { case _: Exception => () }
      })
      stdoutReader.start()
      stderrReader.start()

      try {
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
          process.destroyForcibly()
          stdoutReader.join(1000)
          stderrReader.join(1000)
          if (errorPrefix.isEmpty) ToolResult.Failure(timeoutMessage)
          else ToolResult.Failure(s"$errorPrefix$timeoutMessage")
        } else {
          stdoutReader.join(5000)
          stderrReader.join(5000)
          val stdout = truncateUtf8Bytes(
            new String(stdoutBuf.toByteArray, StandardCharsets.UTF_8),
            ShellOutputByteLimit
          )
          val stderr = truncateUtf8Bytes(
            new String(stderrBuf.toByteArray, StandardCharsets.UTF_8),
            ShellOutputByteLimit
          ).trim

          if (process.exitValue() == 0) ToolResult.Success(stdout)
          else if (errorPrefix.nonEmpty) {
            ToolResult.Failure(s"$errorPrefix${if (stderr.isEmpty) stdout else stderr}")
          } else {
            val parts = Seq(stdout.trim, stderr).filter(_.nonEmpty).mkString("\n")
            if (parts.isEmpty)
              ToolResult.Failure(s"(exit code ${process.exitValue()})")
            else
              ToolResult.Failure(s"(exit code ${process.exitValue()}):\n$parts")
          }
        }
      } finally if (process.isAlive) process.destroyForcibly()
    }.fold(
      err =>
        if (errorPrefix.nonEmpty) ToolResult.Failure(s"${errorPrefix}${err.getMessage}")
        else ToolResult.Failure(err.getMessage),
      identity
    )

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 6: Iterate on the system prompt
  // ---------------------------------------------------------------------------

  // Implementation: Compare this naïve prompt with defensive clauses using fixed behavioral tasks.
  // Failure mode: Plausible wording gets accepted without evidence that agent behavior improved.
  // Agentic coding lesson: Prompt quality is empirical, so evaluate outcomes rather than prose.

  // TODO — Module 01, Exercise 6. See 01-foundations/exercises.md.
  val NaiveSystemPrompt: String = ""

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 7: Harden the loop
  // ---------------------------------------------------------------------------

  // Implementation: Normalize malformed calls and bound tool output before it enters model history.
  // Failure mode: Bad envelopes and oversized results compound across later iterations.
  // Agentic coding lesson: Model-visible context is a controlled interface with a finite budget.

  // truncateOutput and MaxOutputChars now live on ToolResult — kept as forwarders so
  // existing call sites and the workshop exercise tests continue to reference Exercises.
  export ToolResult.{truncateOutput, MaxOutputChars}

  // Workshop waypoint — Module 01, Exercise 8 (manual; no source implementation)
  // Implementation: Run identical tasks across models and settings, then record behavioral differences.
  // Failure mode: Selection by reputation hides task-specific reliability and cost trade-offs.
  // Agentic coding lesson: Choose models from observed task behavior, risk, and cost—not brand reputation.

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 9: Sudoku — the reasoning illusion
  // ---------------------------------------------------------------------------

  // Implementation: Parse candidate grids and validate every Sudoku constraint deterministically.
  // Failure mode: Fluent reasoning can confidently return a grid that violates hard constraints.
  // Agentic coding lesson: Use deterministic validators instead of treating model reasoning as proof.

  def verifySudoku(grid: Array[Array[Int]]): Vector[String] =
    // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 9 — see 01-foundations/exercises.md")

  def parseSudokuGrid(text: String): Option[Array[Array[Int]]] =
    // TODO — Module 01, Exercise 9. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 9 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 10: The fabrication test
  // ---------------------------------------------------------------------------

  // Implementation: Judge whether a response answers the question or reports insufficient specification.
  // Failure mode: A model may answer an underspecified question confidently; this judge cannot establish truth.
  // Agentic coding lesson: Test fabrication pressure with controlled ambiguity, then verify factual claims with tools.

  def evaluateResponse(provider: ChatProvider, agentResponse: String): String =
    // TODO — Module 01, Exercise 10. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 10 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 11: Implement edit_file
  // ---------------------------------------------------------------------------

  // Implementation: Edit only a line range whose exact old text still matches.
  // Failure mode: Broad or stale replacements overwrite unintended code and obscure review.
  // Agentic coding lesson: Guarded, range-scoped edits reduce blast radius and preserve ownership.

  // Line range is 1-based and inclusive. Only the first occurrence is replaced.
  // File stays unchanged if range is invalid or old_text is absent.
  def executeEditFile(params: Tool.EditFile.Params): ToolResult =
    // TODO — Module 01, Exercise 11. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 11 — see 01-foundations/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 12: Implement the list_files tool
  // ---------------------------------------------------------------------------

  // Implementation: Return deterministic repository paths up to a caller-selected depth.
  // Failure mode: Editing starts from an invented or incomplete understanding of project scope.
  // Agentic coding lesson: Repository discovery should ground planning before modification begins.

  def executeListFiles(params: Tool.ListFiles.Params): ToolResult =
    // TODO — Module 01, Exercise 12. See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 12 — see 01-foundations/exercises.md")

  def executeWebFetch(params: Tool.WebFetch.Params): ToolResult =
    Try {
      val backend = HttpClientSyncBackend()
      try {
        val response = basicRequest
          .get(uri"${params.url}")
          .response(asStringAlways)
          .send(backend)
        ToolResult.Success(response.body)
      } finally backend.close()
    }.fold(
      err => ToolResult.Failure(s"fetching url: ${err.getMessage}"),
      identity
    )

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 1: Load persistent instructions
  // ---------------------------------------------------------------------------

  // Implementation: Resolve explicit or ancestor instruction files with deterministic precedence.
  // Failure mode: Missing or surprising precedence makes delegated work violate project rules.
  // Agentic coding lesson: Persistent instructions must be predictable and inspectable.

  def loadInstructions(
    workDir: Path,
    instructionsPath: Option[String],
    instructionsFileName: String = AgentConfig.default.instructionsFileName
  ): Option[String] =
    // TODO — Module 02, Exercise 1. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 1 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 2: Discover and load skills
  // ---------------------------------------------------------------------------

  // Implementation: Discover skill metadata eagerly and load full specialist guidance on demand.
  // Failure mode: Loading everything crowds context; loading nothing omits needed expertise.
  // Agentic coding lesson: Selective context improves decisions without permanently consuming the window.

  def discoverSkills(
    skillsDir: Option[Path],
    skillFileName: String = AgentConfig.default.skillFileName
  ): Vector[SkillInfo] =
    // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 2 — see 02-context-engineering/exercises.md")

  def loadSkillContent(skillPath: Path): String =
    // TODO — Module 02, Exercise 2. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 2 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 3: Discover and execute command prompts
  // ---------------------------------------------------------------------------

  // Implementation: Discover prompt templates, strip metadata, and substitute invocation arguments.
  // Failure mode: Rewritten ad hoc prompts drift and become difficult to inspect or reproduce.
  // Agentic coding lesson: Commands turn recurring delegation patterns into explicit workflows.

  def discoverCommands(commandsDir: Option[Path]): Vector[CommandPrompt] =
    // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 3 — see 02-context-engineering/exercises.md")

  def executeCommand(command: CommandPrompt, args: String): String =
    // TODO — Module 02, Exercise 3. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 3 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Exit-gate implementation helper; the canonical exercise marker is in sequence below.
  // ---------------------------------------------------------------------------

  private val ExitGateMaxOutputBytes = 2048

  private def truncateGateOutput(s: String, max: Int): String =
    if (s.length <= max) s
    else s"${s.substring(0, max)}\n…[truncated ${s.length - max} bytes]"

  private final case class ExitGateResult(code: Int, stdout: String, stderr: String)

  private def runExitGate(cmd: String, cwd: Path): ExitGateResult =
    try {
      val proc = new ProcessBuilder("sh", "-c", cmd).directory(cwd.toFile).start()
      import scala.concurrent.duration.*
      import scala.concurrent.{Await, ExecutionContext, Future}
      given ExecutionContext = ExecutionContext.global
      val stdoutRead = Future(String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8))
      val stderrRead = Future(String(proc.getErrorStream.readAllBytes(), StandardCharsets.UTF_8))
      if (!proc.waitFor(120, TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        ExitGateResult(124, "", s"(gate timed out after 120s) `$cmd`")
      } else {
        val stdout = Await.result(stdoutRead, 5.seconds)
        val stderr = Await.result(stderrRead, 5.seconds)
        ExitGateResult(proc.exitValue(), stdout, stderr)
      }
    } catch {
      case e: Exception => ExitGateResult(127, "", s"(spawn error) ${e.getMessage}")
    }

  /** Build a `ToolDecorator` that denies `message_user` until every shell command in `commands`
    * exits 0 in the agent's `config.workDir`. Each non-zero exit yields a Deny with code +
    * truncated stdout/stderr; the LLM gets another turn to fix and retry. Empty `commands` makes
    * the decorator a no-op.
    */
  def exitGate(commands: Vector[String])(using
    ec: scala.concurrent.ExecutionContext
  ): ToolDecorator =
    // TODO — Module 03, Exercise 6. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 6 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 4: Measure context usage
  // ---------------------------------------------------------------------------

  // Implementation: Measure system, conversation, and tool-schema characters against a budget.
  // Failure mode: Context is removed or retained by intuition rather than actual pressure.
  // Agentic coding lesson: Measure context before deciding what to preserve, remove, or compress.

  def measureContext(
    systemPrompt: String,
    conversation: Conversation,
    tools: Vector[Tool],
    maxChars: Option[Int]
  ): ContextUsage =
    // TODO — Module 02, Exercise 4. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 4 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 5: Compact conversation history
  // ---------------------------------------------------------------------------

  // Implementation: Format history for summarization and replace it with a coherent summary turn pair.
  // Failure mode: Naïve truncation loses active constraints, decisions, and task state.
  // Agentic coding lesson: Compaction trades detail for capacity, so preserve operational state.

  // Strips system messages, emits plain role:content transcript. The compaction
  // prompt is written against this exact shape.
  def formatConversationForCompaction(conversation: Conversation): String =
    // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 5 — see 02-context-engineering/exercises.md")
  def applyCompaction(
    conversation: Conversation,
    summary: String
  ): Conversation =
    // TODO — Module 02, Exercise 5. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 5 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 6: Auto-compact on budget
  // ---------------------------------------------------------------------------

  // Implementation: Trigger compaction only when a configured context budget is exceeded.
  // Failure mode: Late compaction degrades behavior; eager compaction destroys useful detail.
  // Agentic coding lesson: Context budgets need deliberate thresholds rather than reactive cleanup.

  def shouldAutoCompact(usage: ContextUsage): Boolean =
    // TODO — Module 02, Exercise 6. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 6 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 7: Save and resume conversation
  // ---------------------------------------------------------------------------

  // Implementation: Persist and load the stored prompt and turns faithfully; callers choose what to trust.
  // Failure mode: Corrupt storage loses continuity, while trusting stale prompts preserves old authority.
  // Agentic coding lesson: Separate persistence fidelity from resume policy—restore history, rebuild authority.

  def saveSession(historyDir: Path, sessionId: String, conversation: Conversation): Unit =
    // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 7 — see 02-context-engineering/exercises.md")

  def loadSession(historyDir: Path, sessionId: String): Option[Conversation] =
    // TODO — Module 02, Exercise 7. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 7 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 8: Session-aware compaction
  // ---------------------------------------------------------------------------

  // Implementation: Select, summarize, and bound relevant prior sessions for the compaction prompt.
  // Failure mode: Indiscriminate history becomes noise or consumes the current task's context budget.
  // Agentic coding lesson: Cross-session continuity is useful only when it remains bounded and relevant.

  def loadPastSessionSummaries(
    historyDir: Path,
    currentSessionId: String,
    provider: ChatProvider,
    sessionSummaryMaxChars: Int = AgentConfig.default.sessionSummaryMaxChars
  ): String =
    // TODO — Module 02, Exercise 8. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 8 — see 02-context-engineering/exercises.md")
  private def summarizeTopic(
    provider: ChatProvider,
    content: String,
    maxChars: Int
  ): String = {
    val normalized = content.replaceAll("\\s+", " ").trim
    if (normalized.length <= maxChars) normalized
    else {
      val prompt =
        s"""Describe the topic of the following user message in $maxChars characters or fewer.
           |Output ONLY the topic text — no preamble, no quotes, no trailing punctuation beyond what fits.
           |
           |---
           |$normalized""".stripMargin
      val response = provider.complete(Vector(Message.UserMessage(Content.TextContent(prompt))))
      truncateSingleLine(response, maxChars)
    }
  }

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 9: Extract memories
  // ---------------------------------------------------------------------------

  // Implementation: Extract durable facts and append only meaningful entries to the memory side channel.
  // Failure mode: Transient or fabricated claims poison the context of future delegated work.
  // Agentic coding lesson: Agent memory must be selective, durable, and reviewable.

  // Derive the memory file path from a workDir using AgentConfig's canonical defaults.
  // Callers with a custom AgentConfig should use `agent.config.memoryFile` directly.
  private def defaultMemoryFilePath(workDir: Path): Path =
    AgentConfig.default.copy(workDir = workDir).memoryFile

  def extractMemories(
    provider: ChatProvider,
    userMessage: String,
    agentResponse: String,
    workDir: Path,
    memoryFile: Option[Path] = None
  ): Unit =
    // TODO — Module 02, Exercise 9. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 9 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 10: Inject memories into context
  // ---------------------------------------------------------------------------

  // Implementation: Load saved memory so scaffolding can inject it explicitly into current context.
  // Failure mode: Hidden or unbounded memory silently steers later tasks and competes with current rules.
  // Agentic coding lesson: Persistent knowledge must stay visible, bounded, and subordinate to current authority.

  def loadMemories(workDir: Path, memoryFile: Option[Path] = None): Option[String] =
    // TODO — Module 02, Exercise 10. See 02-context-engineering/exercises.md for the contract.
    sys.error("Module 02, Exercise 10 — see 02-context-engineering/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 1: Check tool permissions
  // ---------------------------------------------------------------------------

  // Implementation: Apply allow and deny patterns with deny precedence and a completion exception.
  // Failure mode: Broad permissions let delegated work exceed its intended authority.
  // Agentic coding lesson: Grant the minimum capability needed, and let explicit denial veto allowance.

  private def matchesToolPattern(pattern: String, toolName: String, toolArgs: String): Boolean = {
    val parenIdx = pattern.indexOf('(')
    if (parenIdx < 0) pattern == toolName
    else {
      val patName = pattern.substring(0, parenIdx)
      if (patName != toolName) false
      else {
        val glob = pattern.substring(parenIdx + 1).stripSuffix(")")
        val reStr =
          "^" + glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*") + "$"
        scala.util.Try(reStr.r.findFirstIn(toolArgs).isDefined).getOrElse(false)
      }
    }
  }

  def checkToolPermission(
    toolName: String,
    toolArgs: String,
    allowPatterns: Vector[String],
    denyPatterns: Vector[String]
  ): PermissionResult =
    // TODO — Module 03, Exercise 1. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 1 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 2: Enforce sandbox boundaries
  // ---------------------------------------------------------------------------

  // Implementation: Check paths and protected files, then conservatively classify shell commands.
  // Failure mode: Prompt-only analysis can approve an escaping or ambiguous command.
  // Agentic coding lesson: Advisory model judgment is not an execution boundary or real isolation.

  def enforceSandbox(
    toolName: String,
    args: Json,
    workDir: Path,
    protectedFiles: Vector[String]
  ): PermissionResult =
    // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 2 — see 03-guardrails-and-safety/exercises.md")

  def analyzeShellSandbox(
    provider: ChatProvider,
    command: String,
    workDir: Path
  ): PermissionResult =
    // TODO — Module 03, Exercise 2. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 2 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 3: Redact secrets
  // ---------------------------------------------------------------------------

  // Implementation: Apply configured patterns safely to text explicitly passed to this teaching primitive.
  // Failure mode: Assuming an unwired helper protects output or logs leaves disclosure paths open.
  // Agentic coding lesson: Treat every model and log channel as a surface that must be wired and verified.

  def redactSecrets(text: String, patterns: Vector[String]): String =
    // TODO — Module 03, Exercise 3. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 3 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 4: Log audit events
  // ---------------------------------------------------------------------------

  // Implementation: Append structured JSONL events that reconstruct attempted and completed actions.
  // Failure mode: A final agent narrative cannot prove what tools actually ran or changed.
  // Agentic coding lesson: Auditable traces preserve operator accountability and support diagnosis.

  def logAuditEvent(logPath: Path, event: AuditEvent): Unit =
    // TODO — Module 03, Exercise 4. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 4 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 5: Apply tool decorators
  // ---------------------------------------------------------------------------

  // Implementation: Wire ordered pre/post hooks, rewrites, denials, and feedback through the agent loop above.
  // Failure mode: Prompt instructions can be bypassed at the exact moment an action is attempted.
  // Agentic coding lesson: Enforce critical policy deterministically at tool boundaries.
  // Typed ToolDecorator values are applied directly in handleTurn above.

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 6: Build an exit-gate decorator
  // ---------------------------------------------------------------------------

  // Implementation: Run independent commands before accepting message_user and feed failures back.
  // Failure mode: The agent declares completion while required tests or checks still fail.
  // Agentic coding lesson: Completion is a claim—prompts advise, but executable gates enforce.
  // See exitGate above; the CLI wires it into Agent.handleTurn as a typed decorator.

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 7: Create and restore checkpoints
  // ---------------------------------------------------------------------------

  // Implementation: Copy checkpointed files back over current files; later-created files remain.
  // Failure mode: Treating this partial restore as exact rollback leaves unwanted files behind.
  // Agentic coding lesson: Recovery limits risk only when operators verify its actual semantics.

  private val checkpointCounter = new AtomicInteger(0)

  def createCheckpoint(workDir: Path, checkpointsDir: Path): CheckpointInfo =
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")

  private def copyDirAll(src: Path, dst: Path): Unit = {
    Files.createDirectories(dst)
    Using.resource(Files.list(src)) { stream =>
      stream.iterator().asScala.foreach { entry =>
        val dest = dst.resolve(entry.getFileName)
        if (Files.isDirectory(entry)) copyDirAll(entry, dest)
        else Files.copy(entry, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  def restoreCheckpoint(workDir: Path, checkpointId: String, checkpointsDir: Path): Boolean =
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")

  def listCheckpoints(checkpointsDir: Path): Vector[CheckpointInfo] =
    // TODO — Module 03, Exercise 7. See 03-guardrails-and-safety/exercises.md for the contract.
    sys.error("Module 03, Exercise 7 — see 03-guardrails-and-safety/exercises.md")

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 8: Sandbox in Lima VM
  // ---------------------------------------------------------------------------

  // Implementation: Adapt command execution to a separately provisioned Lima VM and return its result.
  // Failure mode: A wrapper is mistaken for isolation despite missing provisioning or platform support.
  // Agentic coding lesson: Real isolation is an operational boundary, not a stronger prompt warning.

  def executeSandboxedShell(command: String, workDir: Path): ToolResult =
    // TODO — Module 03, Exercise 8. Preserve this forwarding overload while implementing the adapter below.
    executeSandboxedShell(command, workDir, "limactl")

  private[workshop] def executeSandboxedShell(
    command: String,
    workDir: Path,
    limactl: String
  ): ToolResult =
    // TODO — Module 03, Exercise 8. See 03-guardrails-and-safety/exercises.md for the Lima adapter contract.
    sys.error("Module 03, Exercise 8 — see 03-guardrails-and-safety/exercises.md")
}
