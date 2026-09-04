package tabbyshell

import io.circe.Json

class AiTest extends munit.FunSuite:

  private def responseWith(content: String): String =
    Json
      .obj(
        "choices" -> Json.arr(
          Json.obj("message" -> Json.obj("content" -> Json.fromString(content)))
        )
      )
      .noSpaces

  test("disabled AI returns the trailing-trimmed fallback and never calls transport"):
    var calls     = 0
    val transport = new Ai.Transport:
      def post(request: Ai.Request): Either[String, Ai.Response] =
        calls += 1
        Right(Ai.Response(200, responseWith("""{"kind":"string","value":"unused"}""")))
    val formatter = Ai.formatter(Ai.Config(Some("key"), disabled = true), transport)

    assertEquals(
      formatter.format("echo", Vector("tabby"), " leading stays\n\t"),
      AiFormat(Value.Str(" leading stays"), Vector("(ai formatting unavailable: disabled)"))
    )
    assertEquals(calls, 0)

  test("OpenRouter response decoding strips a single JSON fence and builds a string-cell table"):
    val content =
      """```json
        |{"kind":"table","columns":["name","count"],"rows":[["tabby","2"],["otter","3"]]}
        |```""".stripMargin
    val transport = new Ai.Transport:
      def post(request: Ai.Request): Either[String, Ai.Response] = Right(Ai.Response(200, responseWith(content)))
    val formatter = Ai.formatter(Ai.Config(Some("key")), transport)

    assertEquals(
      formatter.format("inventory", Vector.empty, "raw"),
      AiFormat(
        Value.Table(
          Vector("name", "count"),
          Vector(
            Vector(Value.Str("tabby"), Value.Str("2")),
            Vector(Value.Str("otter"), Value.Str("3"))
          )
        )
      )
    )

  test("the request contains the fixed model, zero temperature, command context, and configured base URL"):
    var captured  = Option.empty[Ai.Request]
    val transport = new Ai.Transport:
      def post(request: Ai.Request): Either[String, Ai.Response] =
        captured = Some(request)
        Right(Ai.Response(200, responseWith("""{"kind":"string","value":"formatted"}""")))
    val formatter = Ai.formatter(Ai.Config(Some("secret"), Some("http://mock.example")), transport)

    assertEquals(formatter.format("git", Vector("status", "--short"), " M file\n"), AiFormat(Value.Str("formatted")))
    captured match
      case Some(request) =>
        assertEquals(request.endpoint, "http://mock.example/api/v1/chat/completions")
        assertEquals(request.headers, Vector("Authorization" -> "Bearer secret", "Content-Type" -> "application/json"))
        assert(request.body.contains("\"model\":\"google/gemini-2.5-flash-lite\""))
        assert(request.body.contains("\"temperature\":0"))
        assert(request.body.contains("command: git status --short\\n\\noutput:\\n M file\\n"))
      case None => fail("transport did not receive a request")

  test("malformed table rows fall back once instead of escaping an exception"):
    val malformed = """{"kind":"table","columns":["name"],"rows":[["tabby","extra"]]}"""
    val transport = new Ai.Transport:
      def post(request: Ai.Request): Either[String, Ai.Response] = Right(Ai.Response(200, responseWith(malformed)))
    val formatter = Ai.formatter(Ai.Config(Some("key")), transport)

    assertEquals(
      formatter.format("tool", Vector.empty, "raw\n"),
      AiFormat(Value.Str("raw"), Vector("(ai formatting unavailable: table row does not match column count)"))
    )

  test("transport exceptions also become a single fallback warning"):
    val transport = new Ai.Transport:
      def post(request: Ai.Request): Either[String, Ai.Response] = throw RuntimeException("offline")
    val formatter = Ai.formatter(Ai.Config(Some("key")), transport)

    assertEquals(
      formatter.format("tool", Vector.empty, "raw\n"),
      AiFormat(Value.Str("raw"), Vector("(ai formatting unavailable: offline)"))
    )
