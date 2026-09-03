package workshop.agent

import zio.blocks.schema.json.Json

/** A tool call as it arrived from the model: unresolved tool name (string) and untyped JSON
  * arguments. Use [[validate]] to resolve the name against a [[ToolRegistry]] and parse the
  * arguments via the matched tool's `parse`, yielding a [[ToolCall]].
  */
final case class RawToolCall(name: String, arguments: Json) {

  /** Validate this raw call against a registry into a typed [[ToolCall]], or return a
    * human-readable error. The registry decides which tool names are runnable — `ToolName` itself
    * is open-world, so existence is registry-relative.
    */
  def validate(registry: ToolRegistry): Either[String, ToolCall.Any] =
    registry.find(ToolName(name)) match {
      case None       => Left(s"Unknown tool: $name")
      case Some(tool) => parseWith(tool)
    }

  private def parseWith(tool: Tool): Either[String, ToolCall.Any] = {
    val refined: Tool.WithInput[tool.Input] = tool
    refined.parse(arguments).map(p => ToolCall(refined, p))
  }
}
