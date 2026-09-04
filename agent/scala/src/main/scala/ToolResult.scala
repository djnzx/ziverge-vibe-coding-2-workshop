package workshop.agent

enum ToolResult:
  case Success(output: String)
  case Failure(error: String)

  def stringify: String = this match {
    case ToolResult.Success(output) => output
    case ToolResult.Failure(error)  => s"Error: $error"
  }

  def truncated: ToolResult = this match {
    case ToolResult.Success(output) => ToolResult.Success(ToolResult.truncateOutput(output))
    case ToolResult.Failure(error)  => ToolResult.Failure(ToolResult.truncateOutput(error))
  }

object ToolResult {
  // Module 01, Exercise 7: the model-visible output budget for a single tool result.
  // Large enough to carry a real file or command transcript, small enough that one
  // oversized read cannot crowd out the rest of the conversation in later turns.
  val MaxOutputChars = 10000

  def truncateOutput(output: String): String =
    if (output.length <= MaxOutputChars) output
    else {
      // The omitted count is reported so the model knows it is looking at a prefix and can
      // narrow its next read instead of assuming it saw the whole thing.
      val omitted = output.length - MaxOutputChars
      s"${output.take(MaxOutputChars)}\n[truncated — $omitted more chars]"
    }
}
