package workshop.agent

import sttp.ai.openai.requests.completions.chat.message.{Content, Message}

/** A single turn in a [[Conversation]] — either a user message or an assistant message. Wraps the
  * underlying sttp `Message` so this codebase has one place to attach methods (`role`, `text`), one
  * place to host factories (`TurnMessage.user`, `TurnMessage.assistant`), and one home for the type
  * (`TurnMessage.scala`).
  */
final case class TurnMessage(message: Message.User | Message.Assistant) {

  /** Either `"user"` or `"assistant"`. */
  def role: String = message match {
    case _: Message.User      => "user"
    case _: Message.Assistant => "assistant"
  }

  /** The textual payload of this turn. */
  def text: String = message match {
    case Message.User(Content.TextContent(value), _) => value
    case Message.User(value, _)                      => value.toString
    case Message.Assistant(value, _, _)              => value
  }
}

object TurnMessage {

  /** Construct a user-text turn. */
  def user(text: String): TurnMessage =
    TurnMessage(Message.User(Content.TextContent(text)))

  /** Construct an assistant-text turn. */
  def assistant(text: String): TurnMessage =
    TurnMessage(Message.Assistant(text))
}
