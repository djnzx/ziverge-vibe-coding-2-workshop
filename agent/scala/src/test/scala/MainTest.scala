package workshop.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

private def jsonEscape(value: String): String =
  value.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c    => c.toString
  }

def doneMsg(msg: String): String =
  s"""<tool_call>{"name":"message_user","arguments":{"message":"${jsonEscape(msg)}"}}</tool_call>"""

/** A throwaway agent built from default config and a fresh session — for tests that exercise tool
  * execution without setting up a full agent loop.
  */
val testAgent: Agent = Agent(
  state = AgentState(Conversation.initial(""), SessionId.parse("test-session")),
  config = AgentConfig.default,
  tools = ToolRegistry.default.tools,
  provider = new ChatProvider { def complete(messages: Vector[Message]): String = "" }
)

/** Test-only convenience: validate + execute a Tool by raw JSON args. Production code dispatches
  * via `RawToolCall.validate` + `ToolCall.execute` directly.
  */
extension (tool: Tool)
  def runWith(args: zio.blocks.schema.json.Json): ToolResult =
    RawToolCall(tool.name.wire, args).validate(ToolRegistry.default) match {
      case Right(call) => call.execute(testAgent)
      case Left(err)   => ToolResult.Failure(s"invalid arguments: $err")
    }

class MockProvider(responses: Seq[String]) extends ChatProvider {
  private val remaining = scala.collection.mutable.Queue(responses*)

  def complete(messages: Vector[Message]): String =
    if (remaining.nonEmpty) remaining.dequeue() else doneMsg("No more responses.")
}

/** Test-only convenience: build a transient Agent and drive one turn through it. Production code
  * goes through `Agent.handleTurn` or `runTurn(agent, …)`.
  */
def runTurn(
  provider: ChatProvider,
  config: AgentConfig,
  tools: Vector[Tool],
  conversation: Conversation,
  userContent: String,
  terminal: TerminalOutput = TerminalOutput.silent
): TurnResult = {
  val agent = Agent(
    state = AgentState(conversation, SessionId.fresh),
    config = config,
    tools = tools,
    provider = provider
  )
  Exercises.handleTurn(agent, userContent, terminal)._2
}

class ToolExecutionTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-test-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  tmpDir.test("write_file creates a file") { dir =>
    val filePath = dir.resolve("test-write.txt").toString
    val result =
      ToolCall(Tool.WriteFile, Tool.WriteFile.Params(filePath, "hello")).execute(testAgent)
    result match {
      case ToolResult.Success(output) => assert(output.contains("Successfully wrote"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
    assertEquals(Files.readString(Path.of(filePath), StandardCharsets.UTF_8), "hello")
  }

  tmpDir.test("list_files uses max_depth and resolves relative paths against workDir") { dir =>
    import zio.blocks.schema.json.Json
    Files.writeString(dir.resolve("listed.txt"), "x", StandardCharsets.UTF_8)
    val agent = testAgent.copy(config = AgentConfig.default.copy(workDir = dir))
    val raw   = RawToolCall("list_files", Json.parseUnsafe("""{"path":".","max_depth":1}"""))
    val result = raw.validate(ToolRegistry.default) match
      case Right(call) => call.execute(agent)
      case Left(error) => fail(s"expected valid max_depth schema: $error")
    assertEquals(result, ToolResult.Success("listed.txt"))
  }

  tmpDir.test("board tools resolve relative paths against workDir") { dir =>
    val board = dir.resolve("board.json")
    Files.writeString(
      board,
      """{"currentPhase":"draft","phases":{"draft":{"status":"pending"}}}""",
      StandardCharsets.UTF_8
    )
    val agent = testAgent.copy(config = AgentConfig.default.copy(workDir = dir))
    ToolCall(Tool.ReadBoard, Tool.ReadBoard.Params("board.json")).execute(agent) match
      case ToolResult.Success(_)     => ()
      case ToolResult.Failure(error) => fail(s"read_board failed: $error")
    val updated = ToolCall(
      Tool.UpdateBoard,
      Tool.UpdateBoard.Params("board.json", "draft", "complete", Some("draft.md"))
    ).execute(agent)
    updated match
      case ToolResult.Success(_)     => ()
      case ToolResult.Failure(error) => fail(s"update_board failed: $error")
    assert(Files.readString(board).contains("\"status\": \"complete\""))
  }

  tmpDir.test("update_board accepts null for an optional artifact") { dir =>
    val board = dir.resolve("board.json")
    Files.writeString(
      board,
      """{"currentPhase":"draft","phases":{"draft":{"status":"pending"}}}""",
      StandardCharsets.UTF_8
    )
    val agent = testAgent.copy(config = AgentConfig.default.copy(workDir = dir))
    val raw = RawToolCall(
      "update_board",
      zio.blocks.schema.json.Json.parseUnsafe(
        """{"path":"board.json","phase":"draft","status":"queued","artifact":null}"""
      )
    )
    val result = raw.validate(ToolRegistry.default) match
      case Right(call) => call.execute(agent)
      case Left(error) => fail(s"expected null artifact to validate: $error")
    result match
      case ToolResult.Success(_)     => ()
      case ToolResult.Failure(error) => fail(s"update_board failed: $error")
    val written = Files.readString(board)
    assert(written.contains("\"status\": \"queued\""))
    assert(!written.contains("\"artifact\""))
  }

  test("web_fetch tool is registered between list_files and message_user") {
    val names            = ToolRegistry.default.tools.map(_.name)
    val webFetchIndex    = names.indexOf(ToolName.WebFetch)
    val listFilesIndex   = names.indexOf(ToolName.ListFiles)
    val messageUserIndex = names.indexOf(ToolName.MessageUser)

    assert(webFetchIndex >= 0)
    assertEquals(webFetchIndex, listFilesIndex + 1)
    assertEquals(messageUserIndex, webFetchIndex + 1)
  }

  test("web_fetch params accept valid url") {
    import zio.blocks.schema.json.Json
    val parsed = Json.parseUnsafe("""{"url":"https://example.com"}""").as[Tool.WebFetch.Params]
    assert(parsed.isRight)
  }

  test("RawToolCall.validate rejects web_fetch with missing url") {
    import zio.blocks.schema.json.Json
    val raw = RawToolCall("web_fetch", Json.parseUnsafe("{}"))
    raw.validate(ToolRegistry.default) match {
      case Right(call) => fail(s"expected validation failure, got: $call")
      case Left(err)   => assert(err.nonEmpty)
    }
  }
}

class ParseInputContentTest extends munit.FunSuite {

  test("extracts content from valid JSON") {
    val result = IoFormat.Json.parseInput("""{"content": "hello world"}""")
    assertEquals(result, "hello world")
  }

  test("falls back to plain text on invalid JSON") {
    val result = IoFormat.Json.parseInput("just plain text")
    assertEquals(result, "just plain text")
  }

  test("falls back when content field is missing") {
    val result = IoFormat.Json.parseInput("""{"other": "field"}""")
    assertEquals(result, """{"other": "field"}""")
  }
}

class ParseFilePromptsTest extends munit.FunSuite {

  test("splits prompts on --- delimiter") {
    val input =
      """First prompt
        |---
        |Second prompt""".stripMargin

    assertEquals(
      workshop.agent.internal.TextUtils.splitSections(input, "---"),
      Vector("First prompt", "Second prompt")
    )
  }

  test("handles multiline prompts") {
    val input =
      """Line 1
        |Line 2
        |---
        |Another
        |multiline
        |prompt""".stripMargin

    assertEquals(
      workshop.agent.internal.TextUtils.splitSections(input, "---"),
      Vector("Line 1\nLine 2", "Another\nmultiline\nprompt")
    )
  }

  test("skips empty prompt sections") {
    val input =
      """---
        |
        |First
        |---
        |
        |---
        |Second
        |---
        |""".stripMargin

    assertEquals(
      workshop.agent.internal.TextUtils.splitSections(input, "---"),
      Vector("First", "Second")
    )
  }

  test("returns single prompt when no delimiter exists") {
    val input = "Single prompt content"
    assertEquals(
      workshop.agent.internal.TextUtils.splitSections(input, "---"),
      Vector("Single prompt content")
    )
  }
}

class LineReaderTest extends munit.FunSuite {

  test("Canned returns each line in turn, then EndOfInput") {
    val reader = LineReader.Canned("hello", "world")
    assertEquals(reader.readLine(""), LineReader.Result.Line("hello"))
    assertEquals(reader.readLine(""), LineReader.Result.Line("world"))
    assertEquals(reader.readLine(""), LineReader.Result.EndOfInput)
  }

  test("run processes one line then exits cleanly on EndOfInput") {
    val terminal    = TerminalOutput.real
    val reader      = LineReader.Canned("hello")
    val stdoutBytes = new java.io.ByteArrayOutputStream()

    val handled = Console.withOut(stdoutBytes) {
      reader.run[Vector[String]](terminal, Vector.empty)((acc, input) => (acc :+ input, true))
    }

    val output = stdoutBytes.toString(StandardCharsets.UTF_8)
    assertEquals(handled, Vector("hello"))
    assert(output.contains("goodbye."), s"expected goodbye output, got: $output")
  }

  test("run accumulates continuation lines ending with backslash") {
    val terminal    = TerminalOutput.real
    val reader      = LineReader.Canned("first \\", "second")
    val stdoutBytes = new java.io.ByteArrayOutputStream()

    val handled = Console.withOut(stdoutBytes) {
      reader.run[Vector[String]](terminal, Vector.empty)((acc, input) => (acc :+ input, true))
    }

    assertEquals(handled, Vector("first \nsecond"))
  }

  test("run on Interrupted preserves buffer and keeps reading") {
    val terminal = TerminalOutput.real
    val reader = LineReader.Canned.of(
      LineReader.Result.Line("partial \\"),
      LineReader.Result.Interrupted,
      LineReader.Result.Line("rest")
    )
    val stdoutBytes = new java.io.ByteArrayOutputStream()

    val handled = Console.withOut(stdoutBytes) {
      reader.run[Vector[String]](terminal, Vector.empty)((acc, input) => (acc :+ input, true))
    }

    assertEquals(handled, Vector("partial \nrest"))
  }
}

class CommandTest extends munit.FunSuite {

  test("UserCommand.parse recognizes built-ins and prompts") {
    assertEquals(UserCommand.parse("/quit"), UserCommand.Quit)
    assertEquals(UserCommand.parse("/exit"), UserCommand.Quit)
    assertEquals(UserCommand.parse("/compact"), UserCommand.Compact)
    assertEquals(UserCommand.parse("/foo bar"), UserCommand.Slash("foo", "bar"))
    assertEquals(UserCommand.parse("hello"), UserCommand.Prompt("hello"))
  }

  test("formatConversationForCompaction excludes system messages") {
    val messages = Conversation(
      "sys",
      Vector(TurnMessage.user("Hi"), TurnMessage.assistant("Hello"))
    )

    val formatted = Exercises.formatConversationForCompaction(messages)
    assert(!formatted.contains("sys"))
    assert(formatted.contains("user: Hi"))
    assert(formatted.contains("assistant: Hello"))
  }

  test("applyCompaction preserves system message and returns 3 messages") {
    val input = Conversation(
      "system prompt",
      Vector(TurnMessage.user("old"))
    )
    val result = Exercises.applyCompaction(input, "summary text")

    assertEquals(result.turns.length + 1, 3)
    assertEquals(result.systemPrompt, "system prompt")

    val user = result.turns(0).message.asInstanceOf[Message.User]
    assertEquals(
      user.content,
      Content.TextContent("[Context from previous conversation]\nsummary text")
    )

    val assistant = result.turns(1).message.asInstanceOf[Message.Assistant]
    assert(
      assistant.content.contains("\"name\":\"message_user\"") && assistant.content
        .contains("Context loaded. How can I help?")
    )
  }

  private def agentOf(
    messages: Conversation,
    config: AgentConfig,
    provider: ChatProvider,
    sessionId: String = "session-id"
  ): Agent =
    Agent(
      state = AgentState(messages, SessionId.parse(sessionId)),
      config = config,
      tools = Vector.empty,
      provider = provider
    )

  test("step on Quit yields StepOutcome.Quit") {
    val provider = new MockProvider(Seq.empty)
    val agent    = agentOf(Conversation("sys", Vector.empty), AgentConfig.default, provider)

    assertEquals(agent.step(UserCommand.Quit)._2, StepOutcome.Quit)
    assertEquals(agent.step(UserCommand.parse("/exit"))._2, StepOutcome.Quit)
  }

  test("step on /greet without a command template yields StepOutcome.Unknown") {
    val provider = new MockProvider(Seq.empty)
    val agent    = agentOf(Conversation("sys", Vector.empty), AgentConfig.default, provider)
    assertEquals(agent.step(UserCommand.parse("/greet"))._2, StepOutcome.Unknown("greet"))
  }

  test("step on /greet uses a configured command template") {
    val commandsDir = Files.createTempDirectory("agent-commands-")
    Files.writeString(
      commandsDir.resolve("greet.md"),
      "---\ndescription: Greet someone\n---\nGreet $ARGUMENTS.",
      StandardCharsets.UTF_8
    )
    val provider = new MockProvider(Seq("Hello, Ada!"))
    val agent = agentOf(
      Conversation("sys", Vector.empty),
      AgentConfig.default.copy(commandsDir = Some(commandsDir)),
      provider
    )

    try
      assertEquals(
        agent.step(UserCommand.parse("/greet Ada"))._2,
        StepOutcome.Custom("Hello, Ada!")
      )
    finally
      Files
        .walk(commandsDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)
  }

  test("step on Compact yields StepOutcome.Compacted") {
    var capturedMessages: Vector[Message] = Vector.empty
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        capturedMessages = messages
        "summary from model"
      }
    }

    val conv = Conversation(
      "sys",
      Vector(TurnMessage.user("u1"), TurnMessage.assistant("a1"))
    )
    val agent = agentOf(conv, AgentConfig.default, provider)

    val (next, outcome) = agent.step(UserCommand.Compact)
    outcome match {
      case StepOutcome.Compacted(_, _) => ()
      case other                       => fail(s"expected Compacted outcome, got $other")
    }
    assertEquals(capturedMessages.length, 1)
    assert(capturedMessages.head.isInstanceOf[Message.User])
    // Post-compaction conversation is a synthetic user/assistant pair.
    assertEquals(next.state.conversation.turns.length, 2)
  }
}

