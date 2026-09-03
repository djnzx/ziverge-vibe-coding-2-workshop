package workshop.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import sttp.ai.openai.requests.completions.chat.message.Message
import zio.blocks.schema.json.Json

abstract class Module03FunSuite(prefix: String) extends munit.FunSuite {
  val tmpDir = FunFixture[Path](
    setup = _ => Files.createTempDirectory(prefix),
    teardown =
      dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  )
  protected def writeUtf8(path: Path, content: String): Unit = {
    if (path.getParent != null) Files.createDirectories(path.getParent)
    Files.writeString(path, content, StandardCharsets.UTF_8)
  }
}

class Module03Exercise01CheckToolPermissionTest extends munit.FunSuite {
  test("allows all when both empty") {
    assert(Exercises.checkToolPermission("shell", "ls", Vector.empty, Vector.empty).allowed)
  }

  test("allow only filters") {
    assert(Exercises.checkToolPermission("shell", "ls", Vector("shell"), Vector.empty).allowed)
    assert(!Exercises.checkToolPermission("read_file", "x", Vector("shell"), Vector.empty).allowed)
  }

  test("deny blocks matching") {
    assert(
      Exercises.checkToolPermission("shell", "ls", Vector.empty, Vector("shell(rm *)")).allowed
    )
    assert(
      !Exercises
        .checkToolPermission("shell", "rm -rf /", Vector.empty, Vector("shell(rm *)"))
        .allowed
    )
  }

  test("deny vetoes allow") {
    assert(
      !Exercises
        .checkToolPermission("shell", "rm -rf /", Vector("shell"), Vector("shell(rm *)"))
        .allowed
    )
  }

  test("message_user always allowed") {
    assert(
      Exercises
        .checkToolPermission("message_user", "hi", Vector.empty, Vector("message_user"))
        .allowed
    )
  }

  test("returns reason") {
    assert(
      Exercises
        .checkToolPermission("shell", "rm -rf", Vector.empty, Vector("shell(rm *)"))
        .reason
        .nonEmpty
    )
  }
}

class Module03Exercise02EnforceSandboxTest extends Module03FunSuite("m03-ex02-") {
  test("allows path within workDir") {
    assert(
      Exercises
        .enforceSandbox(
          "read_file",
          Json.parseUnsafe("""{"path":"foo.txt"}"""),
          java.nio.file.Paths.get("/home/user/project"),
          Vector.empty
        )
        .allowed
    )
  }

  test("denies path escape") {
    assert(
      !Exercises
        .enforceSandbox(
          "read_file",
          Json.parseUnsafe("""{"path":"../../etc/passwd"}"""),
          java.nio.file.Paths.get("/home/user/project"),
          Vector.empty
        )
        .allowed
    )
  }

  test("denies write to protected") {
    assert(
      !Exercises
        .enforceSandbox(
          "write_file",
          Json.parseUnsafe("""{"path":"secret.key"}"""),
          java.nio.file.Paths.get("/home/user/project"),
          Vector("secret.key")
        )
        .allowed
    )
  }

  test("allows read of protected") {
    assert(
      Exercises
        .enforceSandbox(
          "read_file",
          Json.parseUnsafe("""{"path":"secret.key"}"""),
          java.nio.file.Paths.get("/home/user/project"),
          Vector("secret.key")
        )
        .allowed
    )
  }

  test("allows non-file tools") {
    assert(
      Exercises
        .enforceSandbox(
          "shell",
          Json.parseUnsafe("""{"command":"rm -rf /"}"""),
          java.nio.file.Paths.get("/home/user"),
          Vector.empty
        )
        .allowed
    )
  }

  test("analyzeShellSandbox allowed on no") {
    val provider = new ChatProvider { def complete(m: Vector[Message]) = "no" }
    assert(Exercises.analyzeShellSandbox(provider, "ls", java.nio.file.Paths.get("/home")).allowed)
  }

  test("analyzeShellSandbox denied on yes") {
    val provider = new ChatProvider { def complete(m: Vector[Message]) = "yes" }
    assert(
      !Exercises
        .analyzeShellSandbox(provider, "cat /etc/passwd", java.nio.file.Paths.get("/home"))
        .allowed
    )
  }

