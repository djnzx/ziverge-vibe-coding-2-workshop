package workshop.agent

import zio.blocks.schema.Schema
import zio.blocks.schema.json.{Json, WriterConfig}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Try

import workshop.agent.internal.JsonUtils.jsonString

/** A tool the agent can invoke. Each concrete tool is a named singleton in `Tool`'s companion (e.g.
  * `Tool.ReadFile`) that defines its own `Params` case class — the 1:1 partnership between a tool
  * and its input shape is captured via the path-dependent `type Input`. Schema-driven JSON parse,
  * schema generation, and field extraction all derive from the `Schema[Input]` evidence each tool
  * supplies.
  */
trait Tool {
  type Input

  def name: ToolName
  def description: String
  def execute(input: Input, agent: Agent): ToolResult
  def inputSchema: Schema[Input]

  final def schema: Json = inputSchema.toJsonSchema.toJson

  final def parse(arguments: Json): Either[String, Input] =
    arguments.as[Input](using inputSchema).left.map(_.message)

  final def formatForContext: String =
    List(
      s"### ${name.wire}",
      description,
      "Parameters:",
      "```json",
      schema.print(WriterConfig.withIndentionStep2),
      "```"
    ).mkString("\n")
}

object Tool {

  /** Tool refined to a specific input type. */
  type WithInput[A] = Tool { type Input = A }

  private def resolveAgainst(workDir: Path, raw: String): String = {
    val p = Path.of(raw)
    if (p.isAbsolute) raw else workDir.resolve(p).toString
  }

  object ReadFile extends Tool {
    final case class Params(path: String) derives Schema {
      def withResolvedPath(workDir: Path): Params = copy(path = resolveAgainst(workDir, path))
    }

    type Input = Params
    val name = ToolName.ReadFile
    val description =
      """Read the contents of a file as UTF-8 text.
        |
        |`path` may be absolute (e.g. `/etc/hosts`) or relative to the agent's working
        |directory (e.g. `src/main/scala/Foo.scala`). Returns the file's contents.
        |Returns an error if the file does not exist or cannot be read.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult =
      Exercises.executeReadFile(p.withResolvedPath(agent.config.workDir))
  }

  object WriteFile extends Tool {
    final case class Params(path: String, content: String) derives Schema {
      def withResolvedPath(workDir: Path): Params = copy(path = resolveAgainst(workDir, path))
    }

    type Input = Params
    val name = ToolName.WriteFile
    val description =
      """Write content to a file as UTF-8 text.
        |
        |`path` may be absolute or relative to the agent's working directory. If the file
        |exists, it is overwritten in full. Parent directories must already exist —
        |use a shell `mkdir -p` first if needed. Returns confirmation on success or
        |an I/O error message on failure.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = {
      val resolved = p.withResolvedPath(agent.config.workDir)
      Try(Files.writeString(Path.of(resolved.path), resolved.content, StandardCharsets.UTF_8))
        .fold(
          err => ToolResult.Failure(s"writing file: $err"),
          _ => ToolResult.Success(s"Successfully wrote to ${resolved.path}")
        )
    }
  }

  object EditFile extends Tool {
    final case class Params(
      path: String,
      line_start: Int,
      line_end: Int,
      old_text: String,
      new_text: String
    ) derives Schema {
      def withResolvedPath(workDir: Path): Params = copy(path = resolveAgainst(workDir, path))
    }

    type Input = Params
    val name = ToolName.EditFile
    val description =
      """Edit a file by replacing the first occurrence of `old_text` with `new_text`,
        |searching only within the 1-indexed inclusive line range [`line_start`, `line_end`].
        |
        |`path` may be absolute or relative to the agent's working directory. The file
        |is unchanged if `old_text` does not appear within the line range. Use this for
        |surgical edits when you know roughly where the change should land; for full
        |overwrites, prefer `write_file`. Returns confirmation on success or an error
        |if the file is missing or `old_text` is not found in the specified range.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult =
      Exercises.executeEditFile(p.withResolvedPath(agent.config.workDir))
  }

  object Shell extends Tool {
    final case class Params(command: String) derives Schema

    type Input = Params
    val name = ToolName.Shell
    val description =
      """Execute a shell command via `/bin/sh -c` and return its combined stdout and
        |stderr, prefixed with the exit code.
        |
        |Runs synchronously in the agent's working directory — long commands block
        |the agent until they finish. `command` is the full shell line (use `&&`,
        |`|`, redirects, etc. as needed). Returns the command output regardless of
        |exit code; non-zero exit codes appear in the prefix.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult =
      Exercises.executeShell(p, agent.config.workDir)
  }

  object ListFiles extends Tool {
    final case class Params(path: String, max_depth: Int) derives Schema {
      def withResolvedPath(workDir: Path): Params = copy(path = resolveAgainst(workDir, path))
    }

    type Input = Params
    val name = ToolName.ListFiles
    val description =
      """Recursively list files and directories at the given path.
        |
        |`path` may be absolute or relative to the agent's working directory.
        |`max_depth` controls recursion: 1 returns only immediate children, 2 includes
        |one level deeper, and so on. Returns one path per line, each relative to
        |`path`. Hidden files (dotfiles) are included; symlinks are listed but not
        |followed. Returns an error if `path` is not a directory.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult =
      Exercises.executeListFiles(p.withResolvedPath(agent.config.workDir))
  }

  object WebFetch extends Tool {
    final case class Params(url: String) derives Schema

    type Input = Params
    val name = ToolName.WebFetch
    val description =
      """Fetch an HTTP/HTTPS URL with a GET request and return the response body as text.
        |
        |`url` must be a full URL including scheme. Follows redirects. No request body,
        |query params, or custom headers are supported — use the `shell` tool with
        |`curl` for those. Returns the response body verbatim, or an error if the
        |request fails or the response is not valid UTF-8.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = Exercises.executeWebFetch(p)
  }

