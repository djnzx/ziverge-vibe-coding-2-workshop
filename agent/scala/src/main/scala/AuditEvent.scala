package workshop.agent

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

// Cross-language note: TS uses Record<string, unknown>, Rust uses serde_json::Value.
// Scala uses Map[String, String] — sufficient for workshop audit details (always string k/v pairs).
final case class AuditEvent(timestamp: String, event: String, details: Map[String, String])
    derives Schema {
  def encode: String = Json.from(this).print
}