  test("analyzeShellSandbox denied on unknown") {
    val provider = new ChatProvider { def complete(m: Vector[Message]) = "unknown" }
    assert(
      !Exercises
        .analyzeShellSandbox(provider, "eval stuff", java.nio.file.Paths.get("/home"))
        .allowed
    )
  }

  test("analyzeShellSandbox denied on unexpected response") {
    val provider = new ChatProvider { def complete(m: Vector[Message]) = "maybe" }
    assert(!Exercises.analyzeShellSandbox(provider, "ls", java.nio.file.Paths.get("/home")).allowed)
  }
}

class Module03Exercise03RedactSecretsTest extends munit.FunSuite {
  test("redacts API key") {
    val result = Exercises.redactSecrets("key=sk-abc123def456ghi789", Vector("sk-[a-zA-Z0-9]{10,}"))
    assert(result.contains("[REDACTED]"))
    assert(!result.contains("sk-abc123"))
  }

  test("leaves clean text") {
    assertEquals(Exercises.redactSecrets("hello", Vector("sk-[a-zA-Z0-9]{10,}")), "hello")
  }

  test("redacts multiple patterns") {
    val result = Exercises.redactSecrets(
      "api_key=sk-abc123def456 password=hunter2",
      Vector("sk-[a-zA-Z0-9]{10,}", "password\\s*=\\s*\\S+")
    )
    assert(!result.contains("sk-abc123"))
    assert(!result.contains("hunter2"))
  }

  test("handles invalid regex") {
    assertEquals(Exercises.redactSecrets("safe", Vector("[invalid")), "safe")
  }
}

class Module03Exercise04LogAuditEventTest extends Module03FunSuite("m03-ex04-") {
  tmpDir.test("appends JSON line") { dir =>
    val logPath = dir.resolve("audit.jsonl")
    Exercises.logAuditEvent(
      logPath,
      AuditEvent("2024-01-01T00:00:00Z", "tool_call", Map("tool" -> "shell"))
    )
    val parsed = Json.parseUnsafe(Files.readString(logPath).trim)

    assertEquals(
      parsed.get("event").as[String].toOption,
      Some("tool_call"),
      clue("expected event field to be present and equal to tool_call")
    )
    assertEquals(
      parsed.get("timestamp").as[String].toOption,
      Some("2024-01-01T00:00:00Z"),
      clue("expected timestamp field to be present and equal to provided timestamp")
    )
  }

  tmpDir.test("creates file if missing") { dir =>
    val logPath = dir.resolve("sub").resolve("audit.jsonl")
    Exercises.logAuditEvent(logPath, AuditEvent("t", "tool_call", Map.empty))
    assert(Files.exists(logPath))
  }

  tmpDir.test("multiple events multiple lines") { dir =>
    val logPath = dir.resolve("audit.jsonl")
    Exercises.logAuditEvent(logPath, AuditEvent("t1", "tool_call", Map.empty))
    Exercises.logAuditEvent(logPath, AuditEvent("t2", "tool_result", Map.empty))
    assertEquals(Files.readString(logPath).trim.linesIterator.size, 2)
  }
}

class Module03Exercise05ToolDecoratorsTest extends Module03FunSuite("m03-ex05-") {
  import scala.concurrent.Future

  private def runWith(decorators: Vector[ToolDecorator], responses: String*): TurnResult = {
    val agent = Agent(
      state = AgentState(Conversation.initial(""), SessionId.parse("test-decorators")),
      config = AgentConfig.default.copy(maxIterations = responses.size),
      tools = ToolRegistry.default.tools,
      provider = new MockProvider(responses)
    )
    Exercises.handleTurn(agent, "test", TerminalOutput.silent, decorators)._2
  }

  test("empty decorator list leaves behavior unchanged") {
    val result = runWith(Vector.empty, doneMsg("done"))
    assertEquals(result.content, "done")
    assertEquals(result.toolCalls, Vector(ToolName.MessageUser))
  }

