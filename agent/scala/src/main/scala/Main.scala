package workshop.agent

import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Try

/** Application entry point. Parses CLI args, constructs an [[Agent]], dispatches to the driver
  * matching `runMode`. Lives here (not on `Agent`'s companion) because the entry point isn't part
  * of `Agent`'s API — `Agent` is a data type, not an app.
  */
@main def main(args: String*): Unit = {
  val argv = args.toArray

  val apiKey = sys.env.get("OPENROUTER_API_KEY").filter(_.nonEmpty).getOrElse {
    Console.err.println("Set OPENROUTER_API_KEY environment variable")
    sys.exit(1)
  }

  val cliConfig = CliConfig.parse(argv) match {
    case Left(err) =>
      Console.err.println(err)
      sys.exit(1)
    case Right(value) => value
  }

  val config = cliConfig.config
  val provider =
    ChatProvider.openAi(apiKey, "https://openrouter.ai/api/v1", config.model, config.temperature)

  val interactive = cliConfig.runMode match {
    // JVM has no isatty(stdin); System.console() is the standard approximation.
    // TS uses process.stdin.isTTY; Rust uses atty::is(Stdin).
    case RunMode.AutoDetect  => Option(System.console()).nonEmpty
    case RunMode.Interactive => true
    case RunMode.Protocol    => false
    case RunMode.FileMode(_) => false
  }

  if (cliConfig.resumeSessionId.nonEmpty && config.historyDir.isEmpty) {
    Console.err.println("--resume requires --history-dir")
    sys.exit(1)
  }
  val agent = cliConfig.resumeSessionId match {
    case Some(id) =>
      Exercises.loadSession(config.historyDir.get, id) match {
        case Some(conversation) =>
          if (config.verbose)
            Console.err.println(s"Resumed session $id with ${conversation.turns.length} turns")
          Agent
            .resumed(
              config,
              ToolRegistry.default,
              provider,
              conversation,
              SessionId.parse(id),
              interactive
            )
            .withInjectedContext
        case None =>
          Console.err.println(s"Session not found: $id")
          sys.exit(1)
      }
    case None =>
      Agent.initial(config, ToolRegistry.default, provider, interactive).withInjectedContext
  }

  cliConfig.runMode match {
    case RunMode.FileMode(filePath) =>
      val fileContent = Try(Files.readString(filePath, StandardCharsets.UTF_8)).fold(
        err => {
          Console.err.println(s"Failed to read prompts file '$filePath': ${err.getMessage}")
          sys.exit(1)
        },
        identity
      )
      val prompts = workshop.agent.internal.TextUtils.splitSections(fileContent, "---")
      val _       = agent.runFileMode(prompts)

    case RunMode.Interactive | RunMode.AutoDetect if interactive =>
      val terminal = TerminalOutput.real
      terminal.banner(
        agent.config.model,
        agent.tools.map(_.name).filterNot(_ == ToolName.MessageUser)
      )
      val jlineTerminal = TerminalBuilder.builder().system(true).build()
      val jlineReader   = LineReaderBuilder.builder().terminal(jlineTerminal).build()
      try {
        val _ = agent.runInteractive(terminal, new LineReader.Jline(jlineReader))
      } finally jlineTerminal.close()

    case _ =>
      val _ = agent.runProtocol(scala.io.Source.stdin.getLines())
  }
  sys.exit(0)
}
