package workshop.agent

import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

private abstract class Module02FunSuite(prefix: String) extends munit.FunSuite {
  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory(prefix),
    teardown = dir =>
      if (Files.exists(dir))
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )
}

private def writeUtf8(path: Path, content: String): Unit = {
  Option(path.getParent).foreach(parent => Files.createDirectories(parent))
  Files.writeString(path, content, StandardCharsets.UTF_8)
}

private def user(text: String): TurnMessage      = TurnMessage.user(text)
private def assistant(text: String): TurnMessage = TurnMessage.assistant(text)

private def conversation(systemPrompt: String, turns: TurnMessage*): Conversation =
  Conversation(systemPrompt, turns.toVector)

private def conversationPairs(conversation: Conversation): Vector[(String, String)] =
  conversation.turns.map(turn => (turn.role, turn.text))

private def writeSessionJson(
  historyDir: Path,
  sessionId: String,
  timestamp: String,
  turns: Vector[(String, String)],
  model: String = "test-model",
  systemPrompt: String = "saved system prompt"
): Unit = {
  val stored = StoredSession(
    timestamp = timestamp,
    workDir = historyDir.toString,
    model = model,
    systemPrompt = systemPrompt,
    turns = turns.map { case (role, content) => StoredTurn(role, content) }
  )
  Files.createDirectories(historyDir)
  writeUtf8(historyDir.resolve(s"$sessionId.json"), stored.encode)
}

private def stubTool(toolName: ToolName): Tool = new Tool {
  type Input = Tool.ReadFile.Params
  val name        = toolName
  val description = s"${toolName.wire} tool"
  val inputSchema = summon[zio.blocks.schema.Schema[Tool.ReadFile.Params]]
  def execute(p: Tool.ReadFile.Params, agent: Agent): ToolResult = ToolResult.Success("ok")
}

private final class CapturingMemoryProvider(response: String) extends ChatProvider {
  var capturedMessages: Vector[Message] = Vector.empty

  def complete(messages: Vector[Message]): String = {
    capturedMessages = messages
    response
  }
}

class Module02Exercise01LoadInstructionsTest extends Module02FunSuite("m02-ex01-") {

  tmpDir.test("returns None when no instructions exist") { dir =>
    assertEquals(Exercises.loadInstructions(dir, None), None)
  }

  tmpDir.test("loads content from explicit instructionsPath") { dir =>
    Files.createDirectories(dir.resolve("docs"))
    writeUtf8(dir.resolve("docs/custom.md"), "Follow the project rules.")

    assertEquals(
      Exercises.loadInstructions(dir, Some("docs/custom.md")),
      Some("Follow the project rules.")
    )
  }

  tmpDir.test("returns None when explicit instructions file is missing") { dir =>
    assertEquals(Exercises.loadInstructions(dir, Some("nonexistent.md")), None)
  }

  tmpDir.test("walks up directory tree and concatenates root first") { dir =>
    val nested = dir.resolve("src/main")
    Files.createDirectories(nested)
    writeUtf8(dir.resolve("AGENTS.md"), "Root instructions")
    writeUtf8(dir.resolve("src/AGENTS.md"), "Nested instructions")

    assertEquals(
      Exercises.loadInstructions(nested, None),
      Some("Root instructions\n\nNested instructions")
    )
  }

  tmpDir.test("returns None for an empty nested directory tree") { dir =>
    val nested = dir.resolve("a/b/c")
    Files.createDirectories(nested)
    assertEquals(Exercises.loadInstructions(nested, None), None)
  }
}

class Module02Exercise02DiscoverSkillsTest extends Module02FunSuite("m02-ex02-") {

  tmpDir.test("returns empty when skillsDir is None") { _ =>
    assertEquals(Exercises.discoverSkills(None), Vector.empty)
  }

  tmpDir.test("returns empty when skillsDir does not exist") { dir =>
    val missingDir = dir.resolve("missing-skills-dir")
    assertEquals(Exercises.discoverSkills(Some(missingDir)), Vector.empty)
  }

