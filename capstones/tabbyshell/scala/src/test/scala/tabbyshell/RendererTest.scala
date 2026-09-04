package tabbyshell

class RendererTest extends munit.FunSuite:

  private val now  = 1700000000L
  private val opts = RenderOpts(color = false, now = now)

  private def render(v: Value): String   = Renderer.render(v, opts)
  private def lines(ls: String*): String = ls.mkString("", "\n", "\n")

  // --- scalars (SPEC 6.3) ---

  test("null renders as an empty line"):
    assertEquals(render(Value.Null), "\n")

  test("booleans render as true and false"):
    assertEquals(render(Value.Bool(true)), "true\n")
    assertEquals(render(Value.Bool(false)), "false\n")

  test("an int renders in base 10"):
    assertEquals(render(Value.Int(-42)), "-42\n")

  test("a float trims trailing zeros and a trailing dot"):
    assertEquals(render(Value.Float(1.5)), "1.5\n")
    assertEquals(render(Value.Float(2.0)), "2\n")
    assertEquals(render(Value.Float(1.23456)), "1.2346\n")

  test("a string renders raw and unquoted"):
    assertEquals(render(Value.Str("a b")), "a b\n")

  test("a string already ending in a newline is not given a second one"):
    assertEquals(render(Value.Str("hi\n")), "hi\n")

  // --- filesize (SPEC 6.5) ---

  test("filesizes below 1000 bytes render as B"):
    assertEquals(render(Value.Filesize(0)), "0 B\n")
    assertEquals(render(Value.Filesize(999)), "999 B\n")

  test("filesizes use SI units with one decimal, trimmed"):
    assertEquals(render(Value.Filesize(1000)), "1 KB\n")
    assertEquals(render(Value.Filesize(1500)), "1.5 KB\n")
    assertEquals(render(Value.Filesize(1000000)), "1 MB\n")
    assertEquals(render(Value.Filesize(1500000000)), "1.5 GB\n")
    assertEquals(render(Value.Filesize(2000000000000L)), "2 TB\n")

  test("filesize rounding is half away from zero"):
    assertEquals(render(Value.Filesize(1949)), "1.9 KB\n")
    assertEquals(render(Value.Filesize(1950)), "2 KB\n")

  test("a negative filesize keeps its sign"):
    assertEquals(render(Value.Filesize(-1500)), "-1.5 KB\n")

  // --- dates (SPEC 6.4) ---

  test("a date under a minute old renders as just now"):
    assertEquals(render(Value.Date(now - 59)), "just now\n")

  test("a date under an hour old renders in minutes"):
    assertEquals(render(Value.Date(now - 300)), "5 minutes ago\n")

  test("a date under a day old renders in hours"):
    assertEquals(render(Value.Date(now - 7200)), "2 hours ago\n")

  test("a date in the previous day window renders as yesterday"):
    assertEquals(render(Value.Date(now - 86400)), "yesterday\n")

  test("a date under a month old renders in days"):
    assertEquals(render(Value.Date(now - 86400 * 5)), "5 days ago\n")

  test("a date under a year old renders in months of 30 days"):
    assertEquals(render(Value.Date(now - 86400 * 60)), "2 months ago\n")

  test("a date over a year old renders as an ISO day"):
    assertEquals(render(Value.Date(1592222400)), "2020-06-15\n")

  test("the epoch renders as 1970-01-01"):
    assertEquals(render(Value.Date(0)), "1970-01-01\n")

  test("a future date renders as ISO unconditionally"):
    assertEquals(render(Value.Date(now + 1000)), "2023-11-14\n")

  // --- tables (SPEC 6.1, 6.2) — golden output from tests/26 ---

  private val items = Value.Table(
    Vector("name", "stock"),
    Vector(
      Vector(Value.Str("apple"), Value.Int(10)),
      Vector(Value.Str("banana"), Value.Int(25))
    )
  )

  test("a table renders with box drawing, an index column and right-aligned numerics"):
    assertEquals(
      render(items),
      lines(
        "╭───┬────────┬───────╮",
        "│ # │ name   │ stock │",
        "├───┼────────┼───────┤",
        "│ 0 │ apple  │    10 │",
        "│ 1 │ banana │    25 │",
        "╰───┴────────┴───────╯"
      )
    )

  test("a record renders as a two-column key/value table"):
    assertEquals(
      render(Value.Record(Vector("name" -> Value.Str("tabbyshell"), "version" -> Value.Str("0.1.0")))),
      lines(
        "╭─────────┬────────────╮",
        "│ key     │ value      │",
        "├─────────┼────────────┤",
        "│ name    │ tabbyshell │",
        "│ version │ 0.1.0      │",
        "╰─────────┴────────────╯"
      )
    )

  test("a list of scalars renders as a two-column index/value table"):
    assertEquals(
      render(Value.List(Vector(Value.Str("Alice"), Value.Str("Bob")))),
      lines(
        "╭───┬───────╮",
        "│ # │ value │",
        "├───┼───────┤",
        "│ 0 │ Alice │",
        "│ 1 │ Bob   │",
        "╰───┴───────╯"
      )
    )

  test("an empty table still renders its headers"):
    assertEquals(
      render(Value.Table(Vector("name", "size"), Vector.empty)),
      lines(
        "╭───┬──────┬──────╮",
        "│ # │ name │ size │",
        "├───┼──────┼──────┤",
        "╰───┴──────┴──────╯"
      )
    )

  test("a list of records with identical keys renders as a table"):
    val rec1 = Value.Record(Vector("a" -> Value.Int(1)))
    val rec2 = Value.Record(Vector("a" -> Value.Int(2)))
    assertEquals(
      render(Value.List(Vector(rec1, rec2))),
      lines(
        "╭───┬───╮",
        "│ # │ a │",
        "├───┼───┤",
        "│ 0 │ 1 │",
        "│ 1 │ 2 │",
        "╰───┴───╯"
      )
    )

  test("a list of records with differing keys renders one inline element per line"):
    val rec1 = Value.Record(Vector("name" -> Value.Str("apple")))
    val rec2 = Value.Record(Vector("name" -> Value.Str("banana"), "stock" -> Value.Int(25)))
    assertEquals(
      render(Value.List(Vector(rec1, rec2))),
      lines("0: {name: \"apple\"}", "1: {name: \"banana\", stock: 25}")
    )

  test("a long string cell is truncated with an ellipsis inside the column cap"):
    val long = "x" * 45
    val out  = render(Value.Table(Vector("c"), Vector(Vector(Value.Str(long)))))
    assert(out.contains("x" * 39 + "…"), out)
    assert(!out.contains("x" * 40), out)

  // --- inline form (SPEC 6.3) ---

  test("inline strings are JSON quoted and escaped"):
    assertEquals(Renderer.compact(Value.Str("with \"quotes\""), opts), "\"with \\\"quotes\\\"\"")

  test("inline records use unquoted keys"):
    assertEquals(
      Renderer.compact(Value.Record(Vector("a" -> Value.Int(1), "b" -> Value.Str("x"))), opts),
      "{a: 1, b: \"x\"}"
    )

  test("inline lists are bracketed"):
    assertEquals(Renderer.compact(Value.List(Vector(Value.Int(1), Value.Int(2))), opts), "[1, 2]")

  test("inline tables report their shape and never recurse"):
    assertEquals(Renderer.compact(items, opts), "<table 2×2>")
