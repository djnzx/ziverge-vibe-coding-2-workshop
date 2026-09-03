package workshop.agent

final case class TurnResult(
  content: String,
  toolCalls: Vector[ToolName],
  conversation: Conversation
)
