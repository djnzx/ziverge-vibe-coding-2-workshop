package snap

import java.io.{ByteArrayOutputStream, IOException, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor}

/** Command composition examples use injected process inputs rather than spawning the assembly. */
class CliTest extends munit.FunSuite:

  private final case class Result(code: Int, stdout: String, stderr: String)

  private def withTemporaryDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("snap-cli-")
    try test(directory)
    finally deleteTree(directory)

  private def invoke(
    cwd: Path,
    home: Option[Path],
    args: Vector[String],
    snapColor: Option[String] = None,
    noColor: Option[String] = None
  ): Result =
    val stdout  = ByteArrayOutputStream()
    val stderr  = ByteArrayOutputStream()
    val streams = Streams(
      PrintStream(stdout, false, StandardCharsets.UTF_8),
      PrintStream(stderr, false, StandardCharsets.UTF_8),
      stdoutIsTty = false,
      stderrIsTty = false
    )
    val code = Cli.run(args, streams, cwd, home, snapColor, noColor)
    streams.flush()
    Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8))

  private def assertInvalid(cwd: Path, args: String*): Unit =
    assertEquals(invoke(cwd, None, args.toVector), Result(1, "", "snap: invalid command or arguments\n"))

  private def deleteTree(root: Path): Unit =
    if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
            Files.delete(file)
            FileVisitResult.CONTINUE

          override def postVisitDirectory(directory: Path, error: IOException): FileVisitResult =
            if Option(error).nonEmpty then throw error
            Files.delete(directory)
            FileVisitResult.CONTINUE
      )

  test("strict grammar gives diff its separate usage family and does not create option-looking init paths"):
    withTemporaryDirectory: root =>
      assertInvalid(root, "--version", "extra")
      assertInvalid(root, "init", "a", "b")
      assertInvalid(root, "init", "--unknown")
      assert(!Files.exists(root.resolve("--unknown"), LinkOption.NOFOLLOW_LINKS))

      assertEquals(invoke(root, None, Vector("init", "repo")), Result(0, "()\n", ""))
      val repository = root.resolve("repo")
      assertInvalid(repository, "config", "contributor.id", "a@x", "--global")
      assertInvalid(repository, "status", "extra")
      assertInvalid(repository, "commit")
      assertInvalid(repository, "revert", "()", "extra")
      assertInvalid(repository, "merge", "repo", "extra")
      assertInvalid(repository, "--serve", "0", "extra")

      Vector(
        Vector("diff", "()"),
        Vector("diff", "()", "()", "--unknown", "repo"),
        Vector("diff", "()", "()", "--repo", "repo", "--repo", "repo")
      ).foreach: args =>
        assertEquals(
          invoke(repository, None, args),
          Result(1, "", "snap: usage: snap diff [<old-version> <new-version> [--repo <repository>]]\n")
        )

  test("commit status log and revert compose through injected cwd home and streams"):
    withTemporaryDirectory: root =>
      val home = root.resolve("home")
      Files.createDirectory(home)
      assertEquals(invoke(root, Some(home), Vector("init", "repo")).code, 0)
      val repository = root.resolve("repo")
      assertEquals(invoke(repository, Some(home), Vector("config", "contributor.id", "a@x")), Result(0, "", ""))

      Files.writeString(repository.resolve("note.txt"), "one\n", StandardCharsets.UTF_8)
      assertEquals(invoke(repository, Some(home), Vector("commit", "first")), Result(0, "(a@x->1)\n", ""))
      assertEquals(invoke(repository, Some(home), Vector("status")), Result(0, "version (a@x->1)\n", ""))
      assertEquals(invoke(repository, Some(home), Vector("log")), Result(0, "(a@x->1)\ta@x\tfirst\n", ""))

      assertEquals(invoke(repository, Some(home), Vector("revert", "()")), Result(0, "(a@x->2)\n", ""))
      assert(!Files.exists(repository.resolve("note.txt"), LinkOption.NOFOLLOW_LINKS))

  test("invalid SNAP_COLOR fails in plain mode before dispatch"):
    withTemporaryDirectory: root =>
      assertEquals(
        invoke(root, None, Vector("init", "blocked"), snapColor = Some("rainbow")),
        Result(1, "", "snap: SNAP_COLOR must be auto, always, or never\n")
      )
      assert(!Files.exists(root.resolve("blocked"), LinkOption.NOFOLLOW_LINKS))
