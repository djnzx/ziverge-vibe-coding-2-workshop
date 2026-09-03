package workshop.agent

import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

/** A single turn in a [[Conversation]] — either a user message or an assistant message. Wraps the
  * underlying sttp `Message` so this codebase has one place to attach methods (`role`, `text`), one
  * place to host factories (`TurnMessage.user`, `TurnMessage.assistant`), and one home for the type
  * (`TurnMessage.scala`).
  */
final case class TurnMessage(message: Message.UserMessage | Message.AssistantMessage) {

  /** Either `"user"` or `"assistant"`. */
  def role: String = message match {
    case _: Message.UserMessage      => "user"
    case _: Message.AssistantMessage => "assistant"
  }

  /** The textual payload of this turn. */
  def text: String = message match {
    case Message.UserMessage(Content.TextContent(value), _) => value
    case Message.UserMessage(value, _)                      => value.toString
    case Message.AssistantMessage(value, _, _)              => value
  }
}

object TurnMessage {

  /** Construct a user-text turn. */
  def user(text: String): TurnMessage =
    TurnMessage(Message.UserMessage(Content.TextContent(text)))

  /** Construct an assistant-text turn. */
  def assistant(text: String): TurnMessage =
    TurnMessage(Message.AssistantMessage(text))
}
