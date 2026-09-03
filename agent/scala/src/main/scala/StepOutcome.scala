package workshop.agent

import zio.blocks.schema.json.{json, Json}

/** The outcome of one [[Agent.step]] call: what kind of step it was, plus the data the agent
  * computed during it. Cases discriminate by what the user asked for — see [[UserCommand]] for the
  * input partition — and carry whatever output the agent produced for that input.
  */
sealed trait StepOutcome {
  def content: String
  def asJson: Json
}

object StepOutcome {
  case object Quit extends StepOutcome {
    def content: String = "goodbye."
    def asJson: Json    = json"""{"content": "goodbye.", "tool_calls": []}"""
  }

  final case class Turn(content: String, toolCalls: Vector[ToolName]) extends StepOutcome {
    def asJson: Json = json"""{"content": $content, "tool_calls": ${toolCalls.map(_.wire)}}"""
  }

  final case class Compacted(beforeTurns: Int, afterTurns: Int) extends StepOutcome {
    def content: String = s"Compacted $beforeTurns messages → $afterTurns messages"
    def asJson: Json    = json"""{"content": $content, "tool_calls": []}"""
  }

  final case class Custom(content: String) extends StepOutcome {
    def asJson: Json = json"""{"content": $content, "tool_calls": []}"""
  }

  final case class Unknown(name: String) extends StepOutcome {
    def content: String = s"Unknown command: /$name"
    def asJson: Json    = json"""{"content": $content, "tool_calls": []}"""
  }
}
