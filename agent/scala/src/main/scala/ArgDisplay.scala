package workshop.agent

import zio.blocks.schema.json.Json

/** How a tool-call argument value renders in a terminal: scalars become their string form;
  * multi-line strings render YAML-block-scalar-style with line-count truncation; objects and arrays
  * render as compact JSON. Separated from [[TerminalOutput.Real]] so the format can be unit-tested
  * directly without driving the renderer.
  */
object ArgDisplay {
  private val MaxLines = 8

  /** Render a single tool-call argument value for display. Multi-line strings are shown
    * YAML-block-scalar-style with a `[+N more lines]` truncation marker.
    */
  def render(value: Json): String =
    value.as[String].toOption match {
      case Some(s) if s.contains('\n') =>
        val lines = s.linesIterator.toVector
        if (lines.length > MaxLines) {
          val shown = lines.take(MaxLines).map(l => s"      $l").mkString("\n")
          s"|\n$shown\n      [+${lines.length - MaxLines} more lines]"
        } else
          lines.map(l => s"      $l").mkString("|\n", "\n", "")
      case Some(s) => s
      case None    => value.print
    }
}
