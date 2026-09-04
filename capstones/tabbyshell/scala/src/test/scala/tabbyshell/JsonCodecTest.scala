package tabbyshell

class JsonCodecTest extends munit.FunSuite:

  test("JSON scalars decode to their corresponding Values"):
    assertEquals(JsonCodec.parse("null", "open"), Right(Value.Null))
    assertEquals(JsonCodec.parse("true", "open"), Right(Value.Bool(true)))
    assertEquals(JsonCodec.parse("42", "open"), Right(Value.Int(42)))
    assertEquals(JsonCodec.parse("1.5", "open"), Right(Value.Float(1.5)))
    assertEquals(JsonCodec.parse("\"tabby\\ncat\"", "open"), Right(Value.Str("tabby\ncat")))

  test("JSON objects preserve their source key order"):
    assertEquals(
      JsonCodec.parse("{\"name\":\"tabby\",\"version\":1}", "open"),
      Right(Value.Record(Vector("name" -> Value.Str("tabby"), "version" -> Value.Int(1))))
    )

  test("a JSON array of records with an identical ordered key sequence becomes a table"):
    assertEquals(
      JsonCodec.parse("[{\"name\":\"apple\",\"stock\":10},{\"name\":\"banana\",\"stock\":25}]", "open"),
      Right(
        Value.Table(
          Vector("name", "stock"),
          Vector(
            Vector(Value.Str("apple"), Value.Int(10)),
            Vector(Value.Str("banana"), Value.Int(25))
          )
        )
      )
    )

  test("a JSON array of records with different key sequences remains a list"):
    assertEquals(
      JsonCodec.parse("[{\"name\":\"apple\",\"stock\":10},{\"stock\":25,\"name\":\"banana\"}]", "open"),
      Right(
        Value.List(
          Vector(
            Value.Record(Vector("name" -> Value.Str("apple"), "stock" -> Value.Int(10))),
            Value.Record(Vector("stock" -> Value.Int(25), "name" -> Value.Str("banana")))
          )
        )
      )
    )

  test("an empty JSON array remains a list"):
    assertEquals(JsonCodec.parse("[]", "open"), Right(Value.List(Vector.empty)))

  test("malformed JSON is a command-scoped BadArg"):
    JsonCodec.parse("{", "open") match
      case Left(ShellError.BadArg("open", detail)) => assert(detail.startsWith("invalid JSON:"), detail)
      case other                                   => fail(s"expected open-scoped JSON error, got $other")

  test("values render as two-space JSON with one trailing newline"):
    val value = Value.Table(
      Vector("name", "size", "modified"),
      Vector(Vector(Value.Str("tabby"), Value.Filesize(12000), Value.Date(123)))
    )

    assertEquals(
      JsonCodec.render(value),
      """[
        |  {
        |    "name": "tabby",
        |    "size": 12000,
        |    "modified": 123
        |  }
        |]
        |""".stripMargin
    )

  test("rendering a record preserves its field order and escapes strings as JSON"):
    assertEquals(
      JsonCodec.render(Value.Record(Vector("second" -> Value.Int(2), "first" -> Value.Str("a\"b")))),
      """{
        |  "second": 2,
        |  "first": "a\"b"
        |}
        |""".stripMargin
    )
