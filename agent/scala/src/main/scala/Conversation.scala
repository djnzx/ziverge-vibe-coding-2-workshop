package workshop.agent

import sttp.ai.openai.requests.completions.chat.message.Message

final case class Conversation(
  systemPrompt: String,
  turns: Vector[TurnMessage]
) {
  def toMessages: Vector[Message] =
    Message.SystemMessage(systemPrompt) +: turns.map(_.message)

  def :+(turn: TurnMessage): Conversation = copy(turns = turns :+ turn)
}

object Conversation:
  def initial(systemPrompt: String): Conversation =
    Conversation(systemPrompt, Vector.empty)