  tmpDir.test("discovers skills with parsed name and description") { dir =>
    val skillsDir = dir.resolve("skills")
    writeUtf8(
      skillsDir.resolve("review/SKILL.md"),
      """---
        |name: Code Review
        |description: "Review changes carefully"
        |---
        |
        |Use a checklist.
        |""".stripMargin
    )
    writeUtf8(
      skillsDir.resolve("testing/SKILL.md"),
      """---
        |name: Testing
        |description: Add focused regression tests
        |---
        |
        |Prefer small tests.
        |""".stripMargin
    )

    val skills = Exercises.discoverSkills(Some(skillsDir))

    assertEquals(skills.map(_.name), Vector("Code Review", "Testing"))
    assertEquals(
      skills.map(_.description),
      Vector("Review changes carefully", "Add focused regression tests")
    )
  }

  tmpDir.test("ignores subdirectories without SKILL.md") { dir =>
    val skillsDir = dir.resolve("skills")
    Files.createDirectories(skillsDir.resolve("empty-skill"))
    writeUtf8(
      skillsDir.resolve("real-skill/SKILL.md"),
      """---
        |name: Real Skill
        |description: This one counts
        |---
        |
        |Content
        |""".stripMargin
    )

    val skills = Exercises.discoverSkills(Some(skillsDir))

    assertEquals(skills.map(_.name), Vector("Real Skill"))
  }

  tmpDir.test("sorts skills by path instead of parsed name") { dir =>
    val skillsDir = dir.resolve("skills")
    val aDir      = skillsDir.resolve("a-first")
    val zDir      = skillsDir.resolve("z-second")

    writeUtf8(
      aDir.resolve("SKILL.md"),
      """---
        |name: Zulu
        |description: Path comes first
        |---
        |
        |Body A
        |""".stripMargin
    )
    writeUtf8(
      zDir.resolve("SKILL.md"),
      """---
        |name: Alpha
        |description: Metadata comes second
        |---
        |
        |Body Z
        |""".stripMargin
    )

    val skills = Exercises.discoverSkills(Some(skillsDir))
    assertEquals(skills.map(_.path), Vector(aDir.resolve("SKILL.md"), zDir.resolve("SKILL.md")))
  }

  tmpDir.test("loadSkillContent returns the full file content") { dir =>
    val skillPath = dir.resolve("skills/planning/SKILL.md")
    val content =
      """---
        |name: Planning
        |description: Plan first
        |---
        |
        |Write the plan down.
        |""".stripMargin
    writeUtf8(skillPath, content)

    assertEquals(Exercises.loadSkillContent(skillPath), content)
  }

  test("loadSkillContent returns empty string for missing file") {
    assertEquals(Exercises.loadSkillContent(java.nio.file.Paths.get("/nonexistent/SKILL.md")), "")
  }
}

class Module02Exercise03DiscoverCommandsTest extends Module02FunSuite("m02-ex03-") {

  tmpDir.test("returns empty when commandsDir is None") { _ =>
    assertEquals(Exercises.discoverCommands(None), Vector.empty)
  }

  tmpDir.test("discovers commands with parsed description and argument hint") { dir =>
    val commandsDir = dir.resolve("commands")
    val commandPath = commandsDir.resolve("code-review.md")
    writeUtf8(
      commandPath,
      """---
        |description: "Review code changes"
        |argument-hint: path/to/file
        |---
        |
        |Review $ARGUMENTS
        |""".stripMargin
    )

    val commands = Exercises.discoverCommands(Some(commandsDir))

    assertEquals(commands.length, 1)
    assertEquals(commands.head.name, "code-review")
    assertEquals(commands.head.description, "Review code changes")
    assertEquals(commands.head.argumentHint, Some("path/to/file"))
    assertEquals(commands.head.path, commandPath)
  }

