package workshop.agent

import sttp.model.Uri
import sttp.ai.openai.OpenAISyncClient
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.ai.openai.requests.completions.chat.message.Message

trait ChatProvider {
  def complete(messages: Vector[Message]): String
}

object ChatProvider {

  /** Build a synchronous OpenAI-compatible chat provider. Both the CLI ([[Main]]) and in-process
    * callers ([[Lib.runPhase]]) use this factory so the request shape stays consistent across entry
    * points.
    *
    * Note: the returned provider holds an internal `OpenAISyncClient` whose lifecycle is bound to
    * JVM exit — for short-lived processes (CLIs, single-phase orchestrators) that's fine. Callers
    * needing explicit cleanup should construct the client themselves.
    */
  def openAi(apiKey: String, baseUrl: String, model: String, temperature: Double): ChatProvider = {
    val client = OpenAISyncClient(apiKey, Uri.unsafeParse(baseUrl))
    new ChatProvider {
      def complete(messages: Vector[Message]): String = {
        val chat = client.createChatCompletion(
          ChatBody(
            model = ChatCompletionModel.CustomChatCompletionModel(model),
            messages = messages,
            temperature = Some(temperature),
            maxTokens = Some(4096)
          )
        )
        chat.choices.headOption.map(_.message.content).getOrElse("")
      }
    }
  }
}
