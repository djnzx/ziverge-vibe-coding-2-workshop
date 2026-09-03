package workshop.agent.internal

object TextUtils {
  def truncateSingleLine(text: String, maxChars: Int): String = {
    val normalized = text.replaceAll("\\s+", " ").trim
    if (normalized.length <= maxChars) normalized
    else if (maxChars <= 3) normalized.take(maxChars)
    else normalized.take(maxChars - 3) + "..."
  }

  /** Split a multi-line string on lines whose exact content equals `delimiterLine`. Trims each
    * section, drops empty sections.
    */
  def splitSections(content: String, delimiterLine: String): Vector[String] =
    content
      .split(s"(?m)^${java.util.regex.Pattern.quote(delimiterLine)}$$")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
}