  tmpDir.test("executeCommand substitutes $ARGUMENTS in the command body") { dir =>
    val commandPath = dir.resolve("commands/review.md")
    writeUtf8(
      commandPath,
      """---
        |description: Review files
        |---
        |
        |Review these targets: $ARGUMENTS
        |Then summarize $ARGUMENTS
        |""".stripMargin
    )

    val command = CommandPrompt("review", "Review files", None, commandPath)
    val result  = Exercises.executeCommand(command, "src/Main.scala")

    assertEquals(
      result.contains("Review these targets: src/Main.scala"),
      true,
      clue("expected result to contain substituted first command line")
    )
    assertEquals(
      result.contains("Then summarize src/Main.scala"),
      true,
      clue("expected result to contain substituted second command line")
    )
  }

  tmpDir.test("executeCommand strips YAML frontmatter from output") { dir =>
    val commandPath = dir.resolve("commands/fix.md")
    writeUtf8(
      commandPath,
      """---
        |description: Fix a bug
        |argument-hint: failing test
        |---
        |
        |Fix $ARGUMENTS now.
        |""".stripMargin
    )

    val result =
      Exercises.executeCommand(CommandPrompt("fix", "Fix a bug", None, commandPath), "test failure")

    assertEquals(result, "Fix test failure now.")
    assertEquals(
      result.contains("description:"),
      false,
      clue("expected stripped command output not to include frontmatter description")
    )
    assertEquals(
      result.contains("---"),
      false,
      clue("expected stripped command output not to include frontmatter delimiters")
    )
  }

  tmpDir.test("executeCommand preserves body formatting after stripping frontmatter") { dir =>
    val commandPath = dir.resolve("commands/template.md")
    writeUtf8(
      commandPath,
      "---\n" +
        "description: Keep formatting\n" +
        "---\n" +
        "\n" +
        "  Step 1\n" +
        "    Step 2\n" +
        "\n" +
        "$ARGUMENTS\n"
    )

    val result = Exercises.executeCommand(
      CommandPrompt("template", "Keep formatting", None, commandPath),
      "Done"
    )

    assertEquals(result, "  Step 1\n    Step 2\n\nDone")
  }

  tmpDir.test("normalizes empty argument-hint to None") { dir =>
    val commandsDir = dir.resolve("commands")
    writeUtf8(
      commandsDir.resolve("simple.md"),
      "---\ndescription: Simple\nargument-hint: \"\"\n---\nDo it."
    )
    val result = Exercises.discoverCommands(Some(commandsDir))
    assertEquals(result.head.argumentHint, None)
  }

  tmpDir.test("ignores non-markdown files in commands dir") { dir =>
    val commandsDir = dir.resolve("commands")
    writeUtf8(commandsDir.resolve("valid.md"), "---\ndescription: Valid\n---\nDo $ARGUMENTS.")
    writeUtf8(commandsDir.resolve("ignore.txt"), "not a command")
    val result = Exercises.discoverCommands(Some(commandsDir))
    assertEquals(result.length, 1)
    assertEquals(result.head.name, "valid")
  }

  test("executeCommand returns empty string for missing file") {
    val cmd = CommandPrompt("ghost", "", None, java.nio.file.Paths.get("/nonexistent/ghost.md"))
    assertEquals(Exercises.executeCommand(cmd, "args"), "")
  }
}

class Module02Exercise04MeasureContextTest extends munit.FunSuite {

  private val readTool  = stubTool(ToolName.ReadFile)
  private val shellTool = stubTool(ToolName.Shell)

  test("reports correct character counts for known inputs") {
    val systemPrompt = "system prompt"
    val conversationState = conversation(
      "ignored prompt",
      user("hello"),
      assistant("done")
    )
    val expectedTools = Vector(readTool, shellTool).map(_.formatForContext).map(_.length).sum

    val usage = Exercises.measureContext(
      systemPrompt,
      conversationState,
      Vector(readTool, shellTool),
      Some(200)
    )

    val expectedConversation = "hello".length + "done".length
    val expectedTotal        = systemPrompt.length + expectedConversation + expectedTools

    assertEquals(usage.system, systemPrompt.length)
    assertEquals(usage.conversation, expectedConversation)
    assertEquals(usage.tools, expectedTools)
    assertEquals(usage.total, expectedTotal)
  }

