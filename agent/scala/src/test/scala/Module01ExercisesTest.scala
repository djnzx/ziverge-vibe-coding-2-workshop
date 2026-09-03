package workshop.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

// Exercise tests for Module 01: Foundations
// Exercise 8 is manual/observational — no unit tests by design.

class Module01Exercise01SystemPromptTest extends munit.FunSuite {

  private val prompt = Exercises.buildSystemPrompt(AgentConfig.default, ToolRegistry.default.tools)

  test("contains all tool names") {
    assert(prompt.contains("read_file"))
    assert(prompt.contains("write_file"))
    assert(prompt.contains("edit_file"))
    assert(prompt.contains("shell"))
    assert(prompt.contains("message_user"))
  }

  test("contains the tool_call protocol") {
    assert(prompt.contains("<tool_call>"))
    assert(prompt.contains("</tool_call>"))
  }

  test("contains JSON schemas for each tool") {
    assert(prompt.contains("```json"))
    assert(prompt.contains("\"path\""))
    assert(prompt.contains("\"command\""))
  }
}

class Module01Exercise02ParseToolCallsTest extends munit.FunSuite {

  test("extracts a single tool call") {
    val text =
      "Let me read that.\n<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"foo.txt\"}}\n</tool_call>"
    val (calls, errors) = Exercises.parseToolCalls(text)
    assertEquals(calls.length, 1)
    assertEquals(calls(0).name, "read_file")
    assertEquals(calls(0).arguments.get("path").as[String].toOption, Some("foo.txt"))
    assert(errors.isEmpty)
  }

  test("extracts multiple tool calls") {
    val text =
      """<tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call> then <tool_call>{"name": "shell", "arguments": {"command": "ls"}}</tool_call>"""
    val (calls, _) = Exercises.parseToolCalls(text)
    assertEquals(calls.length, 2)
    assertEquals(calls(0).name, "read_file")
    assertEquals(calls(1).name, "shell")
  }

  test("returns empty for plain text") {
    val (calls, errors) = Exercises.parseToolCalls("Just some commentary.")
    assert(calls.isEmpty)
    assert(errors.isEmpty)
  }

  test("reports malformed JSON as error") {
    val text            = "<tool_call>this is not json</tool_call>"
    val (calls, errors) = Exercises.parseToolCalls(text)
    assert(calls.isEmpty)
    assertEquals(errors.length, 1)
    assert(errors(0).contains("Malformed"))
  }

  test("reports missing name as error") {
    val text            = """<tool_call>{"arguments": {"path": "foo.txt"}}</tool_call>"""
    val (calls, errors) = Exercises.parseToolCalls(text)
    assert(calls.isEmpty)
    assertEquals(errors.length, 1)
    assert(errors(0).contains("missing"))
  }

  test("reports empty name as error") {
    val text = """<tool_call>{"name": "", "arguments": {"path": "foo.txt"}}</tool_call>"""
    val (calls, errors) = Exercises.parseToolCalls(text)
    assert(calls.isEmpty)
    assertEquals(errors.length, 1)
    assert(errors(0).contains("missing"))
  }

  test("extracts valid calls and reports broken ones") {
    val text =
      """<tool_call>broken</tool_call><tool_call>{"name": "shell", "arguments": {"command": "pwd"}}</tool_call>"""
    val (calls, errors) = Exercises.parseToolCalls(text)
    assertEquals(calls.length, 1)
    assertEquals(calls(0).name, "shell")
    assertEquals(errors.length, 1)
    assert(errors(0).contains("Malformed"))
  }
}

