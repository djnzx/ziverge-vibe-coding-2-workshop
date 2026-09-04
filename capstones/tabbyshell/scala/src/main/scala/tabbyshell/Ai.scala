package tabbyshell

import io.circe.Json
import io.circe.parser.parse as parseJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Result of formatting successful external-command output.
  *
  * Formatting is deliberately non-failing: callers can always continue a pipeline. A warning is data for the terminal boundary to render on stderr, never a
  * side effect of this module.
  */
final case class AiFormat(value: Value, warnings: Vector[String] = Vector.empty)

/** Injectable boundary for turning raw external-command output into a shell value. */
trait AiFormatter:
  def format(command: String, args: Vector[String], stdout: String): AiFormat

/** OpenRouter client and response decoder for SPEC 5.15. */
object Ai:

  private val defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions"
  private val endpointPath    = "/api/v1/chat/completions"
  private val model           = "google/gemini-2.5-flash-lite"
  private val timeout         = Duration.ofSeconds(30L)
  private val systemPrompt    =
    """You convert raw command output into structured data for a typed shell.
      |Reply with ONLY a JSON object, no prose, no markdown fences.
      |
      |Schema:
      |{"kind":"table","columns":["..."],"rows":[["...", "..."]]}
      |{"kind":"string","value":"..."}
      |
      |All cells in "rows" are strings. Use "table" only when the output has
      |clear tabular structure with consistent columns. Otherwise use "string"
      |with the cleaned-up text.""".stripMargin

  /** Configuration is injectable so tests never need live credentials or a server. */
  final case class Config(apiKey: Option[String], baseUrl: Option[String] = None, disabled: Boolean = false)

  final case class Request(endpoint: String, headers: Vector[(String, String)], body: String, timeout: Duration)
  final case class Response(status: Int, body: String)

  /** HTTP is a narrow seam: production uses the JDK client while tests return fixtures. */
  trait Transport:
    def post(request: Request): Either[String, Response]

  /** Constructs a formatter from explicit dependencies. */
  def formatter(config: Config, transport: Transport): AiFormatter = new AiFormatter:
    def format(command: String, args: Vector[String], stdout: String): AiFormat =
      try
        if config.disabled then fallback(stdout, "disabled")
        else
          config.apiKey.filter(_.nonEmpty) match
            case None      => fallback(stdout, "missing OPENROUTER_API_KEY")
            case Some(key) =>
              transport
                .post(requestFor(config, key, command, args, stdout))
                .flatMap: response =>
                  if response.status < 200 || response.status >= 300 then Left(s"HTTP ${response.status}")
                  else decodeResponse(response.body)
                .fold(reason => fallback(stdout, reason), value => AiFormat(value))
      catch case error: Throwable => fallback(stdout, errorMessage(error))

  /** Reads the AI-only environment at the application boundary. */
  def live: AiFormatter = formatter(environmentConfig, JdkTransport(HttpClient.newBuilder.connectTimeout(timeout).build()))

  private def environmentConfig: Config =
    Config(
      apiKey = sys.env.get("OPENROUTER_API_KEY"),
      baseUrl = sys.env.get("OPENROUTER_BASE_URL").filter(_.nonEmpty),
      disabled = sys.env.get("TABBY_DISABLE_AI").exists(_.nonEmpty)
    )

  private def requestFor(config: Config, apiKey: String, command: String, args: Vector[String], stdout: String): Request =
    val commandLine = (command +: args).mkString(" ")
    val body        = Json
      .obj(
        "model"       -> Json.fromString(model),
        "temperature" -> Json.fromInt(0),
        "messages"    -> Json.arr(
          Json.obj("role" -> Json.fromString("system"), "content" -> Json.fromString(systemPrompt)),
          Json.obj(
            "role"    -> Json.fromString("user"),
            "content" -> Json.fromString(s"command: $commandLine\n\noutput:\n$stdout")
          )
        )
      )
      .noSpaces
    Request(
      endpoint(config.baseUrl),
      Vector("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json"),
      body,
      timeout
    )

  private def endpoint(baseUrl: Option[String]): String =
    baseUrl.fold(defaultEndpoint): base =>
      val normalized = base.stripSuffix("/")
      if normalized.endsWith(endpointPath) then normalized
      else if normalized.endsWith("/api/v1") then normalized + "/chat/completions"
      else normalized + endpointPath

  private def decodeResponse(body: String): Either[String, Value] =
    for
      response <- parseJson(body).left.map(error => s"invalid response JSON: ${error.message}")
      content  <- responseContent(response)
      payload  <- parseJson(stripFence(content)).left.map(error => s"invalid formatted JSON: ${error.message}")
      value    <- decodePayload(payload)
    yield value

  private def responseContent(response: Json): Either[String, String] =
    for
      root    <- response.asObject.toRight("response is not a JSON object")
      choices <- root("choices").flatMap(_.asArray).toRight("response is missing choices")
      choice  <- choices.headOption.toRight("response contains no choices")
      message <- choice.asObject.flatMap(_("message")).flatMap(_.asObject).toRight("response choice is missing message")
      content <- message("content").flatMap(_.asString).toRight("response message is missing content")
    yield content

  private def decodePayload(payload: Json): Either[String, Value] =
    for
      objectValue <- payload.asObject.toRight("formatted response is not a JSON object")
      kind        <- objectValue("kind").flatMap(_.asString).toRight("formatted response is missing kind")
      value       <- kind match
        case "string" => objectValue("value").flatMap(_.asString).toRight("string response is missing value").map(Value.Str.apply)
        case "table"  => decodeTable(objectValue)
        case other    => Left(s"unsupported formatted response kind: $other")
    yield value

  private def decodeTable(payload: io.circe.JsonObject): Either[String, Value] =
    for
      columnsJson <- payload("columns").flatMap(_.asArray).toRight("table response is missing columns")
      columns     <- decodeStrings(columnsJson, "table columns")
      rowsJson    <- payload("rows").flatMap(_.asArray).toRight("table response is missing rows")
      rows        <- decodeRows(rowsJson, columns.size)
      table       <- Values.table("ai", columns, rows).left.map(_.message)
    yield table

  private def decodeRows(rows: Vector[Json], columnCount: Int): Either[String, Vector[Vector[Value]]] =
    rows.foldRight[Either[String, Vector[Vector[Value]]]](Right(Vector.empty)): (row, decoded) =>
      for
        rest      <- decoded
        rowJson   <- row.asArray.toRight("table row is not an array")
        cells     <- decodeStrings(rowJson, "table row")
        validated <-
          if cells.sizeIs == columnCount then Right(cells.map(Value.Str.apply))
          else Left("table row does not match column count")
      yield validated +: rest

  private def decodeStrings(values: Vector[Json], description: String): Either[String, Vector[String]] =
    values.foldRight[Either[String, Vector[String]]](Right(Vector.empty)): (value, decoded) =>
      for
        rest   <- decoded
        string <- value.asString.toRight(s"$description contains a non-string value")
      yield string +: rest

  /** Removes one optional Markdown fence pair before parsing model output. */
  private def stripFence(content: String): String =
    val trimmed = content.trim
    val prefix  =
      if trimmed.startsWith("```json") then Some("```json")
      else if trimmed.startsWith("```") then Some("```")
      else None
    prefix.fold(trimmed): marker =>
      val withoutLeading = trimmed.drop(marker.length).dropWhile(character => character == '\n' || character == '\r')
      if withoutLeading.endsWith("```") then withoutLeading.dropRight(3).trim else withoutLeading

  private def fallback(stdout: String, reason: String): AiFormat =
    AiFormat(Value.Str(trimTrailingWhitespace(stdout)), Vector(s"(ai formatting unavailable: $reason)"))

  private def trimTrailingWhitespace(value: String): String = value.reverse.dropWhile(_.isWhitespace).reverse

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final case class JdkTransport(client: HttpClient) extends Transport:
    def post(request: Request): Either[String, Response] =
      try
        val builder = HttpRequest
          .newBuilder(URI.create(request.endpoint))
          .timeout(request.timeout)
          .POST(HttpRequest.BodyPublishers.ofString(request.body, StandardCharsets.UTF_8))
        request.headers.foreach: (name, value) =>
          builder.header(name, value)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        Right(Response(response.statusCode, response.body))
      catch case error: Throwable => Left(errorMessage(error))