  test("computes percentage when a limit is set") {
    val usage = Exercises.measureContext(
      "abcd",
      conversation("ignored", user("xy")),
      Vector.empty,
      Some(12)
    )

    assertEquals(usage.percentage, Some(6.0 / 12.0))
  }

  test("returns None percentage when limit is None") {
    val usage = Exercises.measureContext(
      "abcd",
      conversation("ignored", user("xy")),
      Vector.empty,
      None
    )

    assertEquals(usage.percentage, None)
    assertEquals(usage.limit, None)
  }

  test("returns None percentage when limit is zero") {
    val usage = Exercises.measureContext(
      "abcd",
      conversation("ignored", user("xy")),
      Vector.empty,
      Some(0)
    )

    assertEquals(usage.percentage, None)
    assertEquals(usage.limit, Some(0))
  }

  test("empty conversation reports system and tool overhead only") {
    val usage = Exercises.measureContext("system", conversation("ignored"), Vector(readTool), None)
    val expectedTools = readTool.formatForContext.length

    assertEquals(usage.system, "system".length)
    assertEquals(usage.conversation, 0)
    assertEquals(usage.tools, expectedTools)
    assertEquals(usage.total, "system".length + expectedTools)
  }
}

class Module02Exercise05CompactionTest extends munit.FunSuite {

  test("formatConversationForCompaction strips system messages and formats role lines") {
    val result = Exercises.formatConversationForCompaction(
      conversation("secret system prompt", user("hello"), assistant("hi"))
    )

    assertEquals(result, "user: hello\n\nassistant: hi")
    assertEquals(
      result.contains("secret system prompt"),
      false,
      clue("expected compaction transcript to exclude the system prompt")
    )
  }

  test("applyCompaction replaces prior turns with the summary") {
    val result = Exercises.applyCompaction(
      conversation("system prompt", user("first"), assistant("second"), user("third")),
      "summary text"
    )

    assertEquals(result.turns.length, 2)
    assertEquals(
      conversationPairs(result).head,
      ("user", "[Context from previous conversation]\nsummary text")
    )
  }

  test("applyCompaction preserves the system prompt") {
    val result = Exercises.applyCompaction(
      conversation("system prompt", user("only turn")),
      "summary"
    )

    assertEquals(result.systemPrompt, "system prompt")
  }

  test("applyCompaction keeps user assistant alternation") {
    val result = Exercises.applyCompaction(
      conversation("system prompt", user("one"), assistant("two")),
      "summary"
    )

    assertEquals(conversationPairs(result).map(_._1), Vector("user", "assistant"))
  }
}

class Module02Exercise06ShouldAutoCompactTest extends munit.FunSuite {

  test("returns false when limit is None") {
    val usage = ContextUsage(
      system = 1,
      conversation = 2,
      tools = 3,
      total = 10,
      limit = None,
      percentage = None
    )
    assertEquals(Exercises.shouldAutoCompact(usage), false)
  }

  test("returns true when total exceeds the limit") {
    val usage = ContextUsage(
      system = 1,
      conversation = 2,
      tools = 3,
      total = 11,
      limit = Some(10),
      percentage = Some(1.1)
    )
    assertEquals(Exercises.shouldAutoCompact(usage), true)
  }

  test("returns false when total is within the limit") {
    val usage = ContextUsage(
      system = 1,
      conversation = 2,
      tools = 3,
      total = 10,
      limit = Some(10),
      percentage = Some(1.0)
    )
    assertEquals(Exercises.shouldAutoCompact(usage), false)
  }
}