class Module01Exercise03AgentLoopTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-loop-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private val defaultConfig = AgentConfig.default

  private def initialMessages: Conversation =
    Conversation.initial(Exercises.buildSystemPrompt(defaultConfig, ToolRegistry.default.tools))

  private def jsonPath(path: Path): String =
    path.toString.replace("\\", "\\\\")

  private val nudge = "You must call a tool. Use message_user to deliver your final response."

  tmpDir.test("executes a tool call and completes") { dir =>
    val file = dir.resolve("a.txt")
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            file
          )}","content":"hello"}}</tool_call>""",
        doneMsg("Done.")
      )
    )

    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Create a file")

    assert(Files.exists(file))
    assertEquals(Files.readString(file, StandardCharsets.UTF_8), "hello")
    assert(turn.toolCalls.contains(ToolName.WriteFile))
    assertEquals(turn.toolCalls.last, ToolName.MessageUser)
  }

  tmpDir.test("only executes first tool call in a single response") { dir =>
    val file    = dir.resolve("b.txt")
    val skipped = dir.resolve("b-skipped.txt")
    val provider = new MockProvider(
      Seq(
        s"""I will do it.
           |<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            file
          )}","content":"created"}}</tool_call>
           |<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            skipped
          )}","content":"not-created"}}</tool_call>
           |""".stripMargin,
        doneMsg("Finished")
      )
    )

    val turn =
      runTurn(
        provider,
        defaultConfig,
        ToolRegistry.default.tools,
        initialMessages,
        "Create file and finish",
        TerminalOutput.silent
      )

    assert(Files.exists(file))
    assertEquals(Files.readString(file, StandardCharsets.UTF_8), "created")
    assert(!Files.exists(skipped))
    assertEquals(turn.toolCalls, Vector(ToolName.WriteFile, ToolName.MessageUser))
  }

  test("nudges on empty response") {
    val provider = new MockProvider(Seq("Thinking...", "Still thinking...", doneMsg("Done")))
    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Do something")

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _)) => text
    }

    assertEquals(userTexts.count(_ == nudge), 2)
  }

  tmpDir.test("handles read after write") { dir =>
    val file = dir.resolve("d.txt")
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            file
          )}","content":"hello"}}</tool_call>""",
        s"""<tool_call>{"name":"read_file","arguments":{"path":"${jsonPath(file)}"}}</tool_call>""",
        doneMsg("All done")
      )
    )

    val turn =
      runTurn(
        provider,
        defaultConfig,
        ToolRegistry.default.tools,
        initialMessages,
        "Write and then read",
        TerminalOutput.silent
      )
    assertEquals(
      turn.toolCalls,
      Vector(ToolName.WriteFile, ToolName.ReadFile, ToolName.MessageUser)
    )
  }

  tmpDir.test("ignores additional tool calls beyond the first") { dir =>
    val first  = dir.resolve("c1.txt")
    val second = dir.resolve("c2.txt")
    val provider = new MockProvider(
      Seq(
        s"""
           |<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            first
          )}","content":"one"}}</tool_call>
           |<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            second
          )}","content":"two"}}</tool_call>
           |""".stripMargin,
        doneMsg("Finished")
      )
    )

    val turn =
      runTurn(
        provider,
        defaultConfig,
        ToolRegistry.default.tools,
        initialMessages,
        "Create two files",
        TerminalOutput.silent
      )

    assert(Files.exists(first))
    assert(!Files.exists(second))
    assertEquals(turn.toolCalls, Vector(ToolName.WriteFile, ToolName.MessageUser))
  }

  test("reports unknown tool") {
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"delete_file","arguments":{"path":"/tmp/nope.txt"}}</tool_call>""",
        doneMsg("Done")
      )
    )
    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Try deleting")

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _)) => text
    }
    assert(userTexts.exists(_.contains("Unknown tool: delete_file")))
    assertEquals(turn.toolCalls, Vector(ToolName.MessageUser))
  }

  test("returns max iterations") {
    val provider = new MockProvider(Seq.fill(25)("Still working..."))
    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Do work")
    assertEquals(turn.content, "Max iterations reached.")
  }

  test("forces another iteration after tool calls so model sees results") {
    var callCount = 0
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        callCount += 1
        if (callCount == 1) {
          """<tool_call>{"name":"shell","arguments":{"command":"echo real-output"}}</tool_call>
 <tool_call>{"name":"message_user","arguments":{"message":"The output was fake-output."}}</tool_call>"""
        } else {
          val lastUser = messages.reverse
            .collectFirst { case Message.UserMessage(Content.TextContent(text), _) =>
              text
            }
            .getOrElse("")
          if (lastUser.contains("real-output"))
            doneMsg("The output was real-output.")
          else
            doneMsg("Something went wrong.")
        }
      }
    }

    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Run echo")

    assert(callCount >= 2, s"model should get a second turn, got $callCount calls")
    assert(
      turn.content.contains("real-output"),
      s"final response should reflect actual tool output, got: ${turn.content}"
    )
  }

  test("feeds malformed JSON parse errors back to the model") {
    var callCount = 0
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        callCount += 1
        if (callCount == 1) {
          "<tool_call>this is broken json</tool_call>"
        } else {
          doneMsg("Fixed it.")
        }
      }
    }

    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Do stuff")

    assert(callCount >= 2, s"model should get a second chance after parse error, got $callCount")

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _)) => text
    }
    val parseErrorFeedback = userTexts.find(_.contains("Tool call parse error"))

    assert(parseErrorFeedback.nonEmpty, "parse error should be fed back to the model")
    assert(parseErrorFeedback.exists(_.contains("Malformed")))
    assert(parseErrorFeedback.exists(_.contains("fix the JSON")))
  }

  test("retries when message_user arguments are invalid") {
    var callCount = 0
    val provider = new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        callCount += 1
        if (callCount == 1)
          """<tool_call>{"name":"message_user","arguments":{"msg":"bad"}}</tool_call>"""
        else
          doneMsg("Recovered.")
      }
    }

    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Do stuff")

    assertEquals(turn.content, "Recovered.")
    assert(
      callCount >= 2,
      s"model should get a second chance after invalid message_user args, got $callCount"
    )

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _)) => text
    }
    assert(userTexts.exists(_.contains("parse error")))
  }
}

class Module01Exercise04ReadFileTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-test-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private def findTool(name: ToolName): Tool =
    ToolRegistry.default.tools.find(_.name == name).get

  tmpDir.test("read_file reads an existing file") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-read.txt")
    Files.writeString(filePath, "contents here", StandardCharsets.UTF_8)
    val args   = Json.parseUnsafe(s"""{"path": "${filePath.toString}"}""")
    val result = findTool(ToolName.ReadFile).runWith(args)
    assertEquals(result, ToolResult.Success("contents here"))
  }

  test("read_file returns error for missing file") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("""{"path": "/nonexistent/path.txt"}""")
    val result = findTool(ToolName.ReadFile).runWith(args)
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error) =>
        assert(error.startsWith("reading file"))
        assert(!error.startsWith("Error:"))
    }
  }

  test("read_file returns invalid arguments error for bad args") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("{}")
    val result = findTool(ToolName.ReadFile).runWith(args)
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.contains("invalid arguments:"))
    }
  }
}

class Module01Exercise05ShellTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-shell-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private def findTool(name: ToolName): Tool =
    ToolRegistry.default.tools.find(_.name == name).get

  private val defaultConfig = AgentConfig.default

  private def initialMessages: Conversation =
    Conversation.initial(Exercises.buildSystemPrompt(defaultConfig, ToolRegistry.default.tools))

  test("shell runs a command and returns stdout") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("""{"command": "echo hello"}""")
    val result = findTool(ToolName.Shell).runWith(args)
    result match {
      case ToolResult.Success(output) => assertEquals(output.trim, "hello")
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
  }

  test("shell returns error for failing command") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("""{"command": "false"}""")
    val result = findTool(ToolName.Shell).runWith(args)
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.startsWith("(exit code"))
    }
  }

  test("shell returns invalid arguments error for bad args") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("{}")
    val result = findTool(ToolName.Shell).runWith(args)
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.contains("invalid arguments:"))
    }
  }

  tmpDir.test("shell runs with config.workDir as cwd through handleTurn") { dir =>
    val provider = new MockProvider(
      Seq(
        """<tool_call>{"name":"shell","arguments":{"command":"pwd"}}</tool_call>""",
        doneMsg("done")
      )
    )

    val turn = runTurn(
      provider,
      defaultConfig.copy(workDir = dir),
      ToolRegistry.default.tools,
      initialMessages,
      "show cwd",
      TerminalOutput.silent
    )

    val shellFeedback = turn.conversation.turns.collectFirst {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _))
          if text.startsWith("Tool shell returned:\n") =>
        text
    }

    assert(shellFeedback.nonEmpty, "shell result should be fed back to model")
    assert(shellFeedback.exists(_.contains(dir.toString)), s"shell should execute in $dir")
  }
}

class Module01Exercise06SystemPromptClausesTest extends munit.FunSuite {
  private val prompt =
    Exercises.buildSystemPrompt(AgentConfig.default, ToolRegistry.default.tools).toLowerCase

  test("gold prompt mentions one tool call per turn") {
    assert(
      prompt.contains("one tool") || prompt.contains("single tool") || prompt.contains("one action")
    )
  }

  test("gold prompt warns against predicting tool results") {
    assert(
      prompt.contains("never predict") || prompt.contains("not seen the output") || prompt.contains(
        "do not guess"
      )
    )
  }

  test("gold prompt mentions multi-turn loop") {
    assert(
      prompt.contains("multi-turn") || prompt.contains("iteration") || prompt.contains(
        "loop"
      ) || prompt.contains("another turn")
    )
  }

  test("naive prompt lacks defensive clauses") {
    val naive = Exercises.NaiveSystemPrompt.toLowerCase
    assert(
      !naive.contains("never predict") && !naive.contains("one tool call") && !naive.contains(
        "multi-turn"
      )
    )
  }
}

class Module01Exercise07HardenLoopTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-loop-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private val defaultConfig = AgentConfig.default

  private def initialMessages: Conversation =
    Conversation.initial(Exercises.buildSystemPrompt(defaultConfig, ToolRegistry.default.tools))

  private def jsonPath(path: Path): String =
    path.toString.replace("\\", "\\\\")

  test("handles flat format without arguments wrapper") {
    val text =
      """<tool_call>{"name": "write_file", "path": "foo.txt", "content": "bar"}</tool_call>"""
    val (calls, _) = Exercises.parseToolCalls(text)
    assertEquals(calls.length, 1)
    assertEquals(calls(0).name, "write_file")
    assertEquals(calls(0).arguments.get("path").as[String].toOption, Some("foo.txt"))
    assertEquals(calls(0).arguments.get("content").as[String].toOption, Some("bar"))
  }

  test("passes short output unchanged") {
    val output = "short output"
    assertEquals(Exercises.truncateOutput(output), output)
  }

  test("truncates long output with marker") {
    val extra  = 25
    val output = "x" * (Exercises.MaxOutputChars + extra)
    val result = Exercises.truncateOutput(output)

    assert(result.startsWith("x" * Exercises.MaxOutputChars))
    assert(result.contains(s"[truncated — $extra more chars]"))
  }

  tmpDir.test("truncates large tool output") { dir =>
    val file         = dir.resolve("f.txt")
    val largeContent = "x" * (Exercises.MaxOutputChars + 5000)
    Files.writeString(file, largeContent, StandardCharsets.UTF_8)
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"read_file","arguments":{"path":"${jsonPath(file)}"}}</tool_call>""",
        doneMsg("Done")
      )
    )

    val turn =
      runTurn(
        provider,
        defaultConfig,
        ToolRegistry.default.tools,
        initialMessages,
        "Read the large file",
        TerminalOutput.silent
      )

    val userTexts = turn.conversation.turns.collect {
      case TurnMessage(Message.UserMessage(Content.TextContent(text), _)) => text
    }
    assert(userTexts.exists(_.contains("[truncated")))
  }

  tmpDir.test("truncates assistant message history at first tool_call") { dir =>
    val first  = dir.resolve("first.txt")
    val second = dir.resolve("second.txt")
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            first
          )}","content":"first"}}</tool_call>
            |Extra text that should be truncated
            |<tool_call>{"name":"write_file","arguments":{"path":"${jsonPath(
            second
          )}","content":"second"}}</tool_call>""".stripMargin,
        doneMsg("Done.")
      )
    )

    val turn =
      runTurn(
        provider,
        defaultConfig,
        ToolRegistry.default.tools,
        initialMessages,
        "Create two files",
        TerminalOutput.silent
      )

    val assistantMessages = turn.conversation.turns.collect {
      case TurnMessage(Message.AssistantMessage(content, _, _)) =>
        content
    }

    assistantMessages.foreach { content =>
      assert(
        !content.contains("Extra text"),
        s"Assistant message should not contain 'Extra text': $content"
      )
    }

    assistantMessages.foreach { content =>
      val toolCallCount = content.sliding("<tool_call>".length).count(_.mkString == "<tool_call>")
      assert(
        toolCallCount <= 1,
        s"Assistant message should contain at most 1 <tool_call> tag, found $toolCallCount: $content"
      )
    }
  }
}

