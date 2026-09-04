package tabbyshell

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Concrete contract examples for the typed transforms and serializers in SPEC 5.6–5.14. */
class StructuredBuiltinsTest extends munit.FunSuite:

  private val numericTable = Value.Table(
    Vector("name", "value"),
    Vector(
      Vector(Value.Str("one"), Value.Int(1)),
      Vector(Value.Str("fraction"), Value.Float(2.5)),
      Vector(Value.Str("bytes"), Value.Filesize(3)),
      Vector(Value.Str("five"), Value.Int(5))
    )
  )

  private def result[A](either: Either[ShellError, A]): A =
    either.fold(error => fail(error.message), identity)

  private def state(cwd: Path): ShellState =
    ShellState(cwd.toString, prevCwd = None, home = cwd.toString, now = 1725408000L, color = true)

  private def withTempDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("tabbyshell-structured-builtins-")
    try test(directory)
    finally
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.sortBy(path => -path.getNameCount).foreach(Files.deleteIfExists)
      finally paths.close()

  test("where compares int, float, and filesize cells numerically"):
    assertEquals(
      result(Builtins.where(numericTable, "value", ">", Literal.Float(2.0))),
      Value.Table(
        Vector("name", "value"),
        Vector(
          Vector(Value.Str("fraction"), Value.Float(2.5)),
          Vector(Value.Str("bytes"), Value.Filesize(3)),
          Vector(Value.Str("five"), Value.Int(5))
        )
      )
    )

  test("where preserves a table's schema and reports missing columns"):
    val table: Value.Table = Value.Table(
      Vector("name", "enabled"),
      Vector(Vector(Value.Str("tabby"), Value.Bool(true)), Vector(Value.Str("otter"), Value.Bool(false)))
    )

    assertEquals(
      result(Builtins.where(table, "enabled", "==", Literal.Bool(true))),
      Value.Table(Vector("name", "enabled"), Vector(Vector(Value.Str("tabby"), Value.Bool(true))))
    )
    assertEquals(
      result(Builtins.where(table, "name", ">", Literal.Str("otter"))),
      Value.Table(Vector("name", "enabled"), Vector(Vector(Value.Str("tabby"), Value.Bool(true))))
    )
    assertEquals(
      Builtins.where(table, "missing", "==", Literal.Bool(true)).left.map(_.message),
      Left("where: column not found: missing")
    )

  test("where rejects values incompatible with its literal"):
    Builtins.where(numericTable, "name", "==", Literal.Int(1)) match
      case Left(ShellError.TypeMismatch("where", _, _)) => ()
      case other                                        => fail(s"expected where type mismatch, got $other")

  test("select projects requested columns in requested order"):
    val table: Value.Table = Value.Table(
      Vector("name", "size", "kind"),
      Vector(Vector(Value.Str("tabby"), Value.Filesize(12), Value.Str("cat")))
    )

    assertEquals(
      result(Builtins.select(table, Vector("kind", "name"))),
      Value.Table(Vector("kind", "name"), Vector(Vector(Value.Str("cat"), Value.Str("tabby"))))
    )
    assertEquals(
      Builtins.select(table, Vector("missing")).left.map(_.message),
      Left("select: column not found: missing")
    )
    assertEquals(
      Builtins.select(table, Vector.empty).left.map(_.message),
      Left("select: missing required argument: column")
    )

  test("sort-by is stable across numerically equal values and supports reverse"):
    val table: Value.Table = Value.Table(
      Vector("name", "value"),
      Vector(
        Vector(Value.Str("int-two"), Value.Int(2)),
        Vector(Value.Str("float-one-and-a-half"), Value.Float(1.5)),
        Vector(Value.Str("bytes-two"), Value.Filesize(2)),
        Vector(Value.Str("int-one"), Value.Int(1))
      )
    )

    assertEquals(
      result(Builtins.sortBy(table, "value", reverse = false)),
      Value.Table(
        table.columns,
        Vector(table.rows(3), table.rows(1), table.rows(0), table.rows(2))
      )
    )
    assertEquals(
      result(Builtins.sortBy(table, "value", reverse = true)),
      Value.Table(
        table.columns,
        Vector(table.rows(0), table.rows(2), table.rows(1), table.rows(3))
      )
    )

  test("sort-by reports a missing column and rejects mixed-type columns"):
    val mixed = Value.Table(Vector("value"), Vector(Vector(Value.Str("text")), Vector(Value.Int(1))))

    assertEquals(
      Builtins.sortBy(numericTable, "missing", reverse = false).left.map(_.message),
      Left("sort-by: column not found: missing")
    )
    Builtins.sortBy(mixed, "value", reverse = false) match
      case Left(ShellError.TypeMismatch("sort-by", _, _)) => ()
      case other                                          => fail(s"expected mixed sort to fail, got $other")

  test("first and last without a count return a record or element"):
    val table = Value.Table(
      Vector("name", "count"),
      Vector(Vector(Value.Str("first"), Value.Int(1)), Vector(Value.Str("last"), Value.Int(2)))
    )
    val list = Value.List(Vector(Value.Str("first"), Value.Str("last")))

    assertEquals(
      result(Builtins.first(table, None)),
      Value.Record(Vector("name" -> Value.Str("first"), "count" -> Value.Int(1)))
    )
    assertEquals(
      result(Builtins.last(table, None)),
      Value.Record(Vector("name" -> Value.Str("last"), "count" -> Value.Int(2)))
    )
    assertEquals(result(Builtins.first(list, None)), Value.Str("first"))
    assertEquals(result(Builtins.last(list, None)), Value.Str("last"))

  test("first and last with a count retain table and list shapes, including zero"):
    val table: Value.Table = Value.Table(
      Vector("name"),
      Vector(Vector(Value.Str("one")), Vector(Value.Str("two")), Vector(Value.Str("three")))
    )
    val list = Value.List(Vector(Value.Int(1), Value.Int(2), Value.Int(3)))

    assertEquals(result(Builtins.first(table, Some(2L))), Value.Table(Vector("name"), table.rows.take(2)))
    assertEquals(result(Builtins.last(table, Some(2L))), Value.Table(Vector("name"), table.rows.takeRight(2)))
    assertEquals(result(Builtins.first(list, Some(2L))), Value.List(Vector(Value.Int(1), Value.Int(2))))
    assertEquals(result(Builtins.last(list, Some(2L))), Value.List(Vector(Value.Int(2), Value.Int(3))))
    assertEquals(result(Builtins.first(table, Some(0L))), Value.Table(Vector("name"), Vector.empty))
    assertEquals(result(Builtins.last(table, Some(0L))), Value.Table(Vector("name"), Vector.empty))
    assertEquals(result(Builtins.first(list, Some(0L))), Value.List(Vector.empty))
    assertEquals(result(Builtins.last(list, Some(0L))), Value.List(Vector.empty))

  test("first and last reject empty input when no count is supplied"):
    Builtins.first(Value.Table(Vector("name"), Vector.empty), None) match
      case Left(ShellError.BadArg("first", _)) => ()
      case other                               => fail(s"expected empty first to fail, got $other")
    Builtins.last(Value.List(Vector.empty), None) match
      case Left(ShellError.BadArg("last", _)) => ()
      case other                              => fail(s"expected empty last to fail, got $other")

  test("length counts collection elements, Unicode code points, and null"):
    assertEquals(result(Builtins.length(Value.Table(Vector("x"), Vector(Vector(Value.Int(1)), Vector(Value.Int(2)))))), Value.Int(2))
    assertEquals(result(Builtins.length(Value.List(Vector(Value.Bool(true), Value.Bool(false))))), Value.Int(2))
    assertEquals(result(Builtins.length(Value.Str("🐈é"))), Value.Int(2))
    assertEquals(result(Builtins.length(Value.Null)), Value.Int(0))
    assertEquals(
      Builtins.length(Value.Bool(true)).left.map(_.message),
      Left("length: expected table, list, string, or null, got bool")
    )

  test("get returns a table column as a list and a record field as a scalar"):
    val table  = Value.Table(Vector("name", "size"), Vector(Vector(Value.Str("tabby"), Value.Int(4))))
    val record = Value.Record(Vector("name" -> Value.Str("tabby"), "size" -> Value.Int(4)))

    assertEquals(result(Builtins.get(table, "size")), Value.List(Vector(Value.Int(4))))
    assertEquals(result(Builtins.get(record, "name")), Value.Str("tabby"))
    assertEquals(Builtins.get(table, "missing").left.map(_.message), Left("get: column not found: missing"))
    assertEquals(Builtins.get(record, "missing").left.map(_.message), Left("get: column not found: missing"))
    assertEquals(
      Builtins.get(Value.Int(4), "size").left.map(_.message),
      Left("get: expected table or record, got int")
    )

  test("to produces canonical JSON and RFC 4180 CSV"):
    val table = Value.Table(
      Vector("name", "note"),
      Vector(Vector(Value.Str("tabby"), Value.Str("a,b \"quoted\"")))
    )

    assertEquals(
      result(Builtins.to(table, "json")),
      Value.Str(
        "[\n" +
          "  {\n" +
          "    \"name\": \"tabby\",\n" +
          "    \"note\": \"a,b \\\"quoted\\\"\"\n" +
          "  }\n" +
          "]\n"
      )
    )
    assertEquals(result(Builtins.to(table, "csv")), Value.Str("name,note\ntabby,\"a,b \"\"quoted\"\"\"\n"))
    assertEquals(
      Builtins.to(Value.Int(1), "csv").left.map(_.message),
      Left("to: expected table, got int")
    )
    assertEquals(Builtins.to(table, "yaml").left.map(_.message), Left("to: unsupported format: yaml"))

  test("save follows JSON, table CSV, raw-string, then plain-rendering precedence"):
    withTempDirectory: directory =>
      val shellState = state(directory)
      val jsonPath   = directory.resolve("value.json")
      val csvPath    = directory.resolve("table.csv")
      val rawCsvPath = directory.resolve("raw.csv")
      val textPath   = directory.resolve("rendered.txt")
      val table      = Value.Table(Vector("name", "count"), Vector(Vector(Value.Str("tabby"), Value.Int(2))))

      assertEquals(result(Builtins.save(Value.Str("raw bytes"), "value.json", shellState)), ExecutionResult(Value.Null, shellState))
      assertEquals(result(Builtins.save(table, "table.csv", shellState)), ExecutionResult(Value.Null, shellState))
      assertEquals(result(Builtins.save(Value.Str("raw bytes"), "raw.csv", shellState)), ExecutionResult(Value.Null, shellState))
      assertEquals(result(Builtins.save(Value.Bool(true), "rendered.txt", shellState)), ExecutionResult(Value.Null, shellState))

      assertEquals(Files.readString(jsonPath), "\"raw bytes\"\n")
      assertEquals(Files.readString(csvPath), "name,count\ntabby,2\n")
      assertEquals(Files.readString(rawCsvPath), "raw bytes")
      assertEquals(Files.readString(textPath), "true\n")
