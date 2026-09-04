package snap

import org.scalacheck.{Gen, Prop}
import scala.collection.immutable.SortedMap

/** SPEC §7.11 / §10 presentation selection and byte-exact render examples. Expectations compose [[Presentation.s]] instead of spelling ANSI escapes. */
class PresentationTest extends munit.ScalaCheckSuite:

  private val terminal = Presentation.Terminal
  private val plain    = Presentation.Plain

  private def id(value: String): ContributorId                              = ContributorId(value)
  private def path(value: String): TrackedPath                              = TrackedPath(value)
  private def version(values: (String, Long)*): Version                     = Version(SortedMap.from(values.map((name, revision) => id(name) -> revision)))
  private def patch(author: String, revision: Long, message: String): Patch =
    Patch(id(author), revision, Version.empty, message, Vector(Change.Put(path(s"$author-$revision"), FileBytes.empty)))

  private def assertSelection(snapColor: Option[String], noColor: Option[String], isTty: Boolean, expected: Presentation): Unit =
    assertEquals(Presentation.select(snapColor, noColor, isTty), Right(expected))

  // ---- selection -------------------------------------------------------------

  test("SNAP_COLOR unset and auto follow each stream's injected TTY state when NO_COLOR is absent"):
    Vector(None, Some("auto")).foreach: snapColor =>
      assertSelection(snapColor, None, isTty = true, terminal)
      assertSelection(snapColor, None, isTty = false, plain)

  test("every allowed SNAP_COLOR, NO_COLOR, and TTY combination selects the specified presentation"):
    val colors   = Vector(None, Some("auto"), Some("always"), Some("never"))
    val noColors = Vector(None, Some(""), Some("1"))
    for
      snapColor <- colors
      noColor   <- noColors
      isTty     <- Vector(true, false)
    do
      val expected = snapColor match
        case Some("always")                  => terminal
        case Some("never")                   => plain
        case _ if noColor.nonEmpty || !isTty => plain
        case _                               => terminal
      assertSelection(snapColor, noColor, isTty, expected)

  test("NO_COLOR is complete plain presentation in auto mode, including an empty value"):
    Vector(Some(""), Some("1")).foreach: noColor =>
      assertSelection(None, noColor, isTty = true, plain)
      assertSelection(Some("auto"), noColor, isTty = false, plain)

  test("always overrides NO_COLOR while never forces plain for both TTY states"):
    Vector(true, false).foreach: isTty =>
      assertSelection(Some("always"), Some("1"), isTty, terminal)
      assertSelection(Some("never"), None, isTty, plain)

  test("invalid SNAP_COLOR is the exact plain-before-dispatch error"):
    assertEquals(
      Presentation.select(Some("sometimes"), None, isTty = true),
      Left(SnapError("SNAP_COLOR must be auto, always, or never"))
    )

  test("stdout and stderr can select independently from their separate injected TTY flags"):
    val out = Presentation.select(None, None, isTty = true)
    val err = Presentation.select(None, None, isTty = false)
    assertEquals(out, Right(terminal))
    assertEquals(err, Right(plain))

  // ---- records ---------------------------------------------------------------

  test("success records use the exact terminal label, symbol, and version styling"):
    val current = version("alice@x" -> 2)
    val labels  = Vector(
      SuccessLabel.Initialized -> "Initialized repository",
      SuccessLabel.Committed   -> "Committed",
      SuccessLabel.Reverted    -> "Reverted",
      SuccessLabel.Merged      -> "Merged"
    )
    labels.foreach: (label, text) =>
      assertEquals(Presentation.success(plain, label, current), "(alice@x->2)\n")
      assertEquals(
        Presentation.success(terminal, label, current),
        Presentation.s(32, "✓") + " " + Presentation.s(1, text) + " " + Presentation.s(36, "(alice@x->2)") + "\n"
      )

  test("status renders the clean and every dirty-row layout, including U+2212 minus"):
    val current = version("alice@x" -> 1)
    assertEquals(
      Presentation.status(terminal, current, Vector.empty),
      Presentation.s(1, "Snap status") + "  " + Presentation.s(36, "(alice@x->1)") + "\n\n" +
        "  " + Presentation.s(32, "✓") + " Working tree clean\n"
    )
    val entries = Vector(
      StatusEntry(StatusCode.Added, path("added")),
      StatusEntry(StatusCode.Deleted, path("deleted")),
      StatusEntry(StatusCode.Modified, path("modified"))
    )
    assertEquals(Presentation.status(plain, current, entries), "version (alice@x->1)\nA added\nD deleted\nM modified\n")
    assertEquals(
      Presentation.status(terminal, current, entries),
      Presentation.s(1, "Snap status") + "  " + Presentation.s(36, "(alice@x->1)") + "\n\n" +
        "  " + Presentation.s(32, "+") + " added " + Presentation.s(2, "(added)") + "\n" +
        "  " + Presentation.s(31, "−") + " deleted " + Presentation.s(2, "(deleted)") + "\n" +
        "  " + Presentation.s(33, "~") + " modified " + Presentation.s(2, "(modified)") + "\n"
    )

  test("log escapes its plain message and puts one blank line between terminal entries"):
    val later   = patch("alice@x", 2, "second\\\tline\n")
    val earlier = patch("alice@x", 1, "first")
    assertEquals(Presentation.log(plain, Vector(later, earlier)), "(alice@x->2)\talice@x\tsecond\\\\\\tline\\n\n(alice@x->1)\talice@x\tfirst\n")
    assertEquals(
      Presentation.log(terminal, Vector(later, earlier)),
      Presentation.s(36, "●") + " " + Presentation.s(1, "second\\\\\\tline\\n") + "\n  " + Presentation.s(36, "(alice@x->2)") + " " + Presentation
        .s(2, "by") + " " + Presentation.s(35, "alice@x") + "\n\n" +
        Presentation.s(36, "●") + " " + Presentation.s(1, "first") + "\n  " + Presentation.s(36, "(alice@x->1)") + " " + Presentation.s(
          2,
          "by"
        ) + " " + Presentation.s(35, "alice@x") + "\n"
    )

  test("diff styles the first matching prefix while preserving unchanged lines and LF bytes"):
    val source = "--- a/f\n+++ b/f\n@@ -1,1 +1,1 @@\n context\n-old\n+new\n\\ No newline at end of file\nBinary files a/x and b/x differ\n"
    assertEquals(
      Presentation.diff(terminal, source),
      Presentation.s(1, "--- a/f") + "\n" + Presentation.s(1, "+++ b/f") + "\n" + Presentation.s(36, "@@ -1,1 +1,1 @@") + "\n" +
        " context\n" + Presentation.s(31, "-old") + "\n" + Presentation.s(32, "+new") + "\n" + Presentation.s(2, "\\ No newline at end of file") + "\n" +
        Presentation.s(33, "Binary files a/x and b/x differ") + "\n"
    )

  test("version, warnings, errors, serve URL, and configuration silence have their specified terminal behavior"):
    assertEquals(Presentation.version(plain), "snap 1.0.0\n")
    assertEquals(Presentation.version(terminal), Presentation.s(1, "snap 1.0.0") + "\n")
    assertEquals(Presentation.warning(plain, "resolved f: put-wins"), "warning: resolved f: put-wins\n")
    assertEquals(Presentation.warning(terminal, "resolved f: put-wins"), Presentation.s(33, "⚠") + " " + Presentation.s(33, "resolved f: put-wins") + "\n")
    assertEquals(Presentation.error(plain, SnapError("bad input")), "snap: bad input\n")
    assertEquals(Presentation.error(terminal, SnapError("bad input")), Presentation.s(31, "✗ snap: bad input") + "\n")
    assertEquals(Presentation.serveUrl("http://127.0.0.1:8765/repository.json"), "http://127.0.0.1:8765/repository.json\n")
    assertEquals(Presentation.silent, "")

  // ---- byte-safety properties ------------------------------------------------

  private val ordinaryText: Gen[String] =
    Gen.choose(0, 40).flatMap(size => Gen.listOfN(size, Gen.oneOf(Gen.alphaNumChar, Gen.const(' '), Gen.const('-'), Gen.const('+')))).map(_.mkString)

  property("stripping SGR restores layout-preserving terminal diff and version records"):
    Prop.forAll(ordinaryText): text =>
      val diff = s"+$text\n"
      Presentation.stripSgr(Presentation.diff(terminal, diff)) == diff &&
      Presentation.stripSgr(Presentation.version(terminal)) == Presentation.version(plain)

  property("plain presentation records never contain an escape byte"):
    Prop.forAll(ordinaryText): text =>
      val escape  = Presentation.s(0, "").take(1)
      val current = version("a@x" -> 1)
      val records = Vector(
        Presentation.success(plain, SuccessLabel.Merged, current),
        Presentation.status(plain, current, Vector(StatusEntry(StatusCode.Modified, path("f")))),
        Presentation.log(plain, Vector(patch("a@x", 1, text))),
        Presentation.diff(plain, s"+$text\n"),
        Presentation.version(plain),
        Presentation.warning(plain, text),
        Presentation.error(plain, SnapError(text)),
        Presentation.serveUrl(text),
        Presentation.silent
      )
      records.forall(!_.contains(escape))
