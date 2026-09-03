package workshop.agent

/** The wire-format identifier for a tool. Used in the JSON protocol the LLM sends and as the lookup
  * key in [[ToolRegistry]]. A `ToolName` is just a string wrapper; any wire string is a valid name,
  * but only names present in a `ToolRegistry` resolve to runnable tools.
  *
  * The named values in the companion are the canonical set this codebase ships, but downstream
  * applications can construct their own (`ToolName("my_tool")`).
  */
final case class ToolName(wire: String)

object ToolName {
  val ReadFile: ToolName    = ToolName("read_file")
  val WriteFile: ToolName   = ToolName("write_file")
  val EditFile: ToolName    = ToolName("edit_file")
  val Shell: ToolName       = ToolName("shell")
  val ListFiles: ToolName   = ToolName("list_files")
  val WebFetch: ToolName    = ToolName("web_fetch")
  val MessageUser: ToolName = ToolName("message_user")
  val AskUser: ToolName     = ToolName("ask_user")
  val ReadBoard: ToolName   = ToolName("read_board")
  val UpdateBoard: ToolName = ToolName("update_board")
}