class AgentConfigTest extends munit.FunSuite {

  test("default config has sensible defaults") {
    val config = AgentConfig.default
    assertEquals(config.model, sys.env.getOrElse("MODEL", "anthropic/claude-sonnet-4"))
    assertEquals(config.systemPrompt, Path.of("../system-prompt.txt"))
    assertEquals(config.tools, ToolSelection.All)
    assertEquals(config.workDir, Path.of(System.getProperty("user.dir")))
    assertEquals(config.maxIterations, 20)
    assertEquals(config.temperature, 0.0)
  }

  test("getEnabledTools returns all when config.tools is empty") {
    val config  = AgentConfig.default.copy(tools = ToolSelection.All)
    val enabled = config.enabledTools(ToolRegistry.default.tools)
    assertEquals(enabled.map(_.name), ToolRegistry.default.tools.map(_.name))
  }

  test("getEnabledTools filters plus message_user") {
    val config = AgentConfig.default.copy(tools = ToolSelection.Only(Set(ToolName.ReadFile)))
    val names  = config.enabledTools(ToolRegistry.default.tools).map(_.name)

    assertEquals(names, Vector(ToolName.ReadFile, ToolName.MessageUser))
  }

  test("parseCliConfig keeps empty --tools as Only(empty)") {
    val result = CliConfig.parse(Array("--tools", ""))
    assert(result.isRight)
    val config = result.toOption.get.config
    assertEquals(config.tools, ToolSelection.Only(Set.empty))
  }

