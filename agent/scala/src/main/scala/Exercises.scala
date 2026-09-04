package workshop.agent

import sttp.client4.{asStringAlways, basicRequest, UriContext}
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}
import zio.blocks.schema.json.Json

import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
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

  /** Fallback used when `config.systemPrompt` cannot be read (for example when the agent is started
    * from a directory where `../system-prompt.txt` does not resolve). Mirrors the shipped template
    * so the protocol contract survives a missing file instead of crashing the agent.
    */
  private val FallbackPromptTemplate =
    """You are a coding agent working inside a multi-turn loop. You take one action per
      |response, the system runs it, and you see the real result on your next turn.
      |
      |To use a tool, respond with a JSON object wrapped in <tool_call> tags:
      |
      |<tool_call>
      |{"name": "tool_name", "arguments": {"param": "value"}}
      |</tool_call>
      |
      |Emit exactly one tool call per response and stop after the closing tag. Never predict
      |a tool's output — you have not seen it until the next iteration. When the task is
      |complete, call the message_user tool.
      |
      |{{TTY_INFO}}
      |
      |Available tools:
      |
      |{{TOOL_DESCRIPTIONS}}
      |""".stripMargin

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
  ): String = {
    val template     = readFileIfPresent(config.systemPrompt).getOrElse(FallbackPromptTemplate)
    val descriptions = enabledTools.map(_.formatForContext).mkString("\n\n")
    val rolePrompt = config.rolePrompt.flatMap(readFileIfPresent).map(r => s"\n\n$r").getOrElse("")

    template
      .replace("{{TOOL_DESCRIPTIONS}}", descriptions)
      .replace("{{TTY_INFO}}", if (interactive) TtyHint else "") + rolePrompt
  }

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
    ToolCallRegex
      .findAllMatchIn(text)
      .foldLeft((Vector.empty[RawToolCall], Vector.empty[String])) { case ((calls, errors), m) =>
        val payload = m.group(1)
        Json.parse(payload) match {
          case Left(err) =>
            (
              calls,
              errors :+ s"Malformed tool call JSON (${err.message}): ${preview(payload, 100)}"
            )
          case Right(json) =>
            parseToolCallPayload(json) match {
              case Right(call)  => (calls :+ call, errors)
              case Left(reason) => (calls, errors :+ reason)
            }
        }
      }

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

  /** Injected when the model answers without calling any tool. Keeps the loop moving instead of
    * letting a chatty response silently end the turn.
    */
  private val Nudge = "You must call a tool. Use message_user to deliver your final response."

  private val ToolCallClose = "</tool_call>"

  /** Tools that end the turn: their result is the answer, not feedback for another iteration. */
  private val TerminatingTools = Set(ToolName.MessageUser, ToolName.AskUser)

  /** Upper bound on a single decorator hook. Sits just above the exit gate's own 120s cap so a hung
    * gate surfaces as its own timeout message rather than as an Await failure.
    */
  private val DecoratorTimeout = scala.concurrent.duration.Duration(130, TimeUnit.SECONDS)

  /** Assistant messages enter history truncated at the first `</tool_call>`: keeping the tail would
    * teach the model that multi-call responses (and any hallucinated results after them) are
    * accepted.
    */
  private def truncateAtFirstToolCall(text: String): String = {
    val idx = text.indexOf(ToolCallClose)
    if (idx < 0) text else text.substring(0, idx + ToolCallClose.length)
  }

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
  ): (Agent, TurnResult) = {

    @annotation.tailrec
    def loop(current: Agent, iteration: Int, invoked: Vector[ToolName]): (Agent, TurnResult) =
      if (iteration >= current.config.maxIterations) {
        terminal.error("Max iterations reached.")
        (current, TurnResult("Max iterations reached.", invoked, current.state.conversation))
      } else
        iterate(current, invoked, terminal, decorators) match {
          case Left((next, nextInvoked)) => loop(next, iteration + 1, nextInvoked)
          case Right(finished)           => finished
        }

    loop(agent.append(TurnMessage.user(userContent)), 0, Vector.empty)
  }

  /** One iteration: ask the model, then either continue (Left) with the updated agent and tool
    * ledger, or finish the turn (Right).
    */
  private def iterate(
    agent: Agent,
    invoked: Vector[ToolName],
    terminal: TerminalOutput,
    decorators: Vector[ToolDecorator]
  ): Either[(Agent, Vector[ToolName]), (Agent, TurnResult)] = {
    terminal.spinnerStart()
    val response =
      try agent.provider.complete(agent.state.conversation.toMessages)
      finally terminal.spinnerStop()

    agent.logVerbose(s"LLM: ${preview(response, 400)}")
    extractThinking(response).foreach(terminal.thinking)

    val (calls, errors) = parseToolCalls(response)
    val recorded        = agent.append(TurnMessage.assistant(truncateAtFirstToolCall(response)))

    calls.headOption match {
      case Some(call) => executeCall(recorded, call, invoked, terminal, decorators)
      case None if errors.nonEmpty =>
        val detail = errors.mkString("; ")
        terminal.error(detail)
        Left(
          (
            feedback(
              recorded,
              s"Tool call parse error: $detail\nPlease fix the JSON and try again."
            ),
            invoked
          )
        )
      case None =>
        Left((feedback(recorded, Nudge), invoked))
    }
  }

  /** Execute one parsed tool call through the decorator pipeline. */
  private def executeCall(
    agent: Agent,
    raw: RawToolCall,
    invoked: Vector[ToolName],
    terminal: TerminalOutput,
    decorators: Vector[ToolDecorator]
  ): Either[(Agent, Vector[ToolName]), (Agent, TurnResult)] =
    beforePass(decorators, raw, agent) match {
      case Left(reason) =>
        // Denied before execution: the tool never runs, and the reason becomes the model's
        // feedback for the next iteration.
        terminal.error(reason)
        Left((feedback(agent, s"Tool ${raw.name} denied: $reason"), invoked))

      case Right(effective) =>
        ToolCall.fromRaw(effective, ToolRegistry(agent.tools)) match {
          case Left(err) =>
            terminal.error(err)
            Left(
              (
                feedback(agent, s"Tool call parse error: $err\nPlease fix the JSON and try again."),
                invoked
              )
            )

          case Right(call) if TerminatingTools.contains(call.name) =>
            val result = call.execute(agent)
            afterPass(decorators, effective, result, agent) match {
              case Left(reason) =>
                // A gate refused the termination: feed the reason back and keep working.
                terminal.error(reason)
                Left((feedback(agent, s"Tool ${call.name.wire} denied: $reason"), invoked))
              case Right(allowed) =>
                val content = allowed.stringify
                terminal.answer(content)
                Right((agent, TurnResult(content, invoked :+ call.name, agent.state.conversation)))
            }

          case Right(call) =>
            terminal.toolCall(call)
            val executed = call.execute(agent).truncated
            val result = afterPass(decorators, effective, executed, agent).fold(
              ToolResult.Failure(_),
              identity
            )
            terminal.toolResult(result)
            Left(
              (
                feedback(agent, s"Tool ${call.name.wire} returned:\n${result.stringify}"),
                invoked :+ call.name
              )
            )
        }
    }

  private def feedback(agent: Agent, text: String): Agent =
    agent.append(TurnMessage.user(text))

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 5 wiring: decorator passes around tool execution
  // ---------------------------------------------------------------------------

  /** Run each applicable decorator's `before` hook in registration order, threading any rewritten
    * invocation into the next decorator. The first `Deny` short-circuits the chain.
    */
  private def beforePass(
    decorators: Vector[ToolDecorator],
    call: RawToolCall,
    agent: Agent
  ): Either[String, RawToolCall] = {
    @annotation.tailrec
    def go(remaining: List[ToolDecorator], current: RawToolCall): Either[String, RawToolCall] =
      remaining match {
        case Nil => Right(current)
        case decorator :: rest =>
          if (!decorator.hasBefore || !decorator.appliesTo(current, agent)) go(rest, current)
          else
            scala.concurrent.Await
              .result(decorator.before(current, agent), DecoratorTimeout) match {
              case DecoratorOutcome.Allow(rewritten) => go(rest, rewritten)
              case DecoratorOutcome.Deny(reason)     => Left(reason)
            }
      }

    go(decorators.toList, call)
  }

  /** Run each applicable decorator's `after` hook. The first `Deny` short-circuits and replaces the
    * tool's output, so a rejected result never reaches the model.
    */
  private def afterPass(
    decorators: Vector[ToolDecorator],
    call: RawToolCall,
    result: ToolResult,
    agent: Agent
  ): Either[String, ToolResult] = {
    @annotation.tailrec
    def go(remaining: List[ToolDecorator]): Either[String, ToolResult] =
      remaining match {
        case Nil => Right(result)
        case decorator :: rest =>
          if (!decorator.hasAfter || !decorator.appliesTo(call, agent)) go(rest)
          else
            scala.concurrent.Await
              .result(decorator.after(call, result, agent), DecoratorTimeout) match {
              case DecoratorOutcome.Allow(_)     => go(rest)
              case DecoratorOutcome.Deny(reason) => Left(reason)
            }
      }

    go(decorators.toList)
  }

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 4: Implement read_file
  // ---------------------------------------------------------------------------

  // Implementation: Validate a path and return file contents or a visible read failure.
  // Failure mode: Without readable evidence, the model can invent facts about the repository.
  // Agentic coding lesson: Require agents to inspect source evidence before accepting their claims.

  // Paths used exactly as the params supply — workDir resolution happens at the
  // ToolCall.execute boundary via `Tool.ReadFile.Params.withResolvedPath`.
  def executeReadFile(params: Tool.ReadFile.Params): ToolResult =
    Try(Files.readString(Path.of(params.path), StandardCharsets.UTF_8)).fold(
      err => ToolResult.Failure(s"reading file: ${err.getMessage}"),
      contents => ToolResult.Success(contents)
    )

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 5: Implement shell
  // ---------------------------------------------------------------------------

  // Implementation: Execute commands with a cwd, timeout, bounded streams, and explicit exit evidence.
  // Failure mode: Missing stderr, status, or output bounds turns failed checks into ambiguous feedback.
  // Agentic coding lesson: Shell commands make claims observable only when all evidence returns to the loop.

  def executeShell(params: Tool.Shell.Params, workDir: Path): ToolResult =
    runShell(params.command, workDir)
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

  /** The starting point from the exercise: a prompt that states the envelope but none of the
    * defensive constraints. Kept as a fixture so the before/after comparison against the shipped
    * `agent/system-prompt.txt` stays explicit and testable.
    */
  val NaiveSystemPrompt: String =
    """You are a coding agent. You can use tools to interact with the filesystem
      |and run shell commands.
      |
      |To use a tool, respond with a JSON object wrapped in <tool_call> tags:
      |
      |<tool_call>
      |{"name": "tool_name", "arguments": {"param": "value"}}
      |</tool_call>
      |
      |You may include reasoning or commentary before or after the tool call.
      |When you have completed the task, use the message_user tool.
      |""".stripMargin

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

  private val SudokuDigits = (1 to 9).toVector

  private def sudokuGroupIsValid(values: Vector[Int]): Boolean = values.sorted == SudokuDigits

  private def renderGroup(values: Vector[Int]): String = values.mkString("[", ", ", "]")

  def verifySudoku(grid: Array[Array[Int]]): Vector[String] =
    if (grid.length != 9 || grid.exists(_.length != 9))
      Vector(s"Grid must be 9x9, got ${grid.length} rows")
    else {
      val rows = (0 until 9).toVector.flatMap { r =>
        val values = grid(r).toVector
        Option.when(!sudokuGroupIsValid(values))(s"Row ${r + 1}: ${renderGroup(values)}")
      }
      val cols = (0 until 9).toVector.flatMap { c =>
        val values = (0 until 9).toVector.map(r => grid(r)(c))
        Option.when(!sudokuGroupIsValid(values))(s"Col ${c + 1}: ${renderGroup(values)}")
      }
      val boxes = (0 until 3).toVector.flatMap { boxRow =>
        (0 until 3).toVector.flatMap { boxCol =>
          val values = for {
            r <- (0 until 3).toVector
            c <- (0 until 3).toVector
          } yield grid(boxRow * 3 + r)(boxCol * 3 + c)
          Option.when(!sudokuGroupIsValid(values))(
            s"Box (${boxRow + 1},${boxCol + 1}): ${renderGroup(values)}"
          )
        }
      }
      rows ++ cols ++ boxes
    }

  def parseSudokuGrid(text: String): Option[Array[Array[Int]]] = {
    val rows = text.linesIterator.toVector.flatMap { line =>
      val cells = line.trim.split("\\s+").toVector.filter(_.nonEmpty)
      Option.when(cells.length == 9 && cells.forall(c => c.length == 1 && c.charAt(0).isDigit))(
        cells.map(_.toInt).toArray
      )
    }
    Option.when(rows.length >= 9)(rows.take(9).toArray)
  }

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 10: The fabrication test
  // ---------------------------------------------------------------------------

  // Implementation: Judge whether a response answers the question or reports insufficient specification.
  // Failure mode: A model may answer an underspecified question confidently; this judge cannot establish truth.
  // Agentic coding lesson: Test fabrication pressure with controlled ambiguity, then verify factual claims with tools.

  def evaluateResponse(provider: ChatProvider, agentResponse: String): String = {
    val prompt =
      s"""You are evaluating an AI agent's response to a question it could not have grounded in real data.
         |
         |Did the agent (a) provide a specific answer with concrete data, or (b) refuse to answer
         |because the question was underspecified or the data was unavailable?
         |
         |Agent response:
         |$agentResponse
         |
         |Answer with exactly one word: SPECIFIED if the agent supplied concrete data,
         |UNSPECIFIED if it refused or reported that it could not know.""".stripMargin

    val verdict = provider
      .complete(Vector(Message.UserMessage(Content.TextContent(prompt))))
      .trim
      .toUpperCase

    // UNSPECIFIED contains SPECIFIED as a substring, so the refusal verdict must be tested first.
    if (verdict.contains("UNSPECIFIED")) "UNSPECIFIED"
    else if (verdict.contains("SPECIFIED")) "SPECIFIED"
    else "UNSPECIFIED"
  }

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 11: Implement edit_file
  // ---------------------------------------------------------------------------

  // Implementation: Edit only a line range whose exact old text still matches.
  // Failure mode: Broad or stale replacements overwrite unintended code and obscure review.
  // Agentic coding lesson: Guarded, range-scoped edits reduce blast radius and preserve ownership.

  // Line range is 1-based and inclusive. Only the first occurrence is replaced.
  // File stays unchanged if range is invalid or old_text is absent.
  def executeEditFile(params: Tool.EditFile.Params): ToolResult = {
    val path = Path.of(params.path)
    Try(Files.readString(path, StandardCharsets.UTF_8)).fold(
      err => ToolResult.Failure(s"reading file: ${err.getMessage}"),
      content => {
        // A trailing newline is a file property, not a line, so it is stripped before splitting
        // and restored on write. Without this, every edit would grow or drop a blank last line.
        val trailingNewline = content.endsWith("\n")
        val body            = if (trailingNewline) content.dropRight(1) else content
        val lines           = body.split("\n", -1).toVector

        if (params.line_start < 1)
          ToolResult.Failure(s"line_start ${params.line_start} must be at least 1")
        else if (params.line_end < params.line_start)
          ToolResult.Failure(
            s"line_end ${params.line_end} is before line_start ${params.line_start}"
          )
        else if (params.line_start > lines.length)
          ToolResult.Failure(
            s"line_start ${params.line_start} is out of range for file with ${lines.length} lines"
          )
        else if (params.line_end > lines.length)
          ToolResult.Failure(
            s"line_end ${params.line_end} is out of range for file with ${lines.length} lines"
          )
        else {
          val rangeText = lines.slice(params.line_start - 1, params.line_end).mkString("\n")
          val index     = rangeText.indexOf(params.old_text)
          if (index < 0)
            ToolResult.Failure(
              s"old_text not found in lines ${params.line_start}-${params.line_end} of ${params.path}"
            )
          else {
            val replaced =
              rangeText.substring(0, index) +
                params.new_text +
                rangeText.substring(index + params.old_text.length)
            val updated =
              lines.take(params.line_start - 1) ++
                replaced.split("\n", -1).toVector ++
                lines.drop(params.line_end)
            val output = updated.mkString("\n") + (if (trailingNewline) "\n" else "")

            Try(Files.writeString(path, output, StandardCharsets.UTF_8)).fold(
              err => ToolResult.Failure(s"writing file: ${err.getMessage}"),
              _ =>
                ToolResult.Success(
                  s"Successfully edited ${params.path} (lines ${params.line_start}-${params.line_end})"
                )
            )
          }
        }
      }
    )
  }

  // ---------------------------------------------------------------------------
  // Module 01, Exercise 12: Implement the list_files tool
  // ---------------------------------------------------------------------------

  // Implementation: Return deterministic repository paths up to a caller-selected depth.
  // Failure mode: Editing starts from an invented or incomplete understanding of project scope.
  // Agentic coding lesson: Repository discovery should ground planning before modification begins.

  def executeListFiles(params: Tool.ListFiles.Params): ToolResult = {
    val root = Path.of(params.path)
    if (!Files.isDirectory(root))
      ToolResult.Failure(s"listing files: not a directory: ${params.path}")
    else
      Try {
        def walk(dir: Path, depth: Int): Vector[String] =
          if (depth > params.max_depth) Vector.empty
          else {
            val entries = Using.resource(Files.list(dir))(_.iterator().asScala.toVector)
            entries.flatMap { entry =>
              val relative = root.relativize(entry).toString.replace('\\', '/')
              // Symlinks are listed but never followed — a link loop would otherwise hang the walk.
              if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                s"$relative/" +: walk(entry, depth + 1)
              else Vector(relative)
            }
          }

        walk(root, 1).sorted.mkString("\n")
      }.fold(
        err => ToolResult.Failure(s"listing files: ${err.getMessage}"),
        listing => ToolResult.Success(listing)
      )
  }

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
    instructionsPath match {
      case Some(explicit) =>
        val candidate = Path.of(explicit)
        readFileIfPresent(if (candidate.isAbsolute) candidate else workDir.resolve(candidate))

      case None =>
        val start = workDir.toAbsolutePath.normalize
        // Root first, nearest directory last: the closest AGENTS.md gets the final say.
        val ancestors = Iterator
          .iterate(Option(start))(_.flatMap(dir => Option(dir.getParent)))
          .takeWhile(_.isDefined)
          .flatten
          .toVector
          .reverse

        val found = ancestors.flatMap(dir => readFileIfPresent(dir.resolve(instructionsFileName)))
        Option.when(found.nonEmpty)(found.mkString("\n\n"))
    }

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 2: Discover and load skills
  // ---------------------------------------------------------------------------

  // Implementation: Discover skill metadata eagerly and load full specialist guidance on demand.
  // Failure mode: Loading everything crowds context; loading nothing omits needed expertise.
  // Agentic coding lesson: Selective context improves decisions without permanently consuming the window.

  /** List a directory's entries sorted by path, or nothing when it is not a directory. Discovery
    * order must not depend on filesystem iteration order.
    */
  private def entriesSorted(dir: Path): Vector[Path] =
    if (!Files.isDirectory(dir)) Vector.empty
    else
      Try(Using.resource(Files.list(dir))(_.iterator().asScala.toVector))
        .getOrElse(Vector.empty)
        .sortBy(_.toString)

  def discoverSkills(
    skillsDir: Option[Path],
    skillFileName: String = AgentConfig.default.skillFileName
  ): Vector[SkillInfo] =
    skillsDir.toVector.flatMap { dir =>
      entriesSorted(dir)
        .filter(Files.isDirectory(_))
        .map(_.resolve(skillFileName))
        .filter(Files.isRegularFile(_))
        .flatMap { skillPath =>
          readFileIfPresent(skillPath).map { content =>
            val metadata = parseFrontmatter(content)
            val fallback =
              Option(skillPath.getParent).flatMap(p => Option(p.getFileName)).map(_.toString)
            SkillInfo(
              name = metadata.getOrElse("name", fallback.getOrElse(skillFileName)),
              description = metadata.getOrElse("description", ""),
              path = skillPath
            )
          }
        }
    }

  def loadSkillContent(skillPath: Path): String =
    readFileIfPresent(skillPath).getOrElse("")

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 3: Discover and execute command prompts
  // ---------------------------------------------------------------------------

  // Implementation: Discover prompt templates, strip metadata, and substitute invocation arguments.
  // Failure mode: Rewritten ad hoc prompts drift and become difficult to inspect or reproduce.
  // Agentic coding lesson: Commands turn recurring delegation patterns into explicit workflows.

  def discoverCommands(commandsDir: Option[Path]): Vector[CommandPrompt] =
    commandsDir.toVector.flatMap { dir =>
      entriesSorted(dir)
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".md"))
        .flatMap { path =>
          readFileIfPresent(path).map { content =>
            val metadata = parseFrontmatter(content)
            CommandPrompt(
              name = path.getFileName.toString.stripSuffix(".md"),
              description = metadata.getOrElse("description", ""),
              argumentHint = metadata.get("argument-hint").map(_.trim).filter(_.nonEmpty),
              path = path
            )
          }
        }
    }

  def executeCommand(command: CommandPrompt, args: String): String =
    readFileIfPresent(command.path)
      .map(content => stripFrontmatter(content).replace("$ARGUMENTS", args))
      .getOrElse("")

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
    ToolDecorator(
      name = "exit-gate",
      appliesTo = (call, _) => commands.nonEmpty && call.name == ToolName.MessageUser.wire,
      after = Some { (call, _, agent) =>
        scala.concurrent.Future {
          // foldLeft + orElse gives short-circuit semantics: once one gate fails, the
          // by-name argument for every later gate is never evaluated, so it never runs.
          val failure = commands.foldLeft(Option.empty[String]) { (firstFailure, cmd) =>
            firstFailure.orElse {
              val gate = runExitGate(cmd, agent.config.workDir)
              Option.when(gate.code != 0) {
                val output = truncateGateOutput(
                  Seq(gate.stdout, gate.stderr).map(_.trim).filter(_.nonEmpty).mkString("\n"),
                  ExitGateMaxOutputBytes
                )
                s"""Exit-gate failed: `$cmd` exited ${gate.code}.
                   |$output
                   |
                   |You cannot finish this task until every exit gate passes. Fix the cause and
                   |continue working — do not call message_user again until the checks succeed.""".stripMargin
              }
            }
          }

          failure match {
            case Some(reason) => DecoratorOutcome.Deny(reason)
            case None         => DecoratorOutcome.Allow(call)
          }
        }
      }
    )

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
  ): ContextUsage = {
    val system       = systemPrompt.length
    val conversional = conversation.turns.map(_.text.length).sum
    val toolChars    = tools.map(_.formatForContext.length).sum
    val total        = system + conversional + toolChars

    ContextUsage(
      system = system,
      conversation = conversional,
      tools = toolChars,
      total = total,
      limit = maxChars,
      // A zero (or negative) budget has no meaningful percentage — report None rather than divide.
      percentage = maxChars.filter(_ > 0).map(limit => total.toDouble / limit.toDouble)
    )
  }

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 5: Compact conversation history
  // ---------------------------------------------------------------------------

  // Implementation: Format history for summarization and replace it with a coherent summary turn pair.
  // Failure mode: Naïve truncation loses active constraints, decisions, and task state.
  // Agentic coding lesson: Compaction trades detail for capacity, so preserve operational state.

  /** Synthetic assistant turn that closes the post-compaction user/assistant pair. Shaped as a real
    * `message_user` envelope so the restored history matches what the model has been trained on by
    * every prior turn.
    */
  private val CompactionAcknowledgement =
    """<tool_call>{"name":"message_user","arguments":{"message":"Context loaded. How can I help?"}}</tool_call>"""

  // Strips system messages, emits plain role:content transcript. The compaction
  // prompt is written against this exact shape.
  def formatConversationForCompaction(conversation: Conversation): String =
    conversation.turns.map(turn => s"${turn.role}: ${turn.text}").mkString("\n\n")
  def applyCompaction(
    conversation: Conversation,
    summary: String
  ): Conversation =
    Conversation(
      systemPrompt = conversation.systemPrompt,
      turns = Vector(
        TurnMessage.user(s"[Context from previous conversation]\n$summary"),
        TurnMessage.assistant(CompactionAcknowledgement)
      )
    )

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 6: Auto-compact on budget
  // ---------------------------------------------------------------------------

  // Implementation: Trigger compaction only when a configured context budget is exceeded.
  // Failure mode: Late compaction degrades behavior; eager compaction destroys useful detail.
  // Agentic coding lesson: Context budgets need deliberate thresholds rather than reactive cleanup.

  def shouldAutoCompact(usage: ContextUsage): Boolean = usage.overBudget

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 7: Save and resume conversation
  // ---------------------------------------------------------------------------

  // Implementation: Persist and load the stored prompt and turns faithfully; callers choose what to trust.
  // Failure mode: Corrupt storage loses continuity, while trusting stale prompts preserves old authority.
  // Agentic coding lesson: Separate persistence fidelity from resume policy—restore history, rebuild authority.

  def saveSession(historyDir: Path, sessionId: String, conversation: Conversation): Unit = {
    val stored = StoredSession(
      timestamp = Instant.now().toString,
      workDir = System.getProperty("user.dir"),
      model = AgentConfig.default.model,
      systemPrompt = conversation.systemPrompt,
      turns = conversation.turns.map(turn => StoredTurn(turn.role, turn.text))
    )
    val _ = Try {
      Files.createDirectories(historyDir)
      Files.writeString(
        StoredSession.pathFor(historyDir, sessionId),
        stored.encode,
        StandardCharsets.UTF_8
      )
    }
  }

  def loadSession(historyDir: Path, sessionId: String): Option[Conversation] =
    readFileIfPresent(StoredSession.pathFor(historyDir, sessionId))
      .flatMap(StoredSession.decode)
      .map { stored =>
        Conversation(
          systemPrompt = stored.systemPrompt,
          turns = stored.turns.map { turn =>
            if (turn.role == "assistant") TurnMessage.assistant(turn.content)
            else TurnMessage.user(turn.content)
          }
        )
      }

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
    entriesSorted(historyDir)
      .filter(path => path.getFileName.toString.endsWith(".json"))
      .filter(path => path.getFileName.toString.stripSuffix(".json") != currentSessionId)
      .flatMap { path =>
        readFileIfPresent(path).flatMap(StoredSession.decode).flatMap { session =>
          // A session with no user turn has no topic to report — skip it rather than
          // emitting a bare timestamp line.
          session.turns.find(_.role == "user").map { firstUser =>
            val topic = summarizeTopic(provider, firstUser.content, sessionSummaryMaxChars)
            s"[${session.timestamp}] topic: $topic"
          }
        }
      }
      .mkString("\n")
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

  private val NoMemoriesSentinel = "NONE"

  /** Strip a leading list bullet so the memory file owns its own formatting rather than inheriting
    * whichever bullet style the model happened to emit.
    */
  private def stripBullet(line: String): String = {
    val trimmed = line.trim
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) trimmed.drop(2).trim
    else if (trimmed == "-" || trimmed == "*") ""
    else trimmed
  }

  def extractMemories(
    provider: ChatProvider,
    userMessage: String,
    agentResponse: String,
    workDir: Path,
    memoryFile: Option[Path] = None
  ): Unit = {
    val prompt =
      s"""Extract durable facts worth remembering from this conversation turn.
         |Focus on: user preferences, project conventions, technical decisions, recurring patterns.
         |
         |User message:
         |$userMessage
         |
         |Agent response:
         |$agentResponse
         |
         |Return one fact per line. If nothing is worth remembering, return exactly $NoMemoriesSentinel.""".stripMargin

    val response = provider.complete(Vector(Message.UserMessage(Content.TextContent(prompt))))

    val facts = response.linesIterator.toVector
      .map(stripBullet)
      .filter(fact => fact.nonEmpty && !fact.equalsIgnoreCase(NoMemoriesSentinel))

    if (facts.nonEmpty) {
      val target = memoryFile.getOrElse(defaultMemoryFilePath(workDir))
      val _ = Try {
        Option(target.getParent).foreach(parent => Files.createDirectories(parent))
        Files.writeString(
          target,
          facts.map(fact => s"- $fact\n").mkString,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Module 02, Exercise 10: Inject memories into context
  // ---------------------------------------------------------------------------

  // Implementation: Load saved memory so scaffolding can inject it explicitly into current context.
  // Failure mode: Hidden or unbounded memory silently steers later tasks and competes with current rules.
  // Agentic coding lesson: Persistent knowledge must stay visible, bounded, and subordinate to current authority.

  def loadMemories(workDir: Path, memoryFile: Option[Path] = None): Option[String] =
    readFileIfPresent(memoryFile.getOrElse(defaultMemoryFilePath(workDir)))

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
    // The terminator is never gated: denying it would leave the agent unable to report back.
    if (toolName == ToolName.MessageUser.wire)
      PermissionResult.Allow("message_user is always permitted")
    else
      denyPatterns.find(pattern => matchesToolPattern(pattern, toolName, toolArgs)) match {
        // Deny wins over allow: an explicit prohibition vetoes a broader grant.
        case Some(pattern) => PermissionResult.Deny(s"blocked by deny pattern `$pattern`")
        case None =>
          if (allowPatterns.isEmpty)
            PermissionResult.Allow(s"no allow patterns configured; `$toolName` permitted")
          else
            allowPatterns.find(pattern => matchesToolPattern(pattern, toolName, toolArgs)) match {
              case Some(pattern) => PermissionResult.Allow(s"permitted by allow pattern `$pattern`")
              case None =>
                PermissionResult.Deny(s"`$toolName` matches no configured allow pattern")
            }
      }

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 2: Enforce sandbox boundaries
  // ---------------------------------------------------------------------------

  // Implementation: Check paths and protected files, then conservatively classify shell commands.
  // Failure mode: Prompt-only analysis can approve an escaping or ambiguous command.
  // Agentic coding lesson: Advisory model judgment is not an execution boundary or real isolation.

  private val PathBoundedTools = Set("read_file", "write_file", "edit_file", "list_files")
  private val MutatingTools    = Set("write_file", "edit_file")

  private def globMatches(pattern: String, value: String): Boolean = {
    val regex = "^" + pattern.split("\\*", -1).map(Pattern.quote).mkString(".*") + "$"
    Try(regex.r.matches(value)).getOrElse(false)
  }

  /** A protected pattern may be written against the raw argument, the workDir-relative path, or the
    * bare file name — all three are how an operator would naturally express it.
    */
  private def isProtectedPath(
    raw: String,
    resolved: Path,
    root: Path,
    protectedFiles: Vector[String]
  ): Boolean = {
    val relative = Try(root.relativize(resolved).toString).getOrElse(raw)
    val fileName = Option(resolved.getFileName).map(_.toString).getOrElse(raw)
    protectedFiles.exists(pattern =>
      globMatches(pattern, raw) || globMatches(pattern, relative) || globMatches(pattern, fileName)
    )
  }

  def enforceSandbox(
    toolName: String,
    args: Json,
    workDir: Path,
    protectedFiles: Vector[String]
  ): PermissionResult =
    if (!PathBoundedTools.contains(toolName))
      // Note the hole this leaves: `shell` takes no `path`, so nothing here constrains it.
      // See analyzeShellSandbox (advisory only) and Exercise 8 for real isolation.
      PermissionResult.Allow(s"`$toolName` takes no path argument; no boundary applies")
    else
      args.get("path").as[String].toOption match {
        case None => PermissionResult.Allow(s"`$toolName` supplied no path to check")
        case Some(raw) =>
          val root      = workDir.toAbsolutePath.normalize
          val candidate = Path.of(raw)
          val resolved =
            (if (candidate.isAbsolute) candidate else root.resolve(candidate)).normalize

          if (!resolved.startsWith(root))
            PermissionResult.Deny(s"path `$raw` resolves outside the working directory `$root`")
          else if (
            MutatingTools.contains(toolName) && isProtectedPath(raw, resolved, root, protectedFiles)
          )
            PermissionResult.Deny(s"path `$raw` is protected and may be read but not modified")
          else
            PermissionResult.Allow(s"path `$raw` is inside the working directory")
      }

  def analyzeShellSandbox(
    provider: ChatProvider,
    command: String,
    workDir: Path
  ): PermissionResult = {
    val prompt =
      s"""Analyze this shell command and tell me if it can read or write files outside of $workDir.
         |Answer with exactly 'yes', 'no', or 'unknown'.
         |
         |Command: $command""".stripMargin

    val answer = provider
      .complete(Vector(Message.UserMessage(Content.TextContent(prompt))))
      .trim
      .toLowerCase

    // Only an explicit "no" allows. "unknown" and anything unparseable deny, because a
    // best-effort model judgment is advisory — it must never fail open.
    if (answer.startsWith("no"))
      PermissionResult.Allow(
        "model analysis reports the command stays inside the working directory"
      )
    else
      PermissionResult.Deny(
        s"model analysis did not clear this command (answered `${preview(answer, 40)}`); " +
          "advisory shell analysis is not an isolation boundary"
      )
  }

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 3: Redact secrets
  // ---------------------------------------------------------------------------

  // Implementation: Apply configured patterns safely to text explicitly passed to this teaching primitive.
  // Failure mode: Assuming an unwired helper protects output or logs leaves disclosure paths open.
  // Agentic coding lesson: Treat every model and log channel as a surface that must be wired and verified.

  def redactSecrets(text: String, patterns: Vector[String]): String =
    SecretPatterns(patterns).redact(text)

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 4: Log audit events
  // ---------------------------------------------------------------------------

  // Implementation: Append structured JSONL events that reconstruct attempted and completed actions.
  // Failure mode: A final agent narrative cannot prove what tools actually ran or changed.
  // Agentic coding lesson: Auditable traces preserve operator accountability and support diagnosis.

  def logAuditEvent(logPath: Path, event: AuditEvent): Unit = {
    val _ = Try {
      Option(logPath.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(
        logPath,
        s"${event.encode}\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }
  }

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

  def createCheckpoint(workDir: Path, checkpointsDir: Path): CheckpointInfo = {
    // Zero-padded millis + counter so plain lexicographic ordering equals chronological
    // ordering, and two checkpoints taken in the same millisecond still sort deterministically.
    val id = f"cp-${System.currentTimeMillis()}%013d-${checkpointCounter.incrementAndGet()}%06d"
    val destination = checkpointsDir.resolve(id)
    copyDirAll(workDir, destination)
    CheckpointInfo(id, Instant.now().toString, destination)
  }

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

  def restoreCheckpoint(workDir: Path, checkpointId: String, checkpointsDir: Path): Boolean = {
    val source = checkpointsDir.resolve(checkpointId)
    if (!Files.isDirectory(source)) false
    else
      // Partial restore, not a rollback: checkpointed files are copied back over the current
      // ones, but files created after the checkpoint are left in place.
      Try {
        copyDirAll(source, workDir)
        true
      }.getOrElse(false)
  }

  def listCheckpoints(checkpointsDir: Path): Vector[CheckpointInfo] =
    entriesSorted(checkpointsDir)
      .filter(path => Files.isDirectory(path) && path.getFileName.toString.startsWith("cp-"))
      .map { path =>
        val timestamp =
          Try(Files.getLastModifiedTime(path).toInstant.toString).getOrElse("")
        CheckpointInfo(path.getFileName.toString, timestamp, path)
      }
      .sortBy(_.id)(using Ordering[String].reverse)

  // ---------------------------------------------------------------------------
  // Module 03, Exercise 8: Sandbox in Lima VM
  // ---------------------------------------------------------------------------

  // Implementation: Adapt command execution to a separately provisioned Lima VM and return its result.
  // Failure mode: A wrapper is mistaken for isolation despite missing provisioning or platform support.
  // Agentic coding lesson: Real isolation is an operational boundary, not a stronger prompt warning.

  private val LimaInstance = "default"

  /** Single-quote a value for `sh`, closing and reopening the quote around any embedded quote. */
  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

  def executeSandboxedShell(command: String, workDir: Path): ToolResult =
    executeSandboxedShell(command, workDir, "limactl")

  private[workshop] def executeSandboxedShell(
    command: String,
    workDir: Path,
    limactl: String
  ): ToolResult = {
    // The VM, its mounts, and this working directory must already exist inside the guest —
    // this adapter provisions nothing. `--sandbox-vm` is parsed but not yet dispatched.
    val remote = s"cd -- ${shellQuote(workDir.toString)} && $command"
    runProcessWithTimeout(
      builder = new ProcessBuilder(limactl, "shell", LimaInstance, "--", "bash", "-c", remote),
      timeoutMessage = "sandboxed command timed out after 30s",
      errorPrefix = "sandboxed shell error: "
    )
  }
}