class Module02Exercise07SaveSessionTest extends Module02FunSuite("m02-ex07-") {

  tmpDir.test("saveSession creates a JSON file in historyDir") { dir =>
    val historyDir = dir.resolve("history")
    Exercises.saveSession(
      historyDir,
      "session-1",
      conversation("saved prompt", user("hello"), assistant("done"))
    )

    val filePath = historyDir.resolve("session-1.json")
    val content  = Files.readString(filePath, StandardCharsets.UTF_8)

    assert(Files.exists(filePath))
    assert(content.contains("\"timestamp\""))
    assert(content.contains("\"workDir\""))
    assert(content.contains("\"model\""))
    assert(content.contains("\"systemPrompt\""))
    assert(content.contains("\"turns\""))
  }

  tmpDir.test("loadSession restores saved conversation turns") { dir =>
    val historyDir = dir.resolve("history")
    val original   = conversation("saved prompt", user("hello"), assistant("done"))
    Exercises.saveSession(historyDir, "session-2", original)
    val restored = Exercises.loadSession(historyDir, "session-2")

    assert(restored.nonEmpty)
    assertEquals(restored.map(_.systemPrompt), Some("saved prompt"))
    assertEquals(
      restored.map(conversationPairs),
      Some(Vector(("user", "hello"), ("assistant", "done")))
    )
  }

  tmpDir.test("save then load preserves turn order and content") { dir =>
    val historyDir = dir.resolve("history")
    val original = conversation(
      "saved prompt",
      user("first"),
      assistant("second"),
      user("third")
    )
    Exercises.saveSession(historyDir, "session-3", original)

    assertEquals(
      Exercises.loadSession(historyDir, "session-3").map(conversationPairs),
      Some(conversationPairs(original))
    )
  }

  tmpDir.test("loadSession returns None for missing file") { dir =>
    assertEquals(Exercises.loadSession(dir.resolve("history"), "missing"), None)
  }
}

class Module02Exercise08LoadPastSessionSummariesTest extends Module02FunSuite("m02-ex08-") {

  // Short inputs fit within sessionSummaryMaxChars and must NOT trigger an LLM
  // call. Tests pass this provider to verify the no-LLM-for-short-inputs path.
  private val unusedProvider = new ChatProvider {
    def complete(messages: Vector[Message]): String =
      throw new AssertionError("provider should not be called for short inputs")
  }

  // Returns a fixed summary regardless of input. Used for the long-input test
  // to verify the LLM summarisation path runs and the result is plumbed through.
  private final class FixedSummaryProvider(summary: String) extends ChatProvider {
    def complete(messages: Vector[Message]): String = summary
  }