  test("parseCliConfig rejects unknown tool names") {
    val result = CliConfig.parse(Array("--tools", "read_file,nope_tool"))
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("Unknown tool name(s): nope_tool"))
  }

  test("parseCliConfig reports all unknown tool names") {
    val result = CliConfig.parse(Array("--tools", "foo,read_file,bar,foo"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get, "Unknown tool name(s): foo, bar")
  }

  test("parseCliConfig uses --flag requires a value wording") {
    val result = CliConfig.parse(Array("--model"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get, "--model requires a value")
  }

  test("loadConfigFile interprets empty tools list as ToolSelection.All") {
    val dir     = java.nio.file.Files.createTempDirectory("config-test-scala-")
    val cfgPath = dir.resolve("tools-empty.json")
    java.nio.file.Files.writeString(cfgPath, """{"tools":[]}""")

    val loaded = AgentConfigOverrides.fromFile(cfgPath.toString).toOption.get
    assertEquals(loaded.tools, Some(ToolSelection.All))

    java.nio.file.Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
  }

  test("parseCliConfig rejects conflicting mode flags") {
    val result = CliConfig.parse(Array("--interactive", "--protocol"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get, "Conflicting mode flags: --interactive and --protocol")
  }

  test("parseCliConfig rejects --file with other mode flag") {
    val result = CliConfig.parse(Array("--file", "prompts.txt", "--interactive"))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get, "Conflicting mode flags: --file and --interactive")
  }

  test("maxIterations limits turns") {
    var callCount = 0
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        callCount += 1
        "Still working..."
      }
    }

    val config = AgentConfig.default.copy(maxIterations = 2)
    val turn = runTurn(
      provider,
      config,
      ToolRegistry.default.tools,
      Conversation.initial(Exercises.buildSystemPrompt(config, ToolRegistry.default.tools)),
      "Do work",
      TerminalOutput.silent
    )

    assertEquals(callCount, 2)
    assertEquals(turn.content, "Max iterations reached.")
  }

  test("tool filtering prevents disabled tools") {
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"shell","arguments":{"command":"echo hi"}}</tool_call>""",
        doneMsg("Done")
      )
    )

    val config       = AgentConfig.default.copy(tools = ToolSelection.Only(Set(ToolName.ReadFile)))
    val enabledTools = config.enabledTools(ToolRegistry.default.tools)
    val turn = runTurn(
      provider,
      config,
      enabledTools,
      Conversation.initial(Exercises.buildSystemPrompt(config, enabledTools)),
      "Try shell",
      TerminalOutput.silent
    )

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.User(Content.TextContent(text), _)) => text
    }
    assert(userTexts.exists(_.contains("Unknown tool: shell")))
  }

  test("default config module 02 fields") {
    val config = AgentConfig.default
    assertEquals(config.instructions, None)
    assertEquals(config.skillsDir, None)
    assertEquals(config.historyDir, None)
    assertEquals(config.maxContextChars, None)
  }

  test("default config module 03 fields") {
    val config = AgentConfig.default
    assertEquals(config.allowTools, Vector.empty)
    assertEquals(config.denyTools, Vector.empty)
    assertEquals(config.protectedFiles, Vector.empty)
    assert(config.secretPatterns.nonEmpty)
    assertEquals(config.auditLog, None)
  }

  test("parse CLI module 02 flags") {
    val args = Array(
      "--instructions",
      "./AGENTS.md",
      "--skills-dir",
      "./skills",
      "--history-dir",
      "./history",
      "--max-context-chars",
      "50000"
    )
    val result = CliConfig.parse(args)
    assert(result.isRight)
    val config = result.toOption.get.config
    assertEquals(config.instructions, Some("./AGENTS.md"))
    assertEquals(config.skillsDir, Some(Path.of("./skills")))
    assertEquals(config.historyDir, Some(Path.of("./history")))
    assertEquals(config.maxContextChars, Some(50000))
  }

  test("parse CLI module 03 flags") {
    val args = Array(
      "--allow-tools",
      "read_file,write_file",
      "--deny-tools",
      "shell",
      "--protected-files",
      "*.env,secrets/*",
      "--secret-patterns",
      "sk-.*,ghp_.*",
      "--audit-log",
      "./audit.log"
    )
    val result = CliConfig.parse(args)
    assert(result.isRight)
    val config = result.toOption.get.config
    assertEquals(config.allowTools, Vector("read_file", "write_file"))
    assertEquals(config.denyTools, Vector("shell"))
    assertEquals(config.protectedFiles, Vector("*.env", "secrets/*"))
    assertEquals(config.secretPatterns, SecretPatterns(Vector("sk-.*", "ghp_.*")))
    assertEquals(config.auditLog, Some(Path.of("./audit.log")))
  }

  test("resolve config preserves module 02 overrides") {
    val config = AgentConfig.default.overrideWith(
      AgentConfigOverrides(
        instructions = Some("./AGENTS.md"),
        skillsDir = Some(Path.of("./skills")),
        maxContextChars = Some(50000)
      )
    )
    assertEquals(config.instructions, Some("./AGENTS.md"))
    assertEquals(config.skillsDir, Some(Path.of("./skills")))
    assertEquals(config.maxContextChars, Some(50000))
  }

  test("resolve config preserves module 03 overrides") {
    val config = AgentConfig.default.overrideWith(
      AgentConfigOverrides(
        allowTools = Some(Vector("read_file")),
        denyTools = Some(Vector("shell")),
        protectedFiles = Some(Vector("*.env")),
        auditLog = Some(Path.of("./audit.log"))
      )
    )
    assertEquals(config.allowTools, Vector("read_file"))
    assertEquals(config.denyTools, Vector("shell"))
    assertEquals(config.protectedFiles, Vector("*.env"))
    assertEquals(config.auditLog, Some(Path.of("./audit.log")))
  }

  test("custom secret patterns override defaults") {
    val config = AgentConfig.default.overrideWith(
      AgentConfigOverrides(
        secretPatterns = Some(SecretPatterns(Vector("custom-.*")))
      )
    )
    assertEquals(config.secretPatterns, SecretPatterns(Vector("custom-.*")))
  }

  test("loadConfigFile reads JSON config") {
    val dir     = java.nio.file.Files.createTempDirectory("config-test-scala-")
    val cfgPath = dir.resolve("agent.json")
    java.nio.file.Files
      .writeString(cfgPath, """{"model":"gpt-4o-mini","maxIterations":5,"temperature":0.3}""")
    val loaded = AgentConfigOverrides.fromFile(cfgPath.toString).toOption.get
    assertEquals(loaded.model, Some("gpt-4o-mini"))
    assertEquals(loaded.maxIterations, Some(5))
    assertEquals(loaded.temperature, Some(0.3))
    java.nio.file.Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
  }

  test("--config flag loads file into parseCliConfig") {
    val dir     = java.nio.file.Files.createTempDirectory("config-test-scala-")
    val cfgPath = dir.resolve("cli-config.json")
    java.nio.file.Files.writeString(
      cfgPath,
      """{"model":"from-file","tools":["read_file"],"allowTools":["read_file"]}"""
    )
    val result = CliConfig.parse(Array("--config", cfgPath.toString))
    assert(result.isRight)
    val config = result.toOption.get.config
    assertEquals(config.model, "from-file")
    assertEquals(config.tools, ToolSelection.Only(Set(ToolName.ReadFile)))
    assertEquals(config.allowTools, Vector("read_file"))
    java.nio.file.Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
  }

  test("CLI flags override config file values") {
    val dir     = java.nio.file.Files.createTempDirectory("config-test-scala-")
    val cfgPath = dir.resolve("override.json")
    java.nio.file.Files.writeString(cfgPath, """{"model":"from-file","maxIterations":10}""")
    val result = CliConfig.parse(Array("--config", cfgPath.toString, "--model", "from-cli"))
    assert(result.isRight)
    val config = result.toOption.get.config
    assertEquals(config.model, "from-cli")
    assertEquals(config.maxIterations, 10)
    java.nio.file.Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
  }
}

class RecordingTerminalTest extends munit.FunSuite {
  test("records all event types") {
    val term = new TerminalOutput.Recording
    term.banner("model", Seq(ToolName.ReadFile))
    term.thinking("thought")
    term.toolCall(
      ToolCall(Tool.ReadFile, Tool.ReadFile.Params("f.txt"))
    )
    term.toolResult(ToolResult.Success("ok"))
    term.answer("done")
    term.error("oops")
    term.spinnerStart()
    term.spinnerStop()
    term.goodbye()

    assertEquals(term.events.length, 9)
    assert(term.events.head.isInstanceOf[TerminalEvent.Banner])
    assert(term.events.last == TerminalEvent.Goodbye)
  }

  test("promptString returns plain prompt") {
    val term = new TerminalOutput.Recording
    assertEquals(term.promptString, "> ")
  }
}

class FormatArgValueTest extends munit.FunSuite {
  import zio.blocks.schema.json.Json

  private def jsonStr(s: String): Json = {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    Json.parseUnsafe(s"\"$escaped\"")
  }

  test("returns simple strings unquoted") {
    assertEquals(ArgDisplay.render(jsonStr("hello.txt")), "hello.txt")
  }

  test("returns multiline strings in YAML block-scalar form") {
    assertEquals(ArgDisplay.render(jsonStr("line1\nline2")), "|\n      line1\n      line2")
  }

  test("returns numbers as-is") {
    assertEquals(ArgDisplay.render(Json.parseUnsafe("42")), "42")
  }

  test("returns booleans as-is") {
    assertEquals(ArgDisplay.render(Json.parseUnsafe("true")), "true")
    assertEquals(ArgDisplay.render(Json.parseUnsafe("false")), "false")
  }

  test("returns objects as compact JSON") {
    val obj = Json.parseUnsafe("""{"a":1}""")
    assertEquals(ArgDisplay.render(obj), """{"a":1}""")
  }

  test("returns arrays as compact JSON") {
    val arr = Json.parseUnsafe("[1,2,3]")
    assertEquals(ArgDisplay.render(arr), "[1,2,3]")
  }

  test("returns null as null") {
    assertEquals(ArgDisplay.render(Json.parseUnsafe("null")), "null")
  }

  test("truncates multiline strings beyond 8 lines") {
    val lines  = (1 to 12).map(i => s"line$i").mkString("\n")
    val result = ArgDisplay.render(jsonStr(lines))
    assert(result.startsWith("|\n"))
    assert(result.contains("line8"))
    assert(!result.contains("line9"))
    assert(result.endsWith("[+4 more lines]"))
  }
}

class TerminalInteractionTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-terminal-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private val defaultConfig = AgentConfig.default

  private def initialMessages: Conversation =
    Conversation.initial(Exercises.buildSystemPrompt(defaultConfig, ToolRegistry.default.tools))

  private def jsonPath(path: Path): String =
    path.toString.replace("\\", "\\\\")

  test("records spinner start/stop around LLM call") {
    val term     = new TerminalOutput.Recording
    val provider = new MockProvider(Seq(doneMsg("hi")))

    runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "hello", term)

    val starts = term.events.count(_.isInstanceOf[TerminalEvent.SpinnerStart.type])
    val stops  = term.events.count(_.isInstanceOf[TerminalEvent.SpinnerStop.type])
    assert(starts >= 1, s"should start spinner at least once, got $starts")
    assert(stops >= 1, s"should stop spinner at least once, got $stops")
    assertEquals(starts, stops)
  }

  test("records thinking text") {
    val term = new TerminalOutput.Recording
    val provider = new MockProvider(
      Seq(s"THINKING: I need to analyze this.\nACTION: respond\n${doneMsg("Analyzed.")}")
    )

    runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Analyze", term)

    val thinkingEvents = term.events.collect { case TerminalEvent.Thinking(text) => text }
    assert(thinkingEvents.nonEmpty, "should record thinking")
    assert(
      thinkingEvents.head.toLowerCase.contains("analyze"),
      s"thinking text should contain 'analyze', got: ${thinkingEvents.head}"
    )
  }

  tmpDir.test("records tool call and result") { dir =>
    val term = new TerminalOutput.Recording
    val file = dir.resolve("rec.txt")
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            file
          )}","content":"data"}}</tool_call>""",
        doneMsg("Written.")
      )
    )

    runTurn(
      provider,
      defaultConfig,
      ToolRegistry.default.tools,
      initialMessages,
      "Write rec.txt",
      term
    )

    val toolCalls = term.events.collect { case TerminalEvent.ToolCall(call) => call.name }
    assertEquals(toolCalls, Vector(ToolName.WriteFile))

    val toolResults = term.events.collect { case TerminalEvent.ToolResult(result) =>
      result
    }
    assertEquals(toolResults.length, 1)
    toolResults.head match {
      case ToolResult.Success(output) => assert(output.contains("Successfully wrote"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
  }

  test("records answer on completion") {
    val term     = new TerminalOutput.Recording
    val provider = new MockProvider(Seq(doneMsg("Final answer here.")))

    runTurn(
      provider,
      defaultConfig,
      ToolRegistry.default.tools,
      initialMessages,
      "question",
      term
    )

    val answers = term.events.collect { case TerminalEvent.Answer(text) => text }
    assertEquals(answers, Vector("Final answer here."))
  }

  test("records error for unknown tool") {
    val term = new TerminalOutput.Recording
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"nope","arguments":{}}</tool_call>""",
        doneMsg("ok")
      )
    )

    runTurn(
      provider,
      defaultConfig,
      ToolRegistry.default.tools,
      initialMessages,
      "try nope",
      term
    )

    val errors = term.events.collect { case TerminalEvent.Error(text) => text }
    assert(errors.nonEmpty, "should record error for unknown tool")
    assert(errors.head.contains("Unknown tool"))
  }

  test("records error on max iterations") {
    val term     = new TerminalOutput.Recording
    val provider = new MockProvider(Seq.fill(25)("Still thinking..."))

    runTurn(
      provider,
      defaultConfig,
      ToolRegistry.default.tools,
      initialMessages,
      "Infinite",
      term
    )

    val errors = term.events.collect { case TerminalEvent.Error(text) => text }
    assert(
      errors.exists(e => e.contains("Max iterations") || e.contains("OpenRouter")),
      s"should record max iterations or error, got: $errors"
    )
  }

  test("records tool result as error when tool fails") {
    val term = new TerminalOutput.Recording
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"read_file","arguments":{"path":"/nonexistent/file.txt"}}</tool_call>""",
        doneMsg("Failed.")
      )
    )

    runTurn(
      provider,
      defaultConfig,
      ToolRegistry.default.tools,
      initialMessages,
      "Read missing file",
      term
    )

    val toolResults = term.events.collect { case TerminalEvent.ToolResult(result) =>
      result
    }
    assertEquals(toolResults.length, 1)
    toolResults.head match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error) =>
        assert(error.contains("reading file"))
        assert(!error.startsWith("Error:"))
    }
  }
}

