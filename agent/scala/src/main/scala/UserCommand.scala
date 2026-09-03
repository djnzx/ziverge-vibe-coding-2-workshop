package workshop.agent

/** A user input, parsed into the kind of action it represents. Built-in slash-commands (`/quit`,
  * `/exit`, `/compact`) become their own variants; any other `/foo bar baz` becomes [[Slash]] with
  * `name` and `args` separated; non-slash input is a [[Prompt]] for the LLM.
  *
  * Lookup of [[Slash]] against the user-configured commands directory happens at the agent layer,
  * not at parse time — see [[Agent.step]] for the resolution into `StepOutcome.Custom` or
  * `StepOutcome.Unknown`.
  */
enum UserCommand {
  case Quit
  case Compact
  case Slash(name: String, args: String)
  case Prompt(text: String)
}

object UserCommand {

  /** Parse a single user-input line. Lines starting with `/` are slash-commands;
    * `quit`/`exit`/`compact` are built-ins; everything else is a [[Prompt]].
    */
  def parse(input: String): UserCommand = {
    val trimmed = input.trim
    if (!trimmed.startsWith("/")) UserCommand.Prompt(input)
    else {
      val body  = trimmed.stripPrefix("/").trim
      val parts = body.split("\\s+", 2)
      val name  = parts.headOption.getOrElse("")
      val args  = if (parts.length > 1) parts(1) else ""
      name match {
        case "quit" | "exit" => UserCommand.Quit
        case "compact"       => UserCommand.Compact
        case ""              => UserCommand.Slash("", "")
        case other           => UserCommand.Slash(other, args)
      }
    }
  }
}
