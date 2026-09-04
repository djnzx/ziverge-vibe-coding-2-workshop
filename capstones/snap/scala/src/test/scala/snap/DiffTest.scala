package snap

/** SPEC §5 examples — the `05-diff-goldens.yaml` pair pinned as a unit golden, plus one case per shape the plan names for this phase.
  */
class DiffTest extends munit.FunSuite:

  // ---- the acceptance golden ----------------------------------------------------

  test("05-diff-goldens.yaml: \"a\\nb\\na\\n\" to \"b\\na\\na\" deletes the leading a, retains b/a, inserts the unterminated a"):
    val old = Vector("a\n", "b\n", "a\n")
    val neu = Vector("b\n", "a\n", "a")
    assertEquals(Diff.diff(old, neu), Vector(EditOp.Delete(1), EditOp.Retain(2), EditOp.Insert(Vector("a"))))

  // ---- boundary shapes ------------------------------------------------------------

  test("empty to nonempty is a pure insert"):
    assertEquals(Diff.diff(Vector.empty, Vector("a\n")), Vector(EditOp.Insert(Vector("a\n"))))

  test("nonempty to empty is a pure delete"):
    assertEquals(Diff.diff(Vector("a\n"), Vector.empty), Vector(EditOp.Delete(1)))

  test("empty to empty is the empty script"):
    assertEquals(Diff.diff(Vector.empty, Vector.empty), Vector.empty)

  test("identical inputs yield a single coalesced retain"):
    assertEquals(Diff.diff(Vector("a\n", "b\n"), Vector("a\n", "b\n")), Vector(EditOp.Retain(2)))

  // ---- the delete-on-tie rule -----------------------------------------------------

  test("a one-token replace ties and must delete before inserting"):
    assertEquals(Diff.diff(Vector("a"), Vector("b")), Vector(EditOp.Delete(1), EditOp.Insert(Vector("b"))))

  // ---- coalescing -------------------------------------------------------------------

  test("adjacent same-kind operations across the walk are coalesced into one"):
    val old = Vector("a\n", "b\n", "c\n")
    val neu = Vector("x\n", "y\n", "c\n")
    assertEquals(Diff.diff(old, neu), Vector(EditOp.Delete(2), EditOp.Insert(Vector("x\n", "y\n")), EditOp.Retain(1)))