class ConvergenceRound2Test extends munit.FunSuite {

  private val baseConfig = AgentConfig.default

  private def initialMessages(config: AgentConfig): Conversation =
    Conversation.initial(
      Exercises.buildSystemPrompt(config, config.enabledTools(ToolRegistry.default.tools))
    )

  test("parseToolCalls rejects empty tool name") {
    val text            = """<tool_call>{"name":"","arguments":{}}</tool_call>"""
    val (calls, errors) = Exercises.parseToolCalls(text)
    assertEquals(calls, Vector.empty)
    assert(errors.exists(_.contains("missing \"name\" field")))
  }

  test("handleTurn propagates provider failures") {
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String =
        throw new RuntimeException("boom")
    }

    intercept[RuntimeException] {
      runTurn(
        provider,
        baseConfig,
        baseConfig.enabledTools(ToolRegistry.default.tools),
        initialMessages(baseConfig),
        "hi",
        TerminalOutput.silent
      )
    }
  }

  test("file tools resolve relative paths against config.workDir in handleTurn") {
    val dir    = java.nio.file.Files.createTempDirectory("workdir-resolve-scala-")
    val config = baseConfig.copy(workDir = dir)
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"write_file","arguments":{"path":"out.txt","content":"hello"}}</tool_call>""",
        doneMsg("done")
      )
    )

    try {
      runTurn(
        provider,
        config,
        config.enabledTools(ToolRegistry.default.tools),
        initialMessages(config),
        "write",
        TerminalOutput.silent
      )

      val expected = dir.resolve("out.txt")
      assert(Files.exists(expected), s"expected file at workDir-relative path: $expected")
      assertEquals(Files.readString(expected, StandardCharsets.UTF_8), "hello")
    } finally
      java.nio.file.Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(java.nio.file.Files.delete)
  }

  test("step on unknown command response includes slash") {
    val provider = new MockProvider(Seq.empty)
    val agent = Agent(
      state = AgentState(Conversation.initial("sys"), SessionId.parse("session-id")),
      config = baseConfig,
      tools = baseConfig.enabledTools(ToolRegistry.default.tools),
      provider = provider
    )
    val (_, outcome) = agent.step(UserCommand.parse("/foo"))
    assertEquals(outcome, StepOutcome.Unknown("foo"))
    assertEquals(outcome.content, "Unknown command: /foo")
  }
}

class ConvergenceRound3Test extends munit.FunSuite {

  test("loadConfigFile returns Left on malformed JSON") {
    val dir     = java.nio.file.Files.createTempDirectory("config-malformed-scala-")
    val cfgPath = dir.resolve("broken.json")
    java.nio.file.Files.writeString(cfgPath, "{not-valid-json")

    try {
      val result = AgentConfigOverrides.fromFile(cfgPath.toString)
      assert(result.isLeft)
      assert(result.left.toOption.get.startsWith("Failed to load config file:"))
    } finally
      java.nio.file.Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(java.nio.file.Files.delete)
  }

  test("handleTurn stops spinner even when provider throws") {
    val term = new TerminalOutput.Recording
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String =
        throw new RuntimeException("provider boom")
    }

    intercept[RuntimeException] {
      runTurn(
        provider,
        AgentConfig.default,
        ToolRegistry.default.tools,
        Conversation.initial(
          Exercises.buildSystemPrompt(AgentConfig.default, ToolRegistry.default.tools)
        ),
        "hi",
        term
      )
    }

    val starts = term.events.count(_ == TerminalEvent.SpinnerStart)
    val stops  = term.events.count(_ == TerminalEvent.SpinnerStop)
    assertEquals(starts, 1)
    assertEquals(stops, 1)
  }

  test("runShell non-zero exit follows canonical format") {
    val result = Exercises.runShell("printf out; >&2 printf err; exit 7", Path.of("."))
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error) =>
        assert(error.startsWith("(exit code 7):\n"))
        assert(error.contains("out"))
        assert(error.contains("err"))
        assert(!error.startsWith("command failed"))
    }
  }
}
