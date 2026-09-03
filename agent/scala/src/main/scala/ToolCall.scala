package workshop.agent

import zio.blocks.schema.json.Json

/** A validated tool call: a [[Tool]] paired with its parsed parameters. Constructed via
  * [[RawToolCall.validate]] (or [[ToolCall.fromRaw]]). `execute` is delegated to the tool's own
  * execute function; `paramsFields` re-encodes the params via the tool's schema and pulls out the
  * resulting JSON object's fields — `Tool` is the single source of truth.
  */
final case class ToolCall[A](tool: Tool.WithInput[A], params: A) {
  def name: ToolName = tool.name

  def execute(agent: Agent): ToolResult = tool.execute(params, agent)

  /** Field-name → Json pairs for the carried params, for display purposes. Derived by
    * round-tripping `params` through its `Schema[A]`; if Schema produces an object (which it does
    * for case classes), its fields are the rendering.
    */
  def paramsFields: Vector[(String, Json)] =
    Json.from(params)(using tool.inputSchema).fields.toVector
}

object ToolCall {

  /** Convenience alias for "some tool call whose param type we don't care about." */
  type Any = ToolCall[?]

  /** Smart constructor — validate a `RawToolCall` against a registry into a `ToolCall`, or return a
    * human-readable error. Delegates to [[RawToolCall.validate]]; lives here so that looking at
    * `ToolCall`'s companion reveals how to construct one.
    */
  def fromRaw(raw: RawToolCall, registry: ToolRegistry): Either[String, ToolCall.Any] =
    raw.validate(registry)
}
