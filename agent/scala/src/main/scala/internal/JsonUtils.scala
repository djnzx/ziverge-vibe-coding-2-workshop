package workshop.agent.internal

import zio.blocks.schema.json.Json

object JsonUtils {
  def escapeJson(value: String): String =
    value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }

  def jsonString(value: String): Json = {
    val escaped = value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    Json.parseUnsafe(s"\"$escaped\"")
  }
}
