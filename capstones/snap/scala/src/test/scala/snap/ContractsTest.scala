package snap

import java.nio.charset.StandardCharsets

class ContractsTest extends munit.FunSuite:

  test("file bytes compare by content, not by array identity"):
    assertEquals(FileBytes(Array[Byte](1, 2, 3)), FileBytes(Array[Byte](1, 2, 3)))
    assertNotEquals(FileBytes(Array[Byte](1, 2, 3)), FileBytes(Array[Byte](1, 2)))

  test("file bytes copy on the way in and out"):
    val source = Array[Byte](1, 2, 3)
    val bytes  = FileBytes(source)
    source(0) = 9
    bytes.toArray(1) = 9
    assertEquals(bytes, FileBytes(Array[Byte](1, 2, 3)))

  test("tracked paths sort by unsigned UTF-8 bytes, not by UTF-16 code units"):
    // U+10000 encodes as F0 90 80 80; U+E000 encodes as EE 80 80. UTF-8 byte order
    // puts U+E000 first, while UTF-16 code-unit order puts the surrogate pair first.
    val supplementary = TrackedPath("\uD800\uDC00")
    val privateUse    = TrackedPath("\uE000")
    assert(supplementary.value.compareTo(privateUse.value) < 0, "the UTF-16 order this test guards against")
    assert(summon[Ordering[TrackedPath]].gt(supplementary, privateUse), "UTF-8 byte order must reverse it")

  test("a patch's result advances exactly its author's component"):
    val alice = ContributorId("alice@example.com")
    val bob   = ContributorId("bob@example.com")
    val base  = Version(scala.collection.immutable.SortedMap(alice -> 1L, bob -> 4L))
    val patch = Patch(alice, 2L, base, "second", Vector(Change.Delete(TrackedPath("gone.txt"))))
    assertEquals(patch.result.apply(alice), 2L)
    assertEquals(patch.result.apply(bob), 4L)
    assertEquals(patch.dot, Dot(alice, 2L))

  test("an absent version component reads as zero"):
    assertEquals(Version.empty.apply(ContributorId("nobody@example.com")), 0L)

  test("warnings sort by path, then reason"):
    val warnings = Vector(
      Warning(TrackedPath("b.txt"), Reason.DeleteWins),
      Warning(TrackedPath("a.txt"), Reason.PutWins),
      Warning(TrackedPath("a.txt"), Reason.DeleteWins)
    )
    assertEquals(
      warnings.sorted.map(w => s"${w.path.value}:${w.reason.token}"),
      Vector("a.txt:delete-wins", "a.txt:put-wins", "b.txt:delete-wins")
    )

  test("utf8 file bytes round-trip"):
    val text = "héllo\n"
    assertEquals(new String(FileBytes.utf8(text).toArray, StandardCharsets.UTF_8), text)