  tmpDir.test("before deny blocks execution and feeds the reason back") { dir =>
    val target = dir.resolve("blocked.txt")
    val decorator = ToolDecorator(
      name = "deny-write",
      appliesTo = (call, _) => call.name == "write_file",
      before = Some((_, _) => Future.successful(DecoratorOutcome.Deny("write forbidden")))
    )
    val call =
      s"""<tool_call>{"name":"write_file","arguments":{"path":"$target","content":"bad"}}</tool_call>"""
    val result     = runWith(Vector(decorator), call, doneMsg("aborted"))
    val transcript = result.conversation.turns.map(_.text).mkString("\n")

    assert(!Files.exists(target), clue("denied tool must not execute"))
    assert(transcript.contains("write forbidden"), clue(transcript))
  }

  tmpDir.test("appliesTo false skips both hooks") { dir =>
    var beforeRan = false
    var afterRan  = false
    val target    = dir.resolve("written.txt")
    val decorator = ToolDecorator(
      name = "skip-me",
      appliesTo = (_, _) => false,
      before = Some { (call, _) =>
        beforeRan = true; Future.successful(DecoratorOutcome.Allow(call))
      },
      after = Some { (call, _, _) =>
        afterRan = true; Future.successful(DecoratorOutcome.Allow(call))
      }
    )
    val call =
      s"""<tool_call>{"name":"write_file","arguments":{"path":"$target","content":"ok"}}</tool_call>"""
    runWith(Vector(decorator), call, doneMsg("done"))

    assert(Files.exists(target), clue("non-applicable decorator must not block the tool"))
    assert(!beforeRan, clue("before hook must be skipped"))
    assert(!afterRan, clue("after hook must be skipped"))
  }

  test("after deny on message_user feeds back and continues the loop") {
    var attempts = 0
    val decorator = ToolDecorator(
      name = "finish-gate",
      appliesTo = (call, _) => call.name == "message_user",
      after = Some { (call, _, _) =>
        attempts += 1
        Future.successful(
          if (attempts == 1) DecoratorOutcome.Deny("not ready")
          else DecoratorOutcome.Allow(call)
        )
      }
    )
    val result     = runWith(Vector(decorator), doneMsg("first"), doneMsg("second"))
    val transcript = result.conversation.turns.map(_.text).mkString("\n")

    assertEquals(result.content, "second")
    assertEquals(attempts, 2)
    assert(transcript.contains("not ready"), clue(transcript))
  }

  tmpDir.test("before rewrite is delivered to the tool") { dir =>
    val original  = dir.resolve("original.txt")
    val rewritten = dir.resolve("rewritten.txt")
    val decorator = ToolDecorator(
      name = "rewrite",
      appliesTo = (call, _) => call.name == "write_file",
      before = Some((call, _) =>
        Future.successful(
          DecoratorOutcome.Allow(
            RawToolCall(call.name, Json.parseUnsafe(s"""{"path":"$rewritten","content":"ok"}"""))
          )
        )
      )
    )
    val call =
      s"""<tool_call>{"name":"write_file","arguments":{"path":"$original","content":"bad"}}</tool_call>"""
    val result = runWith(Vector(decorator), call, doneMsg("done"))
    assert(!Files.exists(original))
    assertEquals(Files.readString(rewritten), "ok")
    assert(result.toolCalls.contains(ToolName.WriteFile))
  }

  tmpDir.test("after deny short-circuits and still reports the executed tool") { dir =>
    var secondRan = false
    val first = ToolDecorator(
      name = "deny-output",
      appliesTo = (call, _) => call.name == "write_file",
      after = Some((call, _, _) => Future.successful(DecoratorOutcome.Deny("blocked output")))
    )
    val second = ToolDecorator(
      name = "must-not-run",
      appliesTo = (call, _) => call.name == "write_file",
      after = Some { (call, _, _) =>
        secondRan = true; Future.successful(DecoratorOutcome.Allow(call))
      }
    )
    val target = dir.resolve("written.txt")
    val call =
      s"""<tool_call>{"name":"write_file","arguments":{"path":"$target","content":"ok"}}</tool_call>"""
    val result = runWith(Vector(first, second), call, doneMsg("done"))
    assert(Files.exists(target), clue("after hooks run after tool execution"))
    assert(!secondRan, clue("first after-deny must short-circuit"))
    assert(result.toolCalls.contains(ToolName.WriteFile))
    assert(result.conversation.turns.exists(_.text.contains("blocked output")))
  }
}