class Module01Exercise09SudokuTest extends munit.FunSuite {
  private val validGrid: Array[Array[Int]] = Array(
    Array(5, 3, 4, 6, 7, 8, 9, 1, 2),
    Array(6, 7, 2, 1, 9, 5, 3, 4, 8),
    Array(1, 9, 8, 3, 4, 2, 5, 6, 7),
    Array(8, 5, 9, 7, 6, 1, 4, 2, 3),
    Array(4, 2, 6, 8, 5, 3, 7, 9, 1),
    Array(7, 1, 3, 9, 2, 4, 8, 5, 6),
    Array(9, 6, 1, 5, 3, 7, 2, 8, 4),
    Array(2, 8, 7, 4, 1, 9, 6, 3, 5),
    Array(3, 4, 5, 2, 8, 6, 1, 7, 9)
  )

  test("valid grid returns empty") {
    assertEquals(Exercises.verifySudoku(validGrid), Vector.empty[String])
  }

  test("detects row violation") {
    val bad = validGrid.map(_.clone)
    bad(0)(0) = bad(0)(1)
    assert(Exercises.verifySudoku(bad).exists(_.startsWith("Row 1")))
  }

  test("detects column violation") {
    val bad = validGrid.map(_.clone)
    bad(0)(0) = bad(1)(0)
    assert(Exercises.verifySudoku(bad).exists(_.startsWith("Col 1")))
  }

