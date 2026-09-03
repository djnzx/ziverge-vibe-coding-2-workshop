package workshop.agent.internal

object Frontmatter {
  private def stripWrappingQuotes(value: String): String =
    if (
      value.length >= 2 &&
      ((value.startsWith("\"") && value.endsWith("\"")) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) value.substring(1, value.length - 1)
    else value

  def parseFrontmatter(content: String): Map[String, String] = {
    val lines = content.linesIterator.toVector

    if (!lines.headOption.contains("---")) Map.empty
    else {
      val remainder = lines.drop(1)
      val endIndex  = remainder.indexWhere(_ == "---")

      if (endIndex < 0) Map.empty
      else
        remainder
          .take(endIndex)
          .flatMap { line =>
            line.split(": ", 2) match {
              case Array(key, value) =>
                Some(key.trim -> stripWrappingQuotes(value.trim))
              case _ => None
            }
          }
          .toMap
    }
  }

  def stripFrontmatter(content: String): String = {
    val lines = content.linesIterator.toVector

    if (!lines.headOption.contains("---")) content
    else {
      val remainder = lines.drop(1)
      val endIndex  = remainder.indexWhere(_ == "---")

      if (endIndex < 0) content
      else {
        val bodyLines = remainder.drop(endIndex + 1)
        val normalizedBody = bodyLines match {
          case first +: rest if first.trim.isEmpty => rest
          case _                                   => bodyLines
        }

        normalizedBody.mkString("\n")
      }
    }
  }
}
