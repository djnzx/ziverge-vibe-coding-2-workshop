package workshop.agent

final case class ContextUsage(
  system: Int,
  conversation: Int,
  tools: Int,
  total: Int,
  limit: Option[Int],
  percentage: Option[Double]
) {

  /** True if the conversation has overflowed the configured budget. */
  def overBudget: Boolean = limit.exists(l => total > l)
}

object ContextUsage {

  /** Measure the usage induced by a system prompt, conversation, tool set, and optional limit.
    * Delegates to the workshop's `Exercises.measureContext` so the answer-key implementation
    * remains the single source of truth.
    */
  def measure(
    systemPrompt: String,
    conversation: Conversation,
    tools: Vector[Tool],
    maxChars: Option[Int]
  ): ContextUsage = Exercises.measureContext(systemPrompt, conversation, tools, maxChars)
}