  tmpDir.test("returns an empty string when no prior sessions exist") { dir =>
    val historyDir = dir.resolve("history")
    Files.createDirectories(historyDir)
    assertEquals(Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider), "")
  }

  tmpDir.test("formats prior sessions with timestamp and first user message") { dir =>
    val historyDir = dir.resolve("history")
    writeSessionJson(
      historyDir,
      sessionId = "older",
      timestamp = "2026-01-01T10:00:00Z",
      turns = Vector("user" -> "Fix auth bug", "assistant" -> "Working on it")
    )

    val summaries = Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider)

    assertEquals(summaries, "[2026-01-01T10:00:00Z] topic: Fix auth bug")
  }

  tmpDir.test("excludes the current session id") { dir =>
    val historyDir = dir.resolve("history")
    writeSessionJson(
      historyDir,
      sessionId = "current",
      timestamp = "2026-01-01T10:00:00Z",
      turns = Vector("user" -> "Current work")
    )
    writeSessionJson(
      historyDir,
      sessionId = "previous",
      timestamp = "2026-01-02T10:00:00Z",
      turns = Vector("user" -> "Previous work")
    )

    val summaries = Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider)

    assertEquals(
      summaries.contains("Current work"),
      false,
      clue("expected summaries to exclude current session topic")
    )
    assertEquals(
      summaries.contains("Previous work"),
      true,
      clue("expected summaries to include previous session topic")
    )
  }

  tmpDir.test("LLM-summarises long topics; provider response is capped at maxChars") { dir =>
    val historyDir = dir.resolve("history")
    val longTopic  = "a".repeat(90) // exceeds the 80-char default cap
    val provider   = new FixedSummaryProvider("Investigating ninety-A topic preview")

    writeSessionJson(
      historyDir,
      sessionId = "previous",
      timestamp = "2026-01-03T10:00:00Z",
      turns = Vector("user" -> longTopic)
    )

    assertEquals(
      Exercises.loadPastSessionSummaries(historyDir, "current", provider),
      "[2026-01-03T10:00:00Z] topic: Investigating ninety-A topic preview"
    )
  }

  tmpDir.test("truncateSingleLine still caps an LLM response that overshoots") { dir =>
    val historyDir = dir.resolve("history")
    val longTopic  = "a".repeat(90)
    val provider   = new FixedSummaryProvider("X".repeat(120)) // model overshoots the request

    writeSessionJson(
      historyDir,
      sessionId = "previous",
      timestamp = "2026-01-03T10:00:00Z",
      turns = Vector("user" -> longTopic)
    )

    val expectedTopic = "X".repeat(77) + "..."
    assertEquals(expectedTopic.length, 80)
    assertEquals(
      Exercises.loadPastSessionSummaries(historyDir, "current", provider),
      s"[2026-01-03T10:00:00Z] topic: $expectedTopic"
    )
  }

  tmpDir.test("skips sessions with no user turns") { dir =>
    val historyDir = dir.resolve("history")
    writeSessionJson(
      historyDir,
      sessionId = "assistant-only",
      timestamp = "2026-01-03T10:00:00Z",
      turns = Vector("assistant" -> "Only assistant content")
    )

    assertEquals(Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider), "")
  }

  tmpDir.test("skips sessions with no user turns when other sessions are valid") { dir =>
    val historyDir = dir.resolve("history")
    writeSessionJson(
      historyDir,
      sessionId = "assistant-only",
      timestamp = "2026-01-01T09:00:00.000Z",
      turns = Vector("assistant" -> "No user turn here")
    )
    writeSessionJson(
      historyDir,
      sessionId = "session-b",
      timestamp = "2026-01-02T09:00:00.000Z",
      turns = Vector("user" -> "Include me")
    )

    val summaries = Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider)
    assert(summaries.contains("[2026-01-02T09:00:00.000Z] topic: Include me"))
    assert(!summaries.contains("[2026-01-01T09:00:00.000Z]"))
  }

  tmpDir.test("handles historyDir with no json files") { dir =>
    val historyDir = dir.resolve("history")
    Files.createDirectories(historyDir)
    writeUtf8(historyDir.resolve("notes.txt"), "ignore me")

    assertEquals(Exercises.loadPastSessionSummaries(historyDir, "current", unusedProvider), "")
  }
}

class Module02Exercise09ExtractMemoriesTest extends Module02FunSuite("m02-ex09-") {

  tmpDir.test("appends extracted facts to the memory file") { dir =>
    val provider = new CapturingMemoryProvider("Prefers Scala 3\nUses immutable data")

    Exercises.extractMemories(provider, "Please use Scala 3.", "Will do.", dir)

    val memoryPath = dir.resolve(".agent/memories.md")
    val content    = Files.readString(memoryPath, StandardCharsets.UTF_8)

    assertEquals(content, "- Prefers Scala 3\n- Uses immutable data\n")
    val prompt = provider.capturedMessages
      .collectFirst { case Message.User(Content.TextContent(text), _) =>
        text
      }
      .getOrElse("")
    assert(prompt.contains("Please use Scala 3."))
    assert(prompt.contains("Will do."))
    assert(prompt.contains("NONE"))
  }

