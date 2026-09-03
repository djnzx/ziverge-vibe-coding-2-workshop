package workshop.agent

import java.nio.file.Path

final case class CheckpointInfo(id: String, timestamp: String, path: Path)
