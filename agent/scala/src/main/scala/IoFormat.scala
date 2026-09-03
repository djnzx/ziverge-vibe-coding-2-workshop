package workshop.agent

enum IoFormat:
  case Json
  case Text

  /** Parse a single line of protocol input.
    *
    *   - `Text`: consume the raw line as-is
    *   - `Json`: parse `{"content":"..."}` and fall back to raw line on parse failure
    */
  def parseInput(line: String): String = this match {
    case IoFormat.Text => line
    case IoFormat.Json =>
      zio.blocks.schema.json.Json
        .parse(line)
        .toOption
        .flatMap(_.get("content").as[String].toOption)
        .getOrElse(line)
  }

  /** Write a `StepOutcome` to stdout in this format. */
  def writeProtocol(outcome: StepOutcome): Unit = {
    val responseJson = outcome.asJson
    this match {
      case IoFormat.Text =>
        val content = responseJson.get("content").as[String].toOption.getOrElse("")
        println(content)
      case IoFormat.Json =>
        println(responseJson.print)
    }
    Console.out.flush()
  }

object IoFormat:
  def fromString(s: String): Option[IoFormat] = s match {
    case "json" => Some(IoFormat.Json)
    case "text" => Some(IoFormat.Text)
    case _      => None
  }
