package workshop.agent

/** How a driver mode renders a [[StepOutcome]] to the user. Concrete instances:
  *   - [[Channel.Protocol]]: one JSON-encoded outcome per step, written to stdout via
  *     [[IoFormat.writeProtocol]].
  *   - [[Channel.Terminal]]: per-outcome dispatch to a [[TerminalOutput]]. `Turn` outcomes are NOT
  *     re-rendered here because the agent's in-turn display (driven by the terminal threaded
  *     through `step`) already showed the content.
  *
  * The trait's only concern is output rendering. The terminal used by `Agent.step` for in-turn
  * display is supplied to `Agent.drive` directly; Channel doesn't expose it.
  */
trait Channel {
  def render(outcome: StepOutcome): Unit
}

object Channel {

  /** JSON-protocol channel: outcomes serialized through [[IoFormat.writeProtocol]]. */
  final case class Protocol(outputFormat: IoFormat) extends Channel {
    def render(outcome: StepOutcome): Unit =
      outputFormat.writeProtocol(outcome)
  }

  /** Interactive terminal channel: per-outcome dispatch to a [[TerminalOutput]]. */
  final case class Terminal(term: TerminalOutput) extends Channel {
    def render(outcome: StepOutcome): Unit = outcome match {
      case StepOutcome.Quit            => term.goodbye()
      case StepOutcome.Compacted(_, _) => term.toolResult(ToolResult.Success(outcome.content))
      case StepOutcome.Custom(content) => term.answer(content)
      case StepOutcome.Unknown(_)      => term.error(outcome.content)
      case StepOutcome.Turn(_, _)      => () // in-turn display already showed it
    }
  }
}
