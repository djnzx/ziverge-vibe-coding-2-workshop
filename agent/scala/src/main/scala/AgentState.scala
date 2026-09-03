package workshop.agent

final case class AgentState(
  conversation: Conversation,
  sessionId: SessionId
) {
  def append(turn: TurnMessage): AgentState =
    copy(conversation = conversation :+ turn)
}

object AgentState {
  def initial(systemPrompt: String, sessionId: SessionId): AgentState =
    AgentState(Conversation.initial(systemPrompt), sessionId)
}