  test("detects box violation") {
    val bad = validGrid.map(_.clone)
    bad(0)(0) = bad(1)(1)
    assert(Exercises.verifySudoku(bad).exists(_.startsWith("Box (1,1)")))
  }

  test("parseSudokuGrid parses space-separated text") {
    val text   = validGrid.map(_.mkString(" ")).mkString("\n")
    val parsed = Exercises.parseSudokuGrid(text)
    assert(parsed.isDefined)
    parsed.foreach(g =>
      (0 until 9).foreach(i => assertEquals(g(i).toVector, validGrid(i).toVector))
    )
  }

  test("parseSudokuGrid returns None for incomplete") {
    assertEquals(Exercises.parseSudokuGrid("1 2 3\n4 5 6"), None)
  }
}

class Module01Exercise10FabricationTest extends munit.FunSuite {
  private class JudgeProvider(verdict: String) extends ChatProvider {
    var capturedPrompt: String = ""
    def complete(
      messages: Vector[sttp.ai.openai.requests.completions.chat.message.Message]
    ): String = {
      messages.headOption.foreach {
        case sttp.ai.openai.requests.completions.chat.message.Message
              .UserMessage(Content.TextContent(text), _) =>
          capturedPrompt = text
        case _ =>
      }
      verdict
    }
  }