class Module03Exercise06ExitGateTest extends Module03FunSuite("m03-ex06-") {
  import scala.concurrent.Await
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  // Build a tiny Agent rooted at a given workDir. Only `config.workDir` is read
  // by the exit-gate after-hook; the rest is filler.
  private def agentAt(workDir: Path): Agent = Agent(
    state = AgentState(Conversation.initial(""), SessionId.parse("test-exit-gate")),
    config = AgentConfig.default.copy(workDir = workDir),
    tools = ToolRegistry.default.tools,
    provider = new ChatProvider { def complete(messages: Vector[Message]): String = "" }
  )

  private val msgUser  = RawToolCall("message_user", Json.parseUnsafe("""{"message":"done"}"""))
  private val okResult = ToolResult.Success("done")

  test("appliesTo only fires on message_user when commands non-empty") {
    val dec   = Exercises.exitGate(Vector("true"))
    val agent = agentAt(java.nio.file.Paths.get("/tmp"))
    assert(dec.appliesTo(RawToolCall("message_user", Json.parseUnsafe("{}")), agent))
    assert(!dec.appliesTo(RawToolCall("shell", Json.parseUnsafe("{}")), agent))
    assert(!dec.appliesTo(RawToolCall("ask_user", Json.parseUnsafe("{}")), agent))
    val noGates = Exercises.exitGate(Vector.empty)
    assert(!noGates.appliesTo(RawToolCall("message_user", Json.parseUnsafe("{}")), agent))
  }

  test("allows when every gate exits 0") {
    val dec = Exercises.exitGate(Vector("true", "echo ok"))
    val r = Await.result(
      dec.after(msgUser, okResult, agentAt(java.nio.file.Paths.get("/tmp"))),
      30.seconds
    )
    r match {
      case DecoratorOutcome.Allow(_) => ()
      case other                     => fail(s"expected Allow, got $other")
    }
  }

  test("denies on first non-zero exit, with exit code + output in reason") {
    val dec = Exercises.exitGate(Vector("echo to-stderr >&2 && exit 7"))
    val r = Await.result(
      dec.after(msgUser, okResult, agentAt(java.nio.file.Paths.get("/tmp"))),
      30.seconds
    )
    r match {
      case DecoratorOutcome.Deny(reason) =>
        assert(reason.contains("Exit-gate failed"), clue(reason))
        assert(reason.contains("exited 7"), clue(reason))
        assert(reason.contains("to-stderr"), clue(reason))
      case other => fail(s"expected Deny, got $other")
    }
  }

  tmpDir.test("short-circuits on first failing gate (subsequent gates do not run)") { dir =>
    val sentinel = dir.resolve("should-not-exist")
    val dec      = Exercises.exitGate(Vector("exit 1", s"touch ${sentinel}"))
    val r        = Await.result(dec.after(msgUser, okResult, agentAt(dir)), 30.seconds)
    r match {
      case DecoratorOutcome.Deny(_) =>
        assert(
          !Files.exists(sentinel),
          clue("second gate should not have run after the first failed")
        )
      case other => fail(s"expected Deny, got $other")
    }
  }

  tmpDir.test("gates run in workDir, not process cwd") { dir =>
    writeUtf8(dir.resolve("marker"), "x")
    val dec = Exercises.exitGate(Vector("test -f marker"))
    val r   = Await.result(dec.after(msgUser, okResult, agentAt(dir)), 30.seconds)
    r match {
      case DecoratorOutcome.Allow(_) => ()
      case other => fail(s"expected Allow when marker is in workDir, got $other")
    }
  }

  test("truncates long output") {
    val dec = Exercises.exitGate(Vector("printf '%03000d' 0; exit 1"))
    val r = Await.result(
      dec.after(msgUser, okResult, agentAt(java.nio.file.Paths.get("/tmp"))),
      30.seconds
    )
    r match {
      case DecoratorOutcome.Deny(reason) => assert(reason.contains("[truncated"), clue(reason))
      case other                         => fail(s"expected Deny, got $other")
    }
  }

