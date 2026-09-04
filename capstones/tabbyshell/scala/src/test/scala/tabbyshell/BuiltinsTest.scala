package tabbyshell

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class BuiltinsTest extends munit.FunSuite:

  private val now = 1725408000L

  private def state(cwd: Path, home: Option[Path] = None, prevCwd: Option[Path] = None): ShellState =
    ShellState(cwd.toString, prevCwd.map(_.toString), home.getOrElse(cwd).toString, now, color = false)

  private def result[A](either: Either[ShellError, A]): A =
    either.fold(error => fail(error.message), identity)

  private def withTempDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("tabbyshell-builtins-")
    try test(directory)
    finally
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.sortBy(path => -path.getNameCount).foreach(Files.deleteIfExists)
      finally paths.close()

  test("pwd returns the absolute cwd held in ShellState"):
    withTempDirectory: directory =>
      assertEquals(Builtins.pwd(state(directory)), ExecutionResult(Value.Str(directory.toString), state(directory)))

  test("cat reads a relative path from the shell cwd"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("notes.txt"), "hello tabby\n")

      assertEquals(
        result(Builtins.cat("notes.txt", state(directory))).value,
        Value.Str("hello tabby\n")
      )

  test("file commands expand only a leading home marker"):
    withTempDirectory: directory =>
      val home = Files.createDirectory(directory.resolve("home"))
      Files.writeString(home.resolve("notes.txt"), "from home")

      assertEquals(
        result(Builtins.cat("~/notes.txt", state(directory, Some(home)))).value,
        Value.Str("from home")
      )

  test("open selects JSON, CSV, or raw text by the path extension"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("items.json"), "[{\"name\":\"tabby\"}]")
      Files.writeString(directory.resolve("items.csv"), "name,count\ntabby,2\n")
      Files.writeString(directory.resolve("notes.txt"), "raw text")

      assertEquals(
        result(Builtins.open("items.json", state(directory))).value,
        Value.Table(Vector("name"), Vector(Vector(Value.Str("tabby"))))
      )
      assertEquals(
        result(Builtins.open("items.csv", state(directory))).value,
        Value.Table(Vector("name", "count"), Vector(Vector(Value.Str("tabby"), Value.Str("2"))))
      )
      assertEquals(result(Builtins.open("notes.txt", state(directory))).value, Value.Str("raw text"))

  test("ls omits dotfiles, sorts names, and produces typed metadata"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("zeta.txt"), "z")
      Files.writeString(directory.resolve(".hidden"), "hidden")
      Files.createDirectory(directory.resolve("alpha"))

      result(Builtins.ls(None, includeAll = false, long = false, state(directory))).value match
        case Value.Table(columns, rows) =>
          assertEquals(columns, Vector("name", "type", "size", "modified"))
          assertEquals(rows.map(_(0)), Vector(Value.Str("alpha"), Value.Str("zeta.txt")))
          assertEquals(rows.map(_(1)), Vector(Value.Str("dir"), Value.Str("file")))
          assertEquals(rows.map(_(2)), Vector(Value.Filesize(0), Value.Filesize(1)))
          assert(rows.forall: row =>
            row(3) match
              case Value.Date(_) => true
              case _             => false)
        case other => fail(s"expected table, got ${other.typeName}")

  test("ls all and long include dotfiles plus mode and uid columns"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve(".hidden"), "x")

      result(Builtins.ls(None, includeAll = true, long = true, state(directory))).value match
        case Value.Table(columns, rows) =>
          assertEquals(columns, Vector("name", "type", "size", "modified", "mode", "uid"))
          assertEquals(rows.map(_(0)), Vector(Value.Str(".hidden")))
          assertEquals(rows.head(4).typeName, "string")
          assertEquals(rows.head(5).typeName, "int")
        case other => fail(s"expected table, got ${other.typeName}")

  test("ls classifies symlinks and maps missing directories to IoError"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("target.txt"), "target")
      Files.createSymbolicLink(directory.resolve("target-link"), Path.of("target.txt"))

      result(Builtins.ls(None, includeAll = false, long = false, state(directory))).value match
        case Value.Table(_, rows) =>
          assertEquals(rows.map(_(1)), Vector(Value.Str("symlink"), Value.Str("file")))
        case other => fail(s"expected table, got ${other.typeName}")
      assert(
        Builtins
          .ls(Some("missing"), includeAll = false, long = false, state(directory))
          .left
          .exists:
            case ShellError.IoError("ls", _) => true
            case _                           => false
      )

  test("cd resolves relative paths and records the previous cwd"):
    withTempDirectory: directory =>
      val subdirectory = Files.createDirectory(directory.resolve("subdir"))

      assertEquals(
        result(Builtins.cd(CdTarget.Path("subdir"), state(directory))),
        ExecutionResult(Value.Null, state(subdirectory, Some(directory), prevCwd = Some(directory)))
      )

  test("cd with no path selects the stored home directory"):
    withTempDirectory: directory =>
      val home = Files.createDirectory(directory.resolve("home"))

      assertEquals(
        result(Builtins.cd(CdTarget.Home, state(directory, Some(home)))),
        ExecutionResult(Value.Null, state(home, Some(home), prevCwd = Some(directory)))
      )

  test("cd dash requires history and then uses the previous cwd"):
    withTempDirectory: directory =>
      val subdirectory = Files.createDirectory(directory.resolve("subdir"))

      assertEquals(
        Builtins.cd(CdTarget.Previous, state(directory)).left.map(_.message),
        Left("cd: no previous directory")
      )
      assertEquals(
        result(Builtins.cd(CdTarget.Previous, state(subdirectory, prevCwd = Some(directory)))),
        ExecutionResult(Value.Null, state(directory, Some(subdirectory), prevCwd = Some(subdirectory)))
      )

  test("cd to a regular file is a BadArg"):
    withTempDirectory: directory =>
      Files.writeString(directory.resolve("notes.txt"), "hello")

      assertEquals(
        Builtins.cd(CdTarget.Path("notes.txt"), state(directory)).left.map(_.message),
        Left("cd: not a directory: notes.txt")
      )

  test("cd reports missing paths as IoError"):
    withTempDirectory: directory =>
      assert(
        Builtins
          .cd(CdTarget.Path("missing"), state(directory))
          .left
          .exists:
            case ShellError.IoError(_, _) => true
            case _                        => false
      )