  object MessageUser extends Tool {
    final case class Params(message: String) derives Schema

    type Input = Params
    val name = ToolName.MessageUser
    val description =
      """Deliver the final answer to the user.
        |
        |This is the **terminator** tool — calling it ends the turn. No further tool
        |calls will be processed in this turn after `message_user`. Use only when the
        |task is complete and you have a definitive answer. `message` is the text the
        |user will see.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = ToolResult.Success(p.message)
  }

  object AskUser extends Tool {
    final case class Params(question: String) derives Schema

    type Input = Params
    val name = ToolName.AskUser
    val description =
      """Ask the user a clarifying question and pause until they respond.
        |
        |Like `message_user`, this **terminates the turn** — execution resumes only
        |after the user's response arrives as input to the next turn. Use at review
        |gates and decision points where human input is required before proceeding;
        |do not use to fish for additional context the model could derive itself.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = ToolResult.Success(p.question)
  }

  object ReadBoard extends Tool {
    final case class Params(path: String) derives Schema

    type Input = Params
    val name = ToolName.ReadBoard
    val description =
      """Read the current state of a kanban board JSON file.
        |
        |`path` is the path (absolute or relative to workDir) to the board file.
        |
        |Board shape: an object with fields `intent` (string), `name` (string),
        |`startedAt` (ISO-8601 timestamp), `currentPhase` (the phase name in progress),
        |and `phases` (object mapping each phase name to `{status, artifact?}`).
        |
        |Returns the board JSON verbatim. Used together with `update_board` to drive
        |multi-phase workflows. Returns an error if the file
        |is missing or is not valid JSON.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = {
      val boardPath = Path.of(resolveAgainst(agent.config.workDir, p.path))
      if (!Files.exists(boardPath)) ToolResult.Failure(s"board not found: ${p.path}")
      else
        Try(Files.readString(boardPath, StandardCharsets.UTF_8)).fold(
          err => ToolResult.Failure(s"reading board: $err"),
          content =>
            Json.parse(content) match {
              case Left(err) => ToolResult.Failure(s"reading board: ${err.message}")
              case Right(_)  => ToolResult.Success(content)
            }
        )
    }
  }

  object UpdateBoard extends Tool {
    final case class Params(path: String, phase: String, status: String, artifact: Option[String])
        derives Schema

    type Input = Params
    val name = ToolName.UpdateBoard
    val description =
      """Update a single phase on a kanban board JSON file: set its status and
        |optionally record an artifact path.
        |
        |`path` is the path (absolute or relative to workDir) to the board file.
        |
        |`phase` is the phase name to update. Must already exist as a key under the
        |board's `phases` object; callers define the phases for their own workflow.
        |
        |`status` is the new workflow-defined status string.
        |
        |`artifact` is an optional path (absolute or relative to workDir) to the file
        |produced by completing the phase. Omit (or pass `null`) when the transition
        |does not produce a new artifact — e.g. `pending → in_progress`.
        |
        |The board's `currentPhase` is also updated to `phase` as a side-effect.
        |Returns the updated board JSON. Returns an error if the board file is
        |missing or `phase` is not a known phase.""".stripMargin
    val inputSchema = summon[Schema[Params]]

    def execute(p: Params, agent: Agent): ToolResult = {
      def upsertField(
        fields: zio.blocks.chunk.Chunk[(String, Json)],
        key: String,
        value: Json
      ): zio.blocks.chunk.Chunk[(String, Json)] =
        zio.blocks.chunk.Chunk
          .fromIterable(fields.filterNot(_._1 == key).toVector :+ (key -> value))

      val boardPath = Path.of(resolveAgainst(agent.config.workDir, p.path))
      if (!Files.exists(boardPath)) ToolResult.Failure(s"board not found: ${p.path}")
      else
        Try(Files.readString(boardPath, StandardCharsets.UTF_8)).fold(
          err => ToolResult.Failure(s"updating board: $err"),
          content =>
            Json.parse(content) match {
              case Left(err) => ToolResult.Failure(s"updating board: ${err.message}")
              case Right(board) =>
                board match {
                  case Json.Object(boardFields) =>
                    boardFields.find(_._1 == "phases").map(_._2) match {
                      case Some(Json.Object(phasesFields)) =>
                        phasesFields.find(_._1 == p.phase).map(_._2) match {
                          case Some(Json.Object(phaseFields)) =>
                            val withStatus =
                              upsertField(phaseFields, "status", jsonString(p.status))
                            val withArtifact = p.artifact match {
                              case Some(artifactPath) =>
                                upsertField(withStatus, "artifact", jsonString(artifactPath))
                              case None => withStatus
                            }
                            val updatedPhases =
                              upsertField(phasesFields, p.phase, Json.Object(withArtifact))
                            val withPhases =
                              upsertField(boardFields, "phases", Json.Object(updatedPhases))
                            val updatedBoard = Json.Object(
                              upsertField(withPhases, "currentPhase", jsonString(p.phase))
                            )
                            val rendered = updatedBoard.print(WriterConfig.withIndentionStep2)
                            Try(
                              Files.writeString(boardPath, rendered + "\n", StandardCharsets.UTF_8)
                            ).fold(
                              writeErr => ToolResult.Failure(s"updating board: $writeErr"),
                              _ => ToolResult.Success(rendered)
                            )
                          case _ => ToolResult.Failure(s"unknown phase: ${p.phase}")
                        }
                      case _ => ToolResult.Failure(s"unknown phase: ${p.phase}")
                    }
                  case _ => ToolResult.Failure(s"unknown phase: ${p.phase}")
                }
            }
        )
    }
  }
}
