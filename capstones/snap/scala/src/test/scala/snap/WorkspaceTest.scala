package snap

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor}
import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

/** SPEC §2/§10 examples and the `scan ∘ install == id` property.
  *
  * Every case receives a fresh directory. In particular, none relies on, reads, or mutates the test process's working directory.
  */
class WorkspaceTest extends munit.ScalaCheckSuite:

  private def path(text: String): TrackedPath = TrackedPath(text)
  private def bytes(text: String): FileBytes  = FileBytes.utf8(text)

  private def withTemporaryDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("snap-workspace-")
    try test(directory)
    finally deleteTree(directory)

  private def write(root: Path, relative: String, text: String): Unit =
    val file = root.resolve(relative)
    Option(file.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))

  private def list(directory: Path): Vector[Path] =
    val stream = Files.newDirectoryStream(directory)
    try stream.iterator.asScala.toVector
    finally stream.close()

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

  test("discover finds a repository from a nested subdirectory"):
    withTemporaryDirectory: root =>
      Files.createDirectory(root.resolve(".snap"))
      val nested = root.resolve("one").resolve("two")
      Files.createDirectories(nested)
      assertEquals(Workspace.discover(nested), Right(root))

  test("discover fails when no ancestor is a repository"):
    withTemporaryDirectory: root =>
      assertEquals(Workspace.discover(root), Left(SnapError("not a Snap repository")))

  test("scan excludes the control directory and reads exact arbitrary bytes"):
    withTemporaryDirectory: root =>
      Files.createDirectories(root.resolve(".snap"))
      write(root, ".snap/repository.json", "control")
      write(root, "nested/hello.txt", "hello\n")
      Files.write(root.resolve("binary"), Array[Byte](0, 7, -1))

      assertEquals(
        Workspace.scan(root),
        Right(
          SortedMap(
            path("binary")           -> FileBytes(Array[Byte](0, 7, -1)),
            path("nested/hello.txt") -> bytes("hello\n")
          )
        )
      )

  test("scan rejects a symlink by its tracked path without following it"):
    withTemporaryDirectory: root =>
      Files.createSymbolicLink(root.resolve("link"), Path.of("missing"))
      assertEquals(Workspace.scan(root), Left(SnapError("unsupported working tree entry: link")))

  test("scan rejects a FIFO by its tracked path"):
    withTemporaryDirectory: root =>
      val pipe    = root.resolve("pipe")
      val process = new ProcessBuilder("mkfifo", pipe.toString).inheritIO().start()
      assertEquals(process.waitFor(), 0)
      assertEquals(Workspace.scan(root), Left(SnapError("unsupported working tree entry: pipe")))

  test("install replaces a blocking file with the required directory and file"):
    withTemporaryDirectory: root =>
      write(root, "a", "blocker")
      val target: Tree = SortedMap(path("a/b") -> bytes("contents"))

      assertEquals(Workspace.install(root, target), Right(()))
      assert(Files.isDirectory(root.resolve("a"), LinkOption.NOFOLLOW_LINKS))
      assertEquals(Files.readString(root.resolve("a/b"), StandardCharsets.UTF_8), "contents")
      assertEquals(Workspace.scan(root), Right(target))

  test("install removes obsolete files and their newly empty directories"):
    withTemporaryDirectory: root =>
      write(root, "obsolete/only.txt", "remove")

      assertEquals(Workspace.install(root, Tree.empty), Right(()))
      assert(!Files.exists(root.resolve("obsolete"), LinkOption.NOFOLLOW_LINKS))
      assertEquals(Workspace.scan(root), Right(Tree.empty))

  test("install leaves the control directory untouched"):
    withTemporaryDirectory: root =>
      Files.createDirectories(root.resolve(".snap"))
      write(root, ".snap/repository.json", "metadata")
      val target: Tree = SortedMap(path("tracked") -> bytes("file"))

      assertEquals(Workspace.install(root, target), Right(()))
      assertEquals(Files.readString(root.resolve(".snap/repository.json"), StandardCharsets.UTF_8), "metadata")
      assertEquals(Workspace.scan(root), Right(target))

  test("writeRepository atomically replaces metadata and leaves no temporary file"):
    withTemporaryDirectory: root =>
      val metadata = root.resolve(".snap")
      Files.createDirectory(metadata)
      write(root, ".snap/repository.json", "old")

      assertEquals(Workspace.writeRepository(root, Repository.empty), Right(()))
      assertEquals(RepositoryJson.parse(Files.readString(metadata.resolve("repository.json"), StandardCharsets.UTF_8)), Right(Repository.empty))
      assertEquals(list(metadata).map(_.getFileName.toString), Vector("repository.json"))

  // ---- §2 round-trip property -------------------------------------------------

  /** Each generated file has a distinct top-level segment, so the set is prefix-free by construction. */
  private val tree: Gen[Tree] =
    for
      count   <- Gen.choose(0, 6)
      names   <- Gen.listOfN(count, Gen.choose(0, 10000)).map(_.distinct)
      content <- Gen.listOfN(names.length, Gen.choose(0, 12).flatMap(n => Gen.listOfN(n, Gen.choose(-128, 127).map(_.toByte))))
    yield SortedMap.from(
      names
        .zip(content)
        .map: (name, value) =>
          val suffix = if name % 2 == 0 then s"root-$name/file" else s"root-$name"
          path(suffix) -> FileBytes(value.toArray)
    )

  property("scan after install reproduces every generated prefix-free tree"):
    Prop.forAll(tree): target =>
      withTemporaryDirectory: root =>
        Workspace.install(root, target) == Right(()) && Workspace.scan(root) == Right(target)
