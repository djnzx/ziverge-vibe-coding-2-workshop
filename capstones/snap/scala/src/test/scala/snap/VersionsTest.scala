package snap

import scala.collection.immutable.SortedMap

/** SPEC §3 examples — the specific cases a reader will look up.
  *
  * The rejection cases are drawn from `../tests/19-version-boundaries.yaml` and `../tests/25-config-version-path-boundaries.yaml`, which pin them through the
  * CLI; pinning them here too means a failure names the rule rather than the command.
  */
class VersionsTest extends munit.FunSuite:

  private def id(text: String): ContributorId =
    Versions.contributorId(text).fold(error => fail(s"expected a valid id: ${error.detail}"), identity)

  private def version(pairs: (String, Long)*): Version =
    Version(SortedMap.from(pairs.map((text, revision) => id(text) -> revision)))

  // ---- §3.1 contributor IDs ------------------------------------------------

  test("a contributor id needs exactly one @ with text on both sides"):
    assert(Versions.contributorId("a@x").isRight)
    assert(Versions.contributorId("jdegoes@example.com").isRight)
    assert(Versions.contributorId("not-an-id").isLeft, "no @")
    assert(Versions.contributorId("bad-id").isLeft, "no @")
    assert(Versions.contributorId("two@@x").isLeft, "two @")
    assert(Versions.contributorId("a@b@c").isLeft, "two @")
    assert(Versions.contributorId("@x").isLeft, "empty local part")
    assert(Versions.contributorId("a@").isLeft, "empty domain")

  test("a contributor id excludes whitespace, control characters, comma, parens, and ->"):
    assert(Versions.contributorId("space @x").isLeft)
    assert(Versions.contributorId("a\tb@x").isLeft)
    assert(Versions.contributorId("a\nb@x").isLeft)
    assert(Versions.contributorId("a\u0001b@x").isLeft, "control character")
    assert(Versions.contributorId("a,b@x").isLeft)
    assert(Versions.contributorId("a(b)@x").isLeft)
    assert(Versions.contributorId("a)b@x").isLeft)
    assert(Versions.contributorId("a->b@x").isLeft)

  test("a lone - or > is allowed; only the -> pair is forbidden"):
    assert(Versions.contributorId("a-b@x").isRight)
    assert(Versions.contributorId("a>b@x").isRight)
    assert(Versions.contributorId("a>-b@x").isRight)

  test("§3.1 calls the id ASCII, so a non-ASCII character is rejected"):
    assert(Versions.contributorId("é@x").isLeft)
    assert(Versions.contributorId("a@é").isLeft)

  test("a contributor id is at most 254 bytes"):
    val local = "a" * 252
    assertEquals(s"$local@x".length, 254)
    assert(Versions.contributorId(s"$local@x").isRight, "254 bytes is allowed")
    assert(Versions.contributorId(s"${local}b@x").isLeft, "255 bytes is not")

  test("a contributor id keeps its spelling exactly"):
    assertEquals(Versions.contributorId("MiXeD@Case.COM").map(_.value), Right("MiXeD@Case.COM"))

  test("an invalid contributor id reports a nonempty detail"):
    assertEquals(Versions.contributorId("bad-id"), Left(SnapError("invalid contributor id: bad-id")))
    assertEquals(Versions.contributorId(""), Left(SnapError("invalid contributor id: (empty)")))

  // ---- §3.2 canonical syntax -----------------------------------------------

  test("the empty version is ()"):
    assertEquals(Versions.parse("()"), Right(Version.empty))
    assertEquals(Versions.render(Version.empty), "()")

  test("the §3.2 example round-trips"):
    val text     = "(jdegoes@example.com->2323,vigoo@example.com->239)"
    val expected = version("jdegoes@example.com" -> 2323L, "vigoo@example.com" -> 239L)
    assertEquals(Versions.parse(text), Right(expected))
    assertEquals(Versions.render(expected), text)

  test("render sorts contributors by unsigned UTF-8 bytes and emits no spaces"):
    assertEquals(Versions.render(version("b@x" -> 1L, "a@x" -> 2L)), "(a@x->2,b@x->1)")

  test("the maximum safe revision parses"):
    assertEquals(
      Versions.parse("(a@x->9007199254740991)"),
      Right(version("a@x" -> 9007199254740991L))
    )

  test("an explicit zero is an error, because zero means no revision"):
    assert(Versions.parse("(good@x->0)").isLeft)

  test("a negative revision is an error"):
    assert(Versions.parse("(good@x->-1)").isLeft)

  test("a revision past the maximum safe integer is an error, not a wrap"):
    assert(Versions.parse("(good@x->9007199254740992)").isLeft)
    assert(Versions.parse("(good@x->99999999999999999999999999)").isLeft, "far past Long range")

  test("a leading zero is an error"):
    assert(Versions.parse("(a@x->01)").isLeft)

  test("a duplicate contributor is an error"):
    assert(Versions.parse("(a@x->1,a@x->2)").isLeft)

  test("noncanonical contributor ordering is an error"):
    assert(Versions.parse("(b@x->1,a@x->1)").isLeft)

  test("whitespace anywhere is an error"):
    assert(Versions.parse("(a@x->1, b@x->1)").isLeft)
    assert(Versions.parse(" (a@x->1)").isLeft)
    assert(Versions.parse("(a@x->1) ").isLeft)
    assert(Versions.parse("( )").isLeft)

  test("malformed bracketing and separators are errors"):
    assert(Versions.parse("").isLeft)
    assert(Versions.parse("(").isLeft)
    assert(Versions.parse(")").isLeft)
    assert(Versions.parse("a@x->1").isLeft, "no parentheses")
    assert(Versions.parse("(a@x->1)extra").isLeft)
    assert(Versions.parse("(a@x->1,)").isLeft, "trailing comma")
    assert(Versions.parse("(,a@x->1)").isLeft, "leading comma")
    assert(Versions.parse("(a@x->1,,b@x->1)").isLeft, "doubled comma")
    assert(Versions.parse("(a@x->)").isLeft, "missing revision")
    assert(Versions.parse("(->1)").isLeft, "missing contributor")
    assert(Versions.parse("(a@x1)").isLeft, "missing arrow")

  test("a nonnumeric or signed revision is an error"):
    assert(Versions.parse("(a@x->+1)").isLeft)
    assert(Versions.parse("(a@x->1.0)").isLeft)
    assert(Versions.parse("(a@x->one)").isLeft)
    assert(Versions.parse("(a@x->1e3)").isLeft)

  test("an invalid version reports a nonempty detail"):
    assertEquals(Versions.parse("(a@x->0)"), Left(SnapError("invalid version: (a@x->0)")))
    assertEquals(Versions.parse(""), Left(SnapError("invalid version: (empty)")))

  // ---- §3.3 causal comparison and join -------------------------------------

  test("equal versions compare Equal"):
    assertEquals(Versions.compare(Version.empty, Version.empty), Causality.Equal)
    assertEquals(Versions.compare(version("a@x" -> 1L), version("a@x" -> 1L)), Causality.Equal)

  test("an absent component counts as zero, so () is before any nonempty version"):
    assertEquals(Versions.compare(Version.empty, version("a@x" -> 1L)), Causality.Before)
    assertEquals(Versions.compare(version("a@x" -> 1L), Version.empty), Causality.After)

  test("before requires every component <= and at least one strictly <"):
    assertEquals(Versions.compare(version("a@x" -> 1L), version("a@x" -> 2L)), Causality.Before)
    assertEquals(
      Versions.compare(version("a@x" -> 1L), version("a@x" -> 1L, "b@x" -> 1L)),
      Causality.Before
    )

  test("concurrent is a distinct outcome, never folded into before or after"):
    assertEquals(
      Versions.compare(version("a@x" -> 2L), version("b@x" -> 1L)),
      Causality.Concurrent
    )
    assertEquals(
      Versions.compare(version("a@x" -> 2L, "b@x" -> 1L), version("a@x" -> 1L, "b@x" -> 2L)),
      Causality.Concurrent
    )

  test("join takes the componentwise maximum"):
    assertEquals(
      Versions.join(version("a@x" -> 2L, "b@x" -> 1L), version("a@x" -> 1L, "b@x" -> 2L)),
      version("a@x" -> 2L, "b@x" -> 2L)
    )
    assertEquals(Versions.join(Version.empty, version("a@x" -> 3L)), version("a@x" -> 3L))

  test("21-version-algebra's frontiers join as the suite expects"):
    // (a@x->2) merged with (a@x->1,b@x->2) gives (a@x->2,b@x->2), both directions.
    val fromA  = version("a@x" -> 2L)
    val fromB  = version("a@x" -> 1L, "b@x" -> 2L)
    val joined = version("a@x" -> 2L, "b@x" -> 2L)
    assertEquals(Versions.join(fromA, fromB), joined)
    assertEquals(Versions.join(fromB, fromA), joined)
    assertEquals(Versions.render(joined), "(a@x->2,b@x->2)")

  // ---- §3.4 Snap order -----------------------------------------------------

  test("Snap order compares the counter at each id of the sorted union"):
    // Union is [a@x, b@x]. (a@x->1) reads as [1,0]; (a@x->1,b@x->1) reads as [1,1].
    assert(Versions.snapOrder.lt(version("a@x" -> 1L), version("a@x" -> 1L, "b@x" -> 1L)))
    // [2,0] beats [1,9] at the first key.
    assert(Versions.snapOrder.gt(version("a@x" -> 2L), version("a@x" -> 1L, "b@x" -> 9L)))

  test("Snap order reads the union in ascending id order, not descending"):
    // Disjoint contributors, so the answer is decided entirely by which end of
    // the union is read first: ascending starts at a@x and answers "less"
    // (0 < 2); descending starts at b@x and answers "greater" (1 > 0).
    val left  = version("b@x" -> 1L)
    val right = version("a@x" -> 2L)
    assert(Versions.snapOrder.lt(left, right))
    assert(Versions.snapOrder.gt(right, left))

  test("Snap order totally orders concurrent versions"):
    val left  = version("a@x" -> 2L)
    val right = version("b@x" -> 1L)
    assertEquals(Versions.compare(left, right), Causality.Concurrent)
    assertNotEquals(Versions.snapOrder.compare(left, right), 0)
