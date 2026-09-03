package workshop.agent

/** Per-step persistence + auto-compact policy: after a step completes and is rendered, what
  * lifecycle work should run? Concrete instances:
  *   - [[StepPolicy.PersistEveryStep]]: file/protocol modes — every input is a session unit,
  *     persist on every step. `afterStepSideEffects` runs (saving session unconditionally;
  *     extracting memories + writing output-file only on Turn), then `maybeAutoCompact`.
  *   - [[StepPolicy.PersistTurnOnly]]: interactive mode — non-Turn outcomes (Quit / Compacted /
  *     Custom / Unknown) don't represent session progress, so neither persistence nor compaction
  *     runs. On Turn, the ordering is reversed: `maybeAutoCompact` first (so the saved conversation
  *     is the compacted form), then `afterStepSideEffects`.
  */
trait StepPolicy {
  def afterStep(agent: Agent, userInput: String, outcome: StepOutcome): Agent
}

object StepPolicy {

  /** File-mode / protocol-mode policy: persist every step. */
  case object PersistEveryStep extends StepPolicy {
    def afterStep(agent: Agent, userInput: String, outcome: StepOutcome): Agent = {
      agent.afterStepSideEffects(userInput, outcome)
      agent.maybeAutoCompact
    }
  }

  /** Interactive policy: persist only on `Turn`. Compact first, then save — so a resumed session
    * sees the compacted summary, not the wall of text that triggered the compaction.
    */
  case object PersistTurnOnly extends StepPolicy {
    def afterStep(agent: Agent, userInput: String, outcome: StepOutcome): Agent =
      outcome match {
        case StepOutcome.Turn(_, _) =>
          val advanced = agent.maybeAutoCompact
          advanced.afterStepSideEffects(userInput, outcome)
          advanced
        case _ => agent
      }
  }
}