  test("returns SPECIFIED when judge says SPECIFIED") {
    assertEquals(
      Exercises.evaluateResponse(new JudgeProvider("SPECIFIED"), "here are 5 records"),
      "SPECIFIED"
    )
  }

  test("returns UNSPECIFIED when judge says UNSPECIFIED") {
    assertEquals(
      Exercises.evaluateResponse(new JudgeProvider("UNSPECIFIED"), "I can't"),
      "UNSPECIFIED"
    )
  }

  test("handles case-insensitive verdict") {
    assertEquals(
      Exercises.evaluateResponse(new JudgeProvider("  specified  "), "fake"),
      "SPECIFIED"
    )
  }

  test("sends agent response in judge prompt") {
    val provider = new JudgeProvider("SPECIFIED")
    Exercises.evaluateResponse(provider, "AGENT_MARKER")
    assert(provider.capturedPrompt.contains("AGENT_MARKER"))
  }
}

class Module01Exercise11EditFileTest extends munit.FunSuite {

  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory("agent-test-scala-"),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  private def findTool(name: ToolName): Tool =
    ToolRegistry.default.tools.find(_.name == name).get

  private val defaultConfig = AgentConfig.default

  private def initialMessages: Conversation =
    Conversation.initial(Exercises.buildSystemPrompt(defaultConfig, ToolRegistry.default.tools))

