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
  // TODO — Module 01, Exercise 7. Choose the model-visible output character budget.
  // See 01-foundations/exercises.md.
  val MaxOutputChars = 0

  def truncateOutput(output: String): String =
    // TODO — Module 01, Exercise 7. Bound output and report omitted characters.
    // See 01-foundations/exercises.md for the contract.
    sys.error("Module 01, Exercise 7 — see 01-foundations/exercises.md")
}
