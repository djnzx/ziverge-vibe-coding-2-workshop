package workshop.agent

import java.nio.file.Path

final case class CommandPrompt(
  name: String,
  description: String,
  argumentHint: Option[String],
  path: Path
)