  private def jsonPath(path: Path): String =
    path.toString.replace("\\", "\\\\")

  tmpDir.test("edit_file replaces text in range") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-success.txt")
    Files.writeString(filePath, "alpha\nbeta\ngamma\ndelta", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":2,"line_end":3,"old_text":"beta\\ngamma","new_text":"BETA\\nGAMMA"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => assert(output.startsWith("Successfully edited"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
    assertEquals(
      Files.readString(filePath, StandardCharsets.UTF_8),
      "alpha\nBETA\nGAMMA\ndelta"
    )
  }

  tmpDir.test("edit_file leaves file unmodified when not found") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-not-found.txt")
    val original = "one\ntwo\nthree"
    Files.writeString(filePath, original, StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":1,"line_end":2,"old_text":"missing","new_text":"replacement"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.contains("old_text not found"))
    }
    assertEquals(Files.readString(filePath, StandardCharsets.UTF_8), original)
  }

  tmpDir.test("edit_file rejects invalid range") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-invalid-range.txt")
    Files.writeString(filePath, "line1\nline2", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":5,"line_end":10,"old_text":"line1","new_text":"LINE1"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error) =>
        assert(error.startsWith("line_start 5 is out of range for file with"))
    }
    assertEquals(Files.readString(filePath, StandardCharsets.UTF_8), "line1\nline2")
  }

  tmpDir.test("edit_file rejects line_end beyond file length") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-line-end-range.txt")
    Files.writeString(filePath, "line1\nline2\n", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":1,"line_end":10,"old_text":"line1","new_text":"LINE1"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.contains("line_end 10 is out of range"))
    }
  }

  tmpDir.test("edit_file handles empty content") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-empty.txt")
    Files.writeString(filePath, "", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":1,"line_end":1,"old_text":"","new_text":"inserted"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => assert(output.startsWith("Successfully edited"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
    assertEquals(Files.readString(filePath, StandardCharsets.UTF_8), "inserted")
  }

  tmpDir.test("edit_file replaces only first of multiple matches") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-multiple.txt")
    Files.writeString(filePath, "foo foo foo\n", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":1,"line_end":1,"old_text":"foo","new_text":"bar"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => assert(output.startsWith("Successfully edited"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
    assertEquals(Files.readString(filePath, StandardCharsets.UTF_8), "bar foo foo\n")
  }

  tmpDir.test("edit_file inserts at line when old_text is empty") { dir =>
    import zio.blocks.schema.json.Json
    val filePath = dir.resolve("test-edit-insert.txt")
    Files.writeString(filePath, "beta\ngamma\n", StandardCharsets.UTF_8)

    val args = Json.parseUnsafe(
      s"""{"path":"${filePath.toString}","line_start":1,"line_end":1,"old_text":"","new_text":"alpha\\n"}"""
    )
    val result = findTool(ToolName.EditFile).runWith(args)

    result match {
      case ToolResult.Success(output) => assert(output.startsWith("Successfully edited"))
      case ToolResult.Failure(error)  => fail(s"expected success, got: $error")
    }
    assertEquals(Files.readString(filePath, StandardCharsets.UTF_8), "alpha\nbeta\ngamma\n")
  }

  test("edit_file returns invalid arguments error for bad args") {
    import zio.blocks.schema.json.Json
    val args   = Json.parseUnsafe("{}")
    val result = findTool(ToolName.EditFile).runWith(args)
    result match {
      case ToolResult.Success(output) => fail(s"expected failure, got: $output")
      case ToolResult.Failure(error)  => assert(error.contains("invalid arguments:"))
    }
  }

  tmpDir.test("edit_file through the loop") { dir =>
    val file = dir.resolve("e.txt")
    Files.writeString(file, "line1\nold_value\nline3\n", StandardCharsets.UTF_8)
    val provider = new MockProvider(
      Seq(
        s"""<tool_call>{"name":"edit_file","arguments":{"path":"${jsonPath(
            file
          )}","line_start":2,"line_end":2,"old_text":"old_value","new_text":"new_value"}}</tool_call>""",
        doneMsg("Done")
      )
    )

    val turn =
      runTurn(provider, defaultConfig, ToolRegistry.default.tools, initialMessages, "Edit the file")

    assert(turn.toolCalls.contains(ToolName.EditFile))
    assertEquals(
      Files.readString(file, StandardCharsets.UTF_8),
      "line1\nnew_value\nline3\n"
    )
  }
}

