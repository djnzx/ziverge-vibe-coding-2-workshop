package tabbyshell

/** SPEC 6.6. Expectations are built with fansi rather than hand-written escape sequences, so the tests stay correct whatever bytes the library emits.
  */
class RendererColorTest extends munit.FunSuite:

  private val now   = 1700000000L
  private val plain = RenderOpts(color = false, now = now)
  private val color = RenderOpts(color = true, now = now)

  private val bold   = fansi.Bold.On
  private val dim    = fansi.Bold.Faint
  private val header = fansi.Bold.On ++ fansi.Color.Cyan

  private val table = Value.Table(
    Vector("name", "stock"),
    Vector(Vector(Value.Str("apple"), Value.Int(10)))
  )

  private def painted(attrs: fansi.Attrs, text: String): String =
    attrs(fansi.Str(text)).render

  test("colour is purely additive: stripping it returns the colour-off bytes"):
    assertEquals(
      fansi.Str(Renderer.render(table, color)).plainText,
      Renderer.render(table, plain)
    )

  test("colour off emits no escape sequences at all"):
    val out = Renderer.render(table, plain)
    assertEquals(fansi.Str(out).plainText, out)

  test("headers render bold cyan"):
    assert(Renderer.render(table, color).contains(painted(header, "name")))

  test("ints render green"):
    assert(Renderer.render(table, color).contains(painted(fansi.Color.Green, "10")))

  test("filesizes render green"):
    val v = Value.Table(Vector("size"), Vector(Vector(Value.Filesize(1500))))
    assert(Renderer.render(v, color).contains(painted(fansi.Color.Green, "1.5 KB")))

  test("dates render yellow"):
    val v = Value.Table(Vector("d"), Vector(Vector(Value.Date(0))))
    assert(Renderer.render(v, color).contains(painted(fansi.Color.Yellow, "1970-01-01")))

  test("booleans render green when true and red when false"):
    val out = Renderer.render(Value.List(Vector(Value.Bool(true), Value.Bool(false))), color)
    assert(out.contains(painted(fansi.Color.Green, "true")), out)
    assert(out.contains(painted(fansi.Color.Red, "false")), out)

  test("strings and floats are left uncoloured"):
    val v   = Value.Table(Vector("s", "f"), Vector(Vector(Value.Str("apple"), Value.Float(1.5))))
    val out = Renderer.render(v, color)
    assert(out.contains(" apple "), out)
    assert(out.contains(" 1.5 "), out)

  test("box borders render dim"):
    assert(Renderer.render(table, color).contains(painted(dim, "│")))

  test("the index column renders dim"):
    assert(Renderer.render(table, color).contains(painted(dim, "0")))

  test("an error renders with a cross, plain when colour is off"):
    val err = ShellError.TypeMismatch("where", "table", "string")
    assertEquals(Renderer.renderError(err, plain), "✗ where: expected table, got string\n")

  test("an error renders bold red when colour is on"):
    val err = ShellError.TypeMismatch("where", "table", "string")
    assertEquals(
      Renderer.renderError(err, color),
      painted(bold ++ fansi.Color.Red, "✗ where: expected table, got string") + "\n"
    )
