package workshop.agent

import scala.util.Try

/** A set of regex patterns the agent treats as secrets. Used to redact secrets from tool output,
  * audit logs, etc. Construct directly from a `Vector[String]` of regexes, or use
  * `SecretPatterns.default` for the bundled pattern set.
  */
final case class SecretPatterns(patterns: Vector[String]) {

  /** Replace every match of any pattern with `[REDACTED]`. Invalid regex patterns are silently
    * skipped (the input is returned unchanged for that pattern).
    */
  def redact(text: String): String =
    patterns.foldLeft(text) { (result, pattern) =>
      Try(pattern.r.replaceAllIn(result, "[REDACTED]")).getOrElse(result)
    }

  def isEmpty: Boolean  = patterns.isEmpty
  def nonEmpty: Boolean = patterns.nonEmpty
}

object SecretPatterns {
  val default: SecretPatterns = SecretPatterns(
    Vector(
      "(?i)api[_-]?key\\s*[:=]\\s*\\S+",
      "(?i)secret\\s*[:=]\\s*\\S+",
      "(?i)password\\s*[:=]\\s*\\S+",
      "(?i)token\\s*[:=]\\s*\\S+",
      "sk-[a-zA-Z0-9]{20,}",
      "ghp_[a-zA-Z0-9]{36,}"
    )
  )
}
