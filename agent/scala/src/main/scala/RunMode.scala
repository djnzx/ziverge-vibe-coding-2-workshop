package workshop.agent

import java.nio.file.Path

enum RunMode:
  case AutoDetect
  case Interactive
  case Protocol
  case FileMode(path: Path)