  tmpDir.test("uses the unified extraction prompt text") { dir =>
    val provider = new CapturingMemoryProvider("NONE")

    Exercises.extractMemories(provider, "User prefers Scala", "Acknowledged", dir)

    val prompt = provider.capturedMessages
      .collectFirst { case Message.User(Content.TextContent(text), _) =>
        text
      }
      .getOrElse("")

    assertEquals(
      prompt,
      """Extract durable facts worth remembering from this conversation turn.
        |Focus on: user preferences, project conventions, technical decisions, recurring patterns.
        |
        |User message:
        |User prefers Scala
        |
        |Agent response:
        |Acknowledged
        |
        |Return one fact per line. If nothing is worth remembering, return exactly NONE.""".stripMargin
    )
  }

  tmpDir.test("does nothing when the provider returns NONE") { dir =>
    Exercises.extractMemories(new CapturingMemoryProvider("NONE"), "hello", "hi", dir)
    assert(!Files.exists(dir.resolve(".agent/memories.md")))
  }

  tmpDir.test("does nothing when the provider returns NONE case-insensitively") { dir =>
    Exercises.extractMemories(new CapturingMemoryProvider("none"), "hello", "hi", dir)
    assert(!Files.exists(dir.resolve(".agent/memories.md")))
  }

  tmpDir.test("creates the memory file when it does not exist") { dir =>
    Exercises.extractMemories(
      new CapturingMemoryProvider("Stores user preferences"),
      "hello",
      "hi",
      dir
    )
    assert(Files.exists(dir.resolve(".agent/memories.md")))
  }

  tmpDir.test("appends to an existing memory file instead of overwriting it") { dir =>
    val memoryPath = dir.resolve(".agent/memories.md")
    writeUtf8(memoryPath, "- Existing fact\n")

    Exercises.extractMemories(new CapturingMemoryProvider("New fact"), "hello", "hi", dir)

    assertEquals(
      Files.readString(memoryPath, StandardCharsets.UTF_8),
      "- Existing fact\n- New fact\n"
    )
  }

  tmpDir.test("strips both dash and star bullets from extracted facts") { dir =>
    Exercises.extractMemories(
      new CapturingMemoryProvider("- Prefers Scala 3\n* Runs tests"),
      "hello",
      "hi",
      dir
    )

    assertEquals(
      Files.readString(dir.resolve(".agent/memories.md"), StandardCharsets.UTF_8),
      "- Prefers Scala 3\n- Runs tests\n"
    )
  }

  tmpDir.test("keeps only valid facts when output mixes NONE lines and empty bullets") { dir =>
    Exercises.extractMemories(
      new CapturingMemoryProvider(
        """- Prefers Scala 3
          |NONE
          |* 
          |* Runs tests before commit
          |  none
          |- 
          |Uses munit
          |""".stripMargin
      ),
      "Remember preferences",
      "Done",
      dir
    )

    assertEquals(
      Files.readString(dir.resolve(".agent/memories.md"), StandardCharsets.UTF_8),
      "- Prefers Scala 3\n- Runs tests before commit\n- Uses munit\n"
    )
  }
}

class Module02Exercise10LoadMemoriesTest extends Module02FunSuite("m02-ex10-") {

  tmpDir.test("returns content from an existing memory file") { dir =>
    val memoryPath = dir.resolve(".agent/memories.md")
    writeUtf8(memoryPath, "- Prefers concise answers\n")

    assertEquals(Exercises.loadMemories(dir), Some("- Prefers concise answers\n"))
  }

  tmpDir.test("returns None when the memory file is missing") { dir =>
    assertEquals(Exercises.loadMemories(dir), None)
  }

  tmpDir.test("preserves existing memory formatting") { dir =>
    val memoryPath = dir.resolve(".agent/memories.md")
    val content    = "- Uses Scala\n- Likes tests\n"
    writeUtf8(memoryPath, content)

    assertEquals(Exercises.loadMemories(dir), Some(content))
  }
}
