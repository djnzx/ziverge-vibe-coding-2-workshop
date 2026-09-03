package workshop.agent.internal

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Try

object FileIO {
  def readFileIfPresent(path: Path): Option[String] =
    if (Files.isRegularFile(path)) Try(Files.readString(path, StandardCharsets.UTF_8)).toOption
    else None
}