  test("missing command denies") {
    val dec = Exercises.exitGate(Vector("definitely-not-a-real-command-for-m03"))
    val r = Await.result(
      dec.after(msgUser, okResult, agentAt(java.nio.file.Paths.get("/tmp"))),
      30.seconds
    )
    r match {
      case DecoratorOutcome.Deny(reason) => assert(reason.contains("exited"), clue(reason))
      case other                         => fail(s"expected Deny, got $other")
    }
  }
}

class Module03Exercise07CheckpointsTest extends Module03FunSuite("m03-ex07-") {
  tmpDir.test("create copies files") { dir =>
    val workDir = dir.resolve("work")
    val cpDir   = dir.resolve("checkpoints")
    Files.createDirectories(workDir)
    writeUtf8(workDir.resolve("file.txt"), "original")
    val info = Exercises.createCheckpoint(workDir, cpDir)
    assert(info.id.startsWith("cp-"))
    assert(Files.exists(info.path.resolve("file.txt")))
  }

  tmpDir.test("restore overwrites") { dir =>
    val workDir = dir.resolve("work")
    val cpDir   = dir.resolve("checkpoints")
    Files.createDirectories(workDir)
    writeUtf8(workDir.resolve("file.txt"), "original")
    val info = Exercises.createCheckpoint(workDir, cpDir)
    writeUtf8(workDir.resolve("file.txt"), "modified")
    assert(Exercises.restoreCheckpoint(workDir, info.id, cpDir))
    assertEquals(Files.readString(workDir.resolve("file.txt")), "original")
  }

  tmpDir.test("list returns checkpoints") { dir =>
    val workDir = dir.resolve("work")
    val cpDir   = dir.resolve("checkpoints")
    Files.createDirectories(workDir)
    writeUtf8(workDir.resolve("a.txt"), "a")
    val cp1  = Exercises.createCheckpoint(workDir, cpDir)
    val cp2  = Exercises.createCheckpoint(workDir, cpDir)
    val list = Exercises.listCheckpoints(cpDir)
    assert(list.length >= 2)
    assert(list.exists(_.id == cp1.id))
    assert(list.exists(_.id == cp2.id))
    assertEquals(
      list.take(2).map(_.id),
      Vector(cp2.id, cp1.id),
      clue("expected listCheckpoints to return newest-first order")
    )
  }

  tmpDir.test("restore nonexistent returns false") { dir =>
    assert(!Exercises.restoreCheckpoint(dir, "nonexistent", dir))
  }
}

class Module03Exercise08SandboxedShellTest extends Module03FunSuite("m03-ex08-") {
  private def fakeLimactl(dir: Path, body: String): Path = {
    val executable = dir.resolve("limactl")
    Files.writeString(executable, s"#!/bin/sh\n$body\n")
    executable.toFile.setExecutable(true)
    executable
  }

  tmpDir.test("passes exact Lima arguments and returns stdout") { dir =>
    val argsFile = dir.resolve("args")
    val executable = fakeLimactl(
      dir,
      s"printf '%s\\n' \"$$@\" > '${argsFile.toString}'\nprintf 'sandbox ok\\n'"
    )
    val result = Exercises.executeSandboxedShell(
      "printf '%s' done",
      Path.of("/tmp/work dir"),
      executable.toString
    )
    assertEquals(result, ToolResult.Success("sandbox ok\n"))
    assertEquals(
      Files.readString(argsFile).linesIterator.toVector,
      Vector("shell", "default", "--", "bash", "-c", "cd -- '/tmp/work dir' && printf '%s' done")
    )
  }

  tmpDir.test("reports a non-zero limactl exit") { dir =>
    val executable = fakeLimactl(dir, "printf 'remote failed\\n' >&2\nexit 7")
    Exercises.executeSandboxedShell("false", Path.of("/tmp/work"), executable.toString) match {
      case ToolResult.Failure(error) => assert(error.contains("remote failed"))
      case result                    => fail(s"expected failure, got $result")
    }
  }

  test("reports a limactl start failure") {
    Exercises.executeSandboxedShell(
      "true",
      Path.of("/tmp/work"),
      "/definitely/missing/limactl"
    ) match {
      case ToolResult.Failure(error) => assert(error.startsWith("sandboxed shell error:"))
      case result                    => fail(s"expected failure, got $result")
    }
  }
}
