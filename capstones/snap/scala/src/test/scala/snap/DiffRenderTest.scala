package snap

import scala.collection.immutable.SortedMap

/** SPEC §7.6 byte-exact examples kept small enough to diagnose without the process harness. */
class DiffRenderTest extends munit.FunSuite:

  private def path(value: String): TrackedPath          = TrackedPath(value)
  private def bytes(value: String): FileBytes           = FileBytes.utf8(value)
  private def tree(entries: (String, FileBytes)*): Tree =
    SortedMap.from(entries.map((name, content) => path(name) -> content))

  test("renders repeated-line edits and non-final newline markers with canonical operations"):
    val before = tree("repeated.txt" -> bytes("a\nb\na\n"))
    val after  = tree(
      "added.txt"    -> bytes("new"),
      "repeated.txt" -> bytes("b\na\na")
    )

    assertEquals(
      DiffRender.render(before, after),
      "--- /dev/null\n+++ b/added.txt\n@@ -1,0 +1,1 @@\n+new\n\\ No newline at end of file\n" +
        "--- a/repeated.txt\n+++ b/repeated.txt\n@@ -1,3 +1,3 @@\n-a\n b\n a\n+a\n\\ No newline at end of file\n"
    )

  test("binary additions and empty text additions have their distinct one-block forms"):
    val after = tree(
      "data.bin" -> FileBytes(Array[Byte](0, -1, -128)),
      "empty"    -> FileBytes.empty
    )

    assertEquals(
      DiffRender.render(Tree.empty, after),
      "Binary files /dev/null and b/data.bin differ\n--- /dev/null\n+++ b/empty\n@@ -1,0 +1,0 @@\n"
    )

  test("equal trees have no output"):
    val value = tree("same" -> bytes("same\n"))
    assertEquals(DiffRender.render(value, value), "")
