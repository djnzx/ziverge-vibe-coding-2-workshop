package snap

/** SPEC §7 — the argument grammar and command dispatch.
  *
  * The grammar is positional and strict: each option appears at most once, only in the position shown in §7, and unknown options, extra operands, and missing
  * option values are errors. Parse it here by hand rather than with a general option library, whose relaxed placement rules would accept input §7 forbids.
  *
  * Scaffold: this dispatches nothing yet. Replace the body as the commands in `Commands.scala` come online, keeping the exit statuses from SPEC §10 — 0 for
  * success, 1 for an expected error, 2 for an unexpected internal failure.
  */
object Cli:
  def run(args: Vector[String], streams: Streams): Int =
    val _ = args
    streams.err.print("snap: not implemented\n")
    1
