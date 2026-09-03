package workshop.agent

import java.nio.file.Path

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** A conversation persisted to disk: the system prompt the agent was started with, the model and
  * working directory, and the turn history as [[StoredTurn]] pairs. Encoding/decoding both go
  * through the derived `Schema[StoredSession]` — no manual string-built JSON.
  */
final case class StoredSession(
  timestamp: String,
  workDir: String,
  model: String,
  systemPrompt: String,
  turns: Vector[StoredTurn]
) derives Schema {
  def encode: String = Json.from(this).print
}

object StoredSession {
  def decode(content: String): Option[StoredSession] =
    Json.parse(content).toOption.flatMap(_.as[StoredSession].toOption)

  def pathFor(historyDir: Path, sessionId: String): Path =
    historyDir.resolve(s"$sessionId.json")
}
