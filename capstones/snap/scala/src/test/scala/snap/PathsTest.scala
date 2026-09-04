package snap

import scala.collection.immutable.SortedMap

/** SPEC §2 examples — the specific cases a reader will look up.
  *
  * Drawn from the `tracking/plan.md` Phase 2 list, which is itself drawn from what `../tests/` and §6.2 need: `.snap` exclusion, the four ways a segment can be
  * malformed, and the `a/bc` case that a plain `startsWith` would get wrong.
  */
class PathsTest extends munit.FunSuite:

  private def path(text: String): TrackedPath =
    Paths.trackedPath(text).fold(error => fail(s"expected a valid path: ${error.detail}"), identity)

  private def tree(paths: TrackedPath*): Tree =
    SortedMap.from(paths.map(_ -> FileBytes.empty))

  // ---- §2 tracked-path grammar ----------------------------------------------

  test("a/b is a valid tracked path"):
    assert(Paths.trackedPath("a/b").isRight)

  test(".snap and any path beneath it are rejected"):
    assert(Paths.trackedPath(".snap").isLeft)
    assert(Paths.trackedPath(".snap/x").isLeft)

  test("an empty segment from a doubled slash is rejected"):
    assert(Paths.trackedPath("a//b").isLeft)

  test("a . segment is rejected"):
    assert(Paths.trackedPath("a/./b").isLeft)

  test(".. segment is rejected"):
    assert(Paths.trackedPath("a/../b").isLeft)

  test("a backslash is rejected"):
    assert(Paths.trackedPath("a\\b").isLeft)

  test("the empty string is rejected"):
    assert(Paths.trackedPath("").isLeft)

  test("a control character is rejected"):
    assert(Paths.trackedPath("ab").isLeft)

  test("a leading or trailing slash is rejected as an empty segment"):
    assert(Paths.trackedPath("/a").isLeft)
    assert(Paths.trackedPath("a/").isLeft)

  test("a path may contain non-ASCII bytes without normalization"):
    assertEquals(Paths.trackedPath("é/😀").map(_.value), Right("é/😀"))

  test("an invalid path reports a nonempty detail"):
    assertEquals(Paths.trackedPath(""), Left(SnapError("invalid path: (empty)")))
    assert(Paths.trackedPath("a//b").left.exists(_.detail.matches("invalid path: .+")))

  // ---- isPrefixOf is segment-wise, not a byte prefix ------------------------

  test("a/bc is not a descendant of a/b"):
    assert(!Paths.isPrefixOf(path("a/b"), path("a/bc")))

  test("a is an ancestor of a/b but not of ab"):
    assert(Paths.isPrefixOf(path("a"), path("a/b")))
    assert(!Paths.isPrefixOf(path("a"), path("ab")))

  test("a is not an ancestor of itself"):
    assert(!Paths.isPrefixOf(path("a"), path("a")))

  test("a/b is not an ancestor of its own ancestor a"):
    assert(!Paths.isPrefixOf(path("a/b"), path("a")))

  test("isPrefixOf holds transitively through an intermediate ancestor"):
    assert(Paths.isPrefixOf(path("a"), path("a/b/c")))

  // ---- prefixFree ------------------------------------------------------------

  test("a prefix-free set is accepted"):
    assert(Paths.prefixFree(Set(path("a/b"), path("a/c"), path("x"))).isRight)

  test("a file and a path beneath it are not prefix-free"):
    assert(Paths.prefixFree(Set(path("a"), path("a/b"))).isLeft)

  test("prefix conflicts are found even when unrelated paths sort between them"):
    // "a!" sorts between "a" and "a/b" under unsigned UTF-8 byte order
    // ('!' = 0x21 < '/' = 0x2F), so a naive adjacent-pairs-in-sorted-order check
    // would miss the a / a/b conflict here.
    assert(Paths.prefixFree(Set(path("a"), path("a!"), path("a/b"))).isLeft)

  test("the empty set and a singleton are prefix-free"):
    assert(Paths.prefixFree(Set.empty).isRight)
    assert(Paths.prefixFree(Set(path("a"))).isRight)

  // ---- the ancestor/descendant queries §6.2 needs ----------------------------

  test("ancestor finds the one existing file that contains the candidate"):
    val current = tree(path("a"), path("x/y"))
    assertEquals(Paths.ancestor(path("a/b"), current), Some(path("a")))
    assertEquals(Paths.ancestor(path("x/y/z"), current), Some(path("x/y")))
    assertEquals(Paths.ancestor(path("q"), current), None)

  test("ancestor does not match the candidate itself or a byte-prefix sibling"):
    val current = tree(path("a"), path("a-sibling"))
    assertEquals(Paths.ancestor(path("a"), current), None)
    assertEquals(Paths.ancestor(path("a-sibling"), current), None, "a is a byte prefix but not a segment ancestor")

  test("descendants finds every existing path beneath the candidate"):
    val current = tree(path("a/b"), path("a/c/d"), path("x"))
    assertEquals(Paths.descendants(path("a"), current), Set(path("a/b"), path("a/c/d")))
    assertEquals(Paths.descendants(path("x"), current), Set.empty)

  test("descendants does not match the candidate itself"):
    val current = tree(path("a"))
    assertEquals(Paths.descendants(path("a"), current), Set.empty)
