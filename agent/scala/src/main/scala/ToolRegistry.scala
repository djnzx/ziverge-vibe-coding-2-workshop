package workshop.agent

final case class ToolRegistry(tools: Vector[Tool]) {
  def find(name: ToolName): Option[Tool] = tools.find(_.name == name)
  def names: Vector[ToolName]            = tools.map(_.name)
}

object ToolRegistry {
  // The canonical set of tools the agent ships with. `message_user` is a protocol
  // terminator (signals task completion), not a side-effecting tool. The tools
  // themselves live as named singletons in `Tool`'s companion.
  val default: ToolRegistry = ToolRegistry(
    Vector(
      Tool.ReadFile,
      Tool.WriteFile,
      Tool.EditFile,
      Tool.Shell,
      Tool.ListFiles,
      Tool.WebFetch,
      Tool.MessageUser,
      Tool.AskUser,
      Tool.ReadBoard,
      Tool.UpdateBoard
    )
  )
}
