package workshop.agent

import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

import java.nio.file.Files
import scala.util.Try

/** An agent is the thing you talk to: it holds the conversation that has happened so far, the
  * configuration it was started with, the tools it can invoke, and a chat provider it uses to
  * think.
  *
  * Every interaction returns a new `Agent`. State transitions are pure (modulo the provider and
  * tools, which are inherently side-effecting).
  */
final case class Agent(
  state: AgentState,
  config: AgentConfig,
  tools: Vector[Tool],
  provider: ChatProvider
) {

  /** Process one parsed user input. Returns the next agent state and the step's [[StepOutcome]].
    * The split between parsing (`UserCommand.parse`) and processing (this method) keeps the agent
    * free of input-string parsing — see D21.
    */
  def step(
    command: UserCommand,
    terminal: TerminalOutput = TerminalOutput.silent
  ): (Agent, StepOutcome) =
    command match {
      case UserCommand.Quit =>
        (this, StepOutcome.Quit)

      case UserCommand.Compact =>
        val beforeTurns = state.conversation.turns.length + 1
        val compacted   = compact
        val afterTurns  = compacted.state.conversation.turns.length + 1
        (compacted, StepOutcome.Compacted(beforeTurns, afterTurns))

      case UserCommand.Slash(name, args) =>
        Exercises.discoverCommands(config.commandsDir).find(_.name == name) match {
          case Some(cmd) =>
            val prompt = Exercises.executeCommand(cmd, args)
            val response =
              provider.complete(Vector(Message.UserMessage(Content.TextContent(prompt))))
            (this, StepOutcome.Custom(response))
          case None =>
            (this, StepOutcome.Unknown(name))
        }

      case UserCommand.Prompt(text) =>
        val (next, turn) = handleTurn(text, terminal)
        (next, StepOutcome.Turn(turn.content, turn.toolCalls))
    }

  /** Drive one LLM turn through the agent's tools. Returns the next agent state and the turn's
    * result.
    */
  def handleTurn(
    input: String,
    terminal: TerminalOutput = TerminalOutput.silent
  ): (Agent, TurnResult) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val decorators =
      if (config.exitGates.isEmpty) Vector.empty[ToolDecorator]
      else Vector(Exercises.exitGate(config.exitGates))
    Exercises.handleTurn(this, config.augmentInput(input), terminal, decorators)
  }

  /** Append a turn to the conversation; refreshes context usage. Tools/decorators that need to
    * inspect or extend the conversation through an `Agent` go through this.
    */
  def append(turn: TurnMessage): Agent =
    withConversation(state.conversation :+ turn)

  /** Force-compact the conversation now. Uses the provider to summarise the transcript and replaces
    * the agent's conversation with the summary. See `maybeAutoCompact` for the common-case
    * predicate-and-act composite.
    */
  def compact: Agent = {
    val transcript = Exercises.formatConversationForCompaction(state.conversation)
    val pastSummaries = config.historyDir
      .map(dir =>
        Exercises.loadPastSessionSummaries(
          dir,
          state.sessionId.value,
          provider,
          config.sessionSummaryMaxChars
        )
      )
      .getOrElse("")
    val pastSection =
      if (pastSummaries.isEmpty) "" else s"Previous sessions:\n$pastSummaries\n\n"
    val compactPrompt = loadCompactionPrompt()
      .replace("{{CONVERSATION}}", transcript)
      .replace("{{PAST_SESSIONS}}", pastSection)
    val summary = provider.complete(Vector(Message.UserMessage(Content.TextContent(compactPrompt))))
    val compacted = Exercises.applyCompaction(state.conversation, summary)
    withConversation(compacted)
  }

  /** True iff the agent's current conversation exceeds `config.maxContextChars`. */
  def shouldAutoCompact: Boolean = contextUsage.overBudget

  /** Common composite: if `shouldAutoCompact`, `compact`; otherwise return self. */
  def maybeAutoCompact: Agent =
    if (shouldAutoCompact) {
      logVerbose("Auto-compacted conversation (context budget exceeded)")
      compact
    } else this

  /** Log a verbose-only diagnostic to stderr. No-op when `config.verbose` is false. Centralises the
    * verbose-flag check so call sites read as `agent.logVerbose("…")` instead of scattering
    * `if (config.verbose) Console.err.println(…)` everywhere.
    */
  def logVerbose(message: => String): Unit =
    if (config.verbose) Console.err.println(message)

  /** Persist the current session to `config.historyDir` if one is configured. */
  def saveSession(): Unit =
    config.historyDir.foreach(dir =>
      Exercises.saveSession(dir, state.sessionId.value, state.conversation)
    )

  /** Extract durable memories from the last exchange. Best-effort; swallows failures. */
  def extractMemories(userMessage: String, agentMessage: String): Unit =
    Try(
      Exercises.extractMemories(
        provider,
        userMessage,
        agentMessage,
        config.workDir,
        Some(config.memoryFile)
      )
    )

  /** Layer persistent context (instructions, skills, memories) onto the agent's system prompt. */
  def withInjectedContext: Agent = {
    val instructions = Exercises.loadInstructions(
      config.workDir,
      config.instructions.map(_.toString),
      config.instructionsFileName
    )
    val skills   = Exercises.discoverSkills(config.skillsDir, config.skillFileName)
    val memories = Exercises.loadMemories(config.workDir, Some(config.memoryFile))

    val instructionsSection =
      instructions.map(text => s"\n\n## Project Instructions\n\n$text").getOrElse("")
    val skillsSection =
      if (skills.isEmpty) ""
      else
        "\n\n## Available Skills\n\n" + skills.map(s => s"- ${s.name}: ${s.description}\n").mkString
    val memoriesSection =
      memories
        .map(text => s"\n\n## Things I remember from previous conversations\n\n$text")
        .getOrElse("")
    val extras = instructionsSection + skillsSection + memoriesSection

    if (extras.isEmpty) this
    else
      copy(state =
        AgentState.initial(
          systemPrompt = state.conversation.systemPrompt + extras,
          sessionId = state.sessionId
        )
      )
  }

  // ---------------------------------------------------------------------------
  // Drivers — state-threading loops over `this`
  // ---------------------------------------------------------------------------

  /** Drive a sequence of user inputs through this agent. The loop shape —
    * `parse → step → render → afterStep → continue?` — is the same for every mode; modes vary along
    * independent axes:
    *   - `reader`: where inputs come from ([[LineReader.Jline]] for the interactive REPL;
    *     [[LineReader.fromIterator]] for file/protocol).
    *   - `terminal`: the [[TerminalOutput]] threaded through `step` for the agent's in-turn
    *     display. Interactive mode passes the real terminal; protocol/file modes pass
    *     [[TerminalOutput.silent]].
    *   - [[Channel]]: how outcomes are rendered to the user (JSON via [[IoFormat]] for protocol;
    *     per-outcome terminal calls for interactive).
    *   - [[StepPolicy]]: when to persist and auto-compact after a step.
    *
    * Stops on `StepOutcome.Quit` or when the reader signals `EndOfInput`.
    */
  def drive(
    reader: LineReader,
    terminal: TerminalOutput,
    channel: Channel,
    policy: StepPolicy
  ): Agent =
    reader.run(terminal, this) { (agent, input) =>
      val command         = UserCommand.parse(input)
      val (next, outcome) = agent.step(command, terminal)
      channel.render(outcome)
      val advanced = policy.afterStep(next, input, outcome)
      (advanced, outcome != StepOutcome.Quit)
    }

  /** Drive a sequence of prompts through this agent (used by FileMode). */
  def runFileMode(prompts: Vector[String]): Agent =
    drive(
      LineReader.fromIterator(prompts.iterator),
      TerminalOutput.silent,
      Channel.Protocol(config.outputFormat),
      StepPolicy.PersistEveryStep
    )

  /** Drive an iterator of stdin lines through this agent (protocol mode). */
  def runProtocol(lines: Iterator[String]): Agent =
    drive(
      LineReader.fromIterator(lines.map(config.parseInput)),
      TerminalOutput.silent,
      Channel.Protocol(config.outputFormat),
      StepPolicy.PersistEveryStep
    )

  /** Drive an interactive REPL through this agent. */
  def runInteractive(terminal: TerminalOutput, reader: LineReader): Agent =
    drive(
      reader,
      terminal,
      Channel.Terminal(terminal),
      StepPolicy.PersistTurnOnly
    )

  /** Post-step side effects: persist session, extract memories, write output file. */
  def afterStepSideEffects(userMessage: String, outcome: StepOutcome): Unit = {
    saveSession()
    outcome match {
      case StepOutcome.Turn(content, _) =>
        extractMemories(userMessage, content)
        config.writeOutputFile(content)
      case _ => ()
    }
  }

  private def loadCompactionPrompt(): String =
    Files.readString(config.compactionPrompt)

  /** The current context-usage. Deterministic function of `state.conversation`, `tools`, and
    * `config.maxContextChars` — recomputed on read, never stored.
    */
  def contextUsage: ContextUsage =
    ContextUsage.measure(
      state.conversation.systemPrompt,
      state.conversation,
      tools,
      config.maxContextChars
    )

  private def withConversation(c: Conversation): Agent =
    copy(state = state.copy(conversation = c))
}

object Agent {

  /** Construct a fresh agent: builds the system prompt from config and tools, enables the
    * configured subset of `registry`, and stamps `sessionId` (default: a fresh UUID).
    */
  def initial(
    config: AgentConfig,
    registry: ToolRegistry,
    provider: ChatProvider,
    interactive: Boolean = false,
    sessionId: SessionId = SessionId.fresh
  ): Agent = {
    val enabled   = config.enabledTools(registry.tools)
    val sysPrompt = Exercises.buildSystemPrompt(config, enabled, interactive)
    Agent(
      state = AgentState.initial(sysPrompt, sessionId),
      config = config,
      tools = enabled,
      provider = provider
    )
  }

  /** Restore prior turns while rebuilding the system prompt from the current config. */
  def resumed(
    config: AgentConfig,
    registry: ToolRegistry,
    provider: ChatProvider,
    conversation: Conversation,
    sessionId: SessionId,
    interactive: Boolean = false
  ): Agent = {
    val enabled = config.enabledTools(registry.tools)
    val current = Conversation(
      Exercises.buildSystemPrompt(config, enabled, interactive),
      conversation.turns
    )
    Agent(AgentState(current, sessionId), config, enabled, provider)
  }

}
