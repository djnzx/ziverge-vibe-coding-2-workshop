package tabbyshell

class CsvCodecTest extends munit.FunSuite:

  test("CSV parsing reads the header and preserves every cell as a string"):
    val input = "name,age,city\nAlice,30,Portland\nBob,25,Seattle\n"

    assertEquals(
      CsvCodec.parse(input, "open"),
      Right(
        Value.Table(
          Vector("name", "age", "city"),
          Vector(
            Vector(Value.Str("Alice"), Value.Str("30"), Value.Str("Portland")),
            Vector(Value.Str("Bob"), Value.Str("25"), Value.Str("Seattle"))
          )
        )
      )
    )

  test("CSV parsing supports quoted commas, doubled quotes, and embedded CRLF or LF"):
    val input =
      "name,note\r\n" +
        "Ada,\"first\r\nsecond, with \"\"quotes\"\"\"\n" +
        "Bob,\"one\ntwo\"\r\n"

    assertEquals(
      CsvCodec.parse(input, "open"),
      Right(
        Value.Table(
          Vector("name", "note"),
          Vector(
            Vector(Value.Str("Ada"), Value.Str("first\r\nsecond, with \"quotes\"")),
            Vector(Value.Str("Bob"), Value.Str("one\ntwo"))
          )
        )
      )
    )

  test("CSV parsing rejects ragged rows through the table smart constructor"):
    val input = "name,age\nAda,30\nBob\n"

    assertEquals(
      CsvCodec.parse(input, "open").left.map(_.message),
      Left("open: expected table, got ragged rows")
    )

  test("CSV rendering quotes headers and cells and uses LF record terminators"):
    val table: Value.Table = Value.Table(
      Vector("first,name", "remark\""),
      Vector(
        Vector(Value.Str("Ada"), Value.Str("a,b")),
        Vector(Value.Str("Bob"), Value.Str("said \"hi\"\nthen left"))
      )
    )

    assertEquals(
      CsvCodec.render(table),
      "\"first,name\",\"remark\"\"\"\n" +
        "Ada,\"a,b\"\n" +
        "Bob,\"said \"\"hi\"\"\nthen left\"\n"
    )

  test("CSV rendering writes scalar table cells without display formatting"):
    val table: Value.Table = Value.Table(
      Vector("name", "price", "bytes"),
      Vector(Vector(Value.Str("apple"), Value.Float(1.5), Value.Filesize(1500)))
    )

    assertEquals(CsvCodec.render(table), "name,price,bytes\napple,1.5,1500\n")

  test("CSV rendering preserves CRLF inside a quoted field while records end in LF"):
    val table: Value.Table = Value.Table(
      Vector("note"),
      Vector(Vector(Value.Str("first\r\nsecond")))
    )

    assertEquals(CsvCodec.render(table), "note\n\"first\r\nsecond\"\n")