class Module01Exercise12ListFilesTest extends munit.FunSuite {
  val tmpDir = FunFixture[Path](
    setup = _ => {
      val dir = Files.createTempDirectory("ex12-")
      Files.writeString(dir.resolve("a.txt"), "a")
      Files.writeString(dir.resolve("b.txt"), "b")
      val sub = Files.createDirectory(dir.resolve("sub"))
      Files.writeString(sub.resolve("c.txt"), "c")
      Files.createDirectory(sub.resolve("deep"))
      Files.writeString(sub.resolve("deep").resolve("d.txt"), "d")
      dir
    },
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )

  tmpDir.test("lists immediate children at depth 1") { dir =>
    Exercises.executeListFiles(Tool.ListFiles.Params(dir.toString, max_depth = 1)) match {
      case ToolResult.Success(output) =>
        assert(output.contains("a.txt"))
        assert(output.contains("b.txt"))
        assert(output.contains("sub/"))
        assert(!output.contains("c.txt"))
      case ToolResult.Failure(err) => fail(s"unexpected failure: $err")
    }
  }

  tmpDir.test("lists recursively at depth 2") { dir =>
    Exercises.executeListFiles(Tool.ListFiles.Params(dir.toString, max_depth = 2)) match {
      case ToolResult.Success(output) =>
        assert(output.contains("sub/c.txt"))
        assert(output.contains("sub/deep/"))
        assert(!output.contains("sub/deep/d.txt"))
      case ToolResult.Failure(err) => fail(s"unexpected failure: $err")
    }
  }

  test("returns error for missing directory") {
    Exercises.executeListFiles(Tool.ListFiles.Params("/nonexistent/dir", max_depth = 1)) match {
      case ToolResult.Failure(_) => ()
      case ToolResult.Success(_) => fail("expected failure")
    }
  }

  tmpDir.test("returns sorted output") { dir =>
    Exercises.executeListFiles(Tool.ListFiles.Params(dir.toString, max_depth = 1)) match {
      case ToolResult.Success(output) =>
        val lines = output.linesIterator.toVector
        assertEquals(lines(0), "a.txt")
        assertEquals(lines(1), "b.txt")
        assertEquals(lines(2), "sub/")
      case ToolResult.Failure(err) => fail(s"unexpected failure: $err")
    }
  }
}
