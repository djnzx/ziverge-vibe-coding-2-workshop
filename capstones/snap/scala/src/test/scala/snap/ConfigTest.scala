package snap

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor}

/** SPEC §8 / §7.2 examples. Every filesystem path, including the global-home location, is a fresh test directory injected into [[Config]]. */
class ConfigTest extends munit.FunSuite:

  private def id(text: String): ContributorId = ContributorId(text)

  private def withTemporaryDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("snap-config-")
    try test(directory)
    finally deleteTree(directory)

  private def write(path: Path, contents: String): Unit =
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.writeString(path, contents, StandardCharsets.UTF_8)

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

  test("a local contributor ID wins without reading malformed global configuration"):
    withTemporaryDirectory: root =>
      val repository = root.resolve("repository")
      val home       = root.resolve("home")
      write(repository.resolve(".snap/config.json"), """{"contributor":{"id":"local@x"}}""")
      write(home.resolve(".snapconfig.json"), "not JSON")

      assertEquals(Config.resolve(repository, Some(home)), Right(Configuration(Some(id("local@x")))))

  test("a local configuration without an ID falls through to global configuration"):
    withTemporaryDirectory: root =>
      val repository = root.resolve("repository")
      val home       = root.resolve("home")
      write(repository.resolve(".snap/config.json"), """{"contributor":{}}""")
      write(home.resolve(".snapconfig.json"), """{"contributor":{"id":"global@x"}}""")

      assertEquals(Config.resolve(repository, Some(home)), Right(Configuration(Some(id("global@x")))))

  test("a malformed local configuration fails even when global configuration is valid"):
    withTemporaryDirectory: root =>
      val repository = root.resolve("repository")
      val home       = root.resolve("home")
      write(repository.resolve(".snap/config.json"), "not JSON")
      write(home.resolve(".snapconfig.json"), """{"contributor":{"id":"global@x"}}""")

      assert(Config.resolve(repository, Some(home)).left.exists(_.detail.contains("invalid JSON")))

  test("a missing injected HOME yields no contributor when local configuration has none"):
    withTemporaryDirectory: repository =>
      assertEquals(Config.resolve(repository, None), Right(Configuration.empty))

  test("unknown fields and duplicates in a read file are rejected"):
    withTemporaryDirectory: root =>
      val repository = root.resolve("repository")
      write(repository.resolve(".snap/config.json"), """{"contributor":{"id":"a@x","id":"b@x"}}""")
      assertEquals(Config.resolve(repository, None), Left(SnapError("duplicate JSON key: id")))

      write(repository.resolve(".snap/config.json"), """{"contributor":{},"contribut\u006fr":{}}""")
      assertEquals(Config.resolve(repository, None), Left(SnapError("duplicate JSON key: contributor")))

      write(repository.resolve(".snap/config.json"), """{"contributor":{"id":"a@x","extra":true}}""")
      assertEquals(Config.resolve(repository, None), Left(SnapError("contributor has unknown field: extra")))

  test("an invalid contributor ID in a read local configuration blocks global fallback"):
    withTemporaryDirectory: root =>
      val repository = root.resolve("repository")
      val home       = root.resolve("home")
      write(repository.resolve(".snap/config.json"), """{"contributor":{"id":"not-an-id"}}""")
      write(home.resolve(".snapconfig.json"), """{"contributor":{"id":"global@x"}}""")

      assertEquals(Config.resolve(repository, Some(home)), Left(SnapError("invalid contributor id: not-an-id")))

  test("writing replaces unknown local fields with the exact contributor configuration"):
    withTemporaryDirectory: repository =>
      write(repository.resolve(".snap/config.json"), """{"unknown":true}""")

      assertEquals(Config.writeLocal(repository, id("new@x")), Right(()))
      assertEquals(Config.read(repository.resolve(".snap/config.json")), Right(Configuration(Some(id("new@x")))))
      assertEquals(Files.readString(repository.resolve(".snap/config.json"), StandardCharsets.UTF_8), "{\"contributor\":{\"id\":\"new@x\"}}\n")

  test("writing global configuration uses only the injected home directory"):
    withTemporaryDirectory: home =>
      assertEquals(Config.writeGlobal(home, id("global@x")), Right(()))
      assertEquals(Config.read(home.resolve(".snapconfig.json")), Right(Configuration(Some(id("global@x")))))

  test("writing validates a forged contributor ID before it can mutate configuration"):
    withTemporaryDirectory: repository =>
      assertEquals(Config.writeLocal(repository, ContributorId("not-an-id")), Left(SnapError("invalid contributor id: not-an-id")))
      assert(!Files.exists(repository.resolve(".snap/config.json"), LinkOption.NOFOLLOW_LINKS))

  test("the authoring commands receive the exact missing-contributor error"):
    assertEquals(
      Config.requireContributorId(Configuration.empty),
      Left(SnapError("contributor.id is required; configure it locally or globally"))
    )
