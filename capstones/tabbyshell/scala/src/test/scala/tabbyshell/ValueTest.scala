package tabbyshell

class ValueTest extends munit.FunSuite:

  private def int(n: Long): Value = Value.Int(n)

  test("a table with uniform rows is built"):
    assertEquals(
      Values.table("open", Vector("a", "b"), Vector(Vector(int(1), int(2)))),
      Right(Value.Table(Vector("a", "b"), Vector(Vector(int(1), int(2)))))
    )

  test("a table with a row shorter than its columns is rejected"):
    assertEquals(
      Values.table("open", Vector("a", "b"), Vector(Vector(int(1)))).left.map(_.message),
      Left("open: expected table, got ragged rows")
    )

  test("a table with a row longer than its columns is rejected"):
    assert(Values.table("open", Vector("a"), Vector(Vector(int(1), int(2)))).isLeft)

  test("a table with no rows is valid"):
    assertEquals(
      Values.table("ls", Vector("name"), Vector.empty),
      Right(Value.Table(Vector("name"), Vector.empty))
    )

  test("type names follow the SPEC 3.3 vocabulary"):
    assertEquals(Value.Null.typeName, "null")
    assertEquals(Value.Bool(true).typeName, "bool")
    assertEquals(Value.Int(1).typeName, "int")
    assertEquals(Value.Float(1.0).typeName, "float")
    assertEquals(Value.Str("x").typeName, "string")
    assertEquals(Value.Filesize(1).typeName, "filesize")
    assertEquals(Value.Date(1).typeName, "date")
    assertEquals(Value.List(Vector.empty).typeName, "list")
    assertEquals(Value.Record(Vector.empty).typeName, "record")
    assertEquals(Value.Table(Vector.empty, Vector.empty).typeName, "table")

  test("scalars are the seven non-collection variants"):
    val scalars     = Vector(Value.Null, Value.Bool(true), Value.Int(1), Value.Float(1.0), Value.Str("x"), Value.Filesize(1), Value.Date(1))
    val collections = Vector(Value.List(Vector.empty), Value.Record(Vector.empty), Value.Table(Vector.empty, Vector.empty))
    assert(scalars.forall(_.isScalar))
    assert(collections.forall(!_.isScalar))
