package workshop.agent

import scala.concurrent.Future

/** A code-defined decorator around a tool execution. Orchestrators build [[ToolDecorator]]s as
  * typed values and pass them to [[Exercises.handleTurn]] (or [[Lib.runPhase]]).
  *
  * Lifecycle around each tool call:
  *   - `appliesTo(call, agent)` — cheap predicate; non-matching decorators skip.
  *   - `before(call, agent)` — inspect (and optionally rewrite) the call before execution.
  *     Returning `Deny(reason)` short-circuits: the tool does NOT run, and the reason is fed back
  *     as a user-facing tool result.
  *   - `after(call, result, agent)` — inspect the tool result. `Deny(reason)` replaces the result
  *     with the deny reason.
  *
  * The Future-based shape is for parity with the TS implementation; most decorators are pure and
  * synchronous (return `Future.successful(...)`).
  */
trait ToolDecorator {
  def name: String
  def appliesTo(call: RawToolCall, agent: Agent): Boolean
  def before(call: RawToolCall, agent: Agent): Future[DecoratorOutcome] =
    Future.successful(DecoratorOutcome.Allow(call))
  def after(call: RawToolCall, result: ToolResult, agent: Agent): Future[DecoratorOutcome] =
    Future.successful(DecoratorOutcome.Allow(call))

  /** Whether this decorator participates in the `before` phase (returns a non-default
    * implementation). Defaults to `false`; convenience constructors flip it to `true` when a
    * `before` lambda is supplied.
    */
  def hasBefore: Boolean = false

  /** Whether this decorator participates in the `after` phase. */
  def hasAfter: Boolean = false
}

object ToolDecorator {

  /** Case-class-style convenience constructor for ergonomics with existing
    * `ToolDecorator(name = ..., appliesTo = ..., before = Some(...), after = Some(...))` call
    * sites. The `Option`-wrapped lambdas signal participation; when `None` (the default),
    * `hasBefore` / `hasAfter` stay `false` and the decorator loop skips that phase entirely.
    */
  def apply(
    name: String,
    appliesTo: (RawToolCall, Agent) => Boolean,
    before: Option[(RawToolCall, Agent) => Future[DecoratorOutcome]] = None,
    after: Option[(RawToolCall, ToolResult, Agent) => Future[DecoratorOutcome]] = None
  ): ToolDecorator = {
    val n = name; val a = appliesTo; val b = before; val af = after
    new ToolDecorator {
      val name: String                                        = n
      def appliesTo(call: RawToolCall, agent: Agent): Boolean = a(call, agent)
      override val hasBefore: Boolean                         = b.isDefined
      override val hasAfter: Boolean                          = af.isDefined
      override def before(call: RawToolCall, agent: Agent): Future[DecoratorOutcome] =
        b.fold(super.before(call, agent))(fn => fn(call, agent))
      override def after(
        call: RawToolCall,
        result: ToolResult,
        agent: Agent
      ): Future[DecoratorOutcome] =
        af.fold(super.after(call, result, agent))(fn => fn(call, result, agent))
    }
  }
}

/** Allow or Deny a tool call from a [[ToolDecorator]]'s hook. `Allow` may carry a rewritten
  * `RawToolCall` (the `before` hook can rewrite arguments); `Deny` carries the reason that will be
  * surfaced to the LLM as a tool result.
  */
enum DecoratorOutcome {
  case Allow(rewritten: RawToolCall)
  case Deny(reason: String)
}

object DecoratorOutcome {

  /** Convenience constructors mirroring the TS API. Decorator authors can write
    * `DecoratorOutcome.allow(call)` / `DecoratorOutcome.deny(reason)` instead of the enum cases
    * directly.
    */
  def allow(call: RawToolCall): DecoratorOutcome = DecoratorOutcome.Allow(call)
  def deny(reason: String): DecoratorOutcome     = DecoratorOutcome.Deny(reason)
}

/** Backwards-compatible alias. New code should use [[DecoratorOutcome]] directly. Kept so existing
  * call sites (`Decorators.allow(...)`, `Decorators.deny(...)`) continue to compile.
  */
object Decorators {
  export DecoratorOutcome.{allow, deny}
}
