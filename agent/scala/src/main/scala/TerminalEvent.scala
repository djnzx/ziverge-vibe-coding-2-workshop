package workshop.agent

// File-local renames so the enum cases below can be named `ToolCall` / `ToolResult`
// without shadowing the workshop.agent.ToolCall / .ToolResult types they carry.
import workshop.agent.{ToolCall => AgentToolCall, ToolResult => AgentToolResult}

// Captures semantic UI events for test assertions — not exact ANSI bytes or terminal output.
enum TerminalEvent:
  case Banner(model: String, tools: Seq[ToolName])
  case Thinking(text: String)
  case ToolCall(call: AgentToolCall.Any)
  case ToolResult(result: AgentToolResult)
  case Answer(text: String)
  case Error(text: String)
  case SpinnerStart
  case SpinnerStop
  case Goodbye
