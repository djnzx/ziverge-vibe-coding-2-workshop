package snap

import java.io.PrintStream
import java.nio.charset.StandardCharsets

/** The process's output edge.
  *
  * SPEC §10 fixes output as UTF-8 with LF line endings, so nothing here uses `println`: its terminator is the platform's, which is CRLF on Windows and would
  * break byte-exactness. Write records with an explicit `\n`.
  *
  * Commands take a `Streams` rather than touching `System.out` directly so that tests can capture bytes, and so §7.11's `auto` presentation can be decided from
  * the injected `stdoutIsTty` / `stderrIsTty` flags. SPEC §11 requires each implementation to unit-test `auto` selection for TTY and non-TTY stdout and stderr
  * independently, which the shared YAML harness cannot do because it captures both streams through pipes.
  */
final case class Streams(
  out: PrintStream,
  err: PrintStream,
  stdoutIsTty: Boolean,
  stderrIsTty: Boolean
):
  def flush(): Unit =
    out.flush()
    err.flush()

object Streams:
  /** The real process streams.
    *
    * Known limitation: the JVM exposes no per-stream `isatty`. `Console.isTerminal` (JDK 22+) describes the console as a whole, so `snap 2>err.txt` on a
    * terminal still reports stderr as a TTY. Revisit if a §7.11 acceptance case depends on one stream being redirected while the other is not.
    */
  def standard: Streams =
    val terminal = Option(System.console()).exists(_.isTerminal())
    Streams(
      out = new PrintStream(System.out, false, StandardCharsets.UTF_8),
      err = new PrintStream(System.err, false, StandardCharsets.UTF_8),
      stdoutIsTty = terminal,
      stderrIsTty = terminal
    )
