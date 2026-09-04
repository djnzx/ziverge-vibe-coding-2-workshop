package snap

/** SPEC §6.3 examples — one case per table row, the concurrent-insert-at-one-cursor case the `Q insert` priority decides, and three cases shaped like
  * `22-ot-matrix.yaml`'s merge scenarios, worked by hand against the base tree `"0\n1\n2\n3\n4\n"` it uses.
  */
class OtTest extends munit.FunSuite:

  // ---- one example per table row --------------------------------------------------

  test("Q insert: retains its length, consuming Q only"):
    val p = Vector(EditOp.Delete(1))
    val q = Vector(EditOp.Insert(Vector("x\n")), EditOp.Delete(1))
    assertEquals(Ot.transform(p, q), Vector(EditOp.Retain(1)))

  test("P insert: passes through unchanged, consuming P only"):
    val p = Vector(EditOp.Insert(Vector("x\n")), EditOp.Retain(1))
    val q = Vector(EditOp.Retain(1))
    assertEquals(Ot.transform(p, q), Vector(EditOp.Insert(Vector("x\n")), EditOp.Retain(1)))

  test("P retain, Q retain: retains the smaller count, both consumed"):
    val p = Vector(EditOp.Retain(3))
    val q = Vector(EditOp.Retain(2), EditOp.Retain(1))
    assertEquals(Ot.transform(p, q), Vector(EditOp.Retain(3)))

  test("P delete, Q retain: deletes the smaller count, both consumed"):
    val p = Vector(EditOp.Delete(2))
    val q = Vector(EditOp.Retain(2))
    assertEquals(Ot.transform(p, q), Vector(EditOp.Delete(2)))

  test("P retain, Q delete: emits nothing, both consumed"):
    val p = Vector(EditOp.Retain(2))
    val q = Vector(EditOp.Delete(2))
    assertEquals(Ot.transform(p, q), Vector.empty)

  test("P delete, Q delete: emits nothing, both consumed"):
    val p = Vector(EditOp.Delete(2))
    val q = Vector(EditOp.Delete(2))
    assertEquals(Ot.transform(p, q), Vector.empty)

  // ---- concurrent inserts at one cursor: Q insert has priority ---------------------

  test("concurrent inserts at one cursor land in canonical integration order: Q's insert first"):
    val p           = Vector(EditOp.Insert(Vector("p\n")), EditOp.Retain(1))
    val q           = Vector(EditOp.Insert(Vector("q\n")), EditOp.Retain(1))
    val transformed = Ot.transform(p, q)
    assertEquals(transformed, Vector(EditOp.Retain(1), EditOp.Insert(Vector("p\n")), EditOp.Retain(1)))
    // Applied to Q's own result, P's insert lands after Q's — never reordered ahead of it.
    assertEquals(Text.apply(transformed, Vector("q\n", "x\n")), Right(Vector("q\n", "p\n", "x\n")))

  // ---- three-way cases shaped like 22-ot-matrix.yaml --------------------------------

  private val base = Vector("0\n", "1\n", "2\n", "3\n", "4\n")

  /** Runs one merge step exactly as replay will (§6.2): `p` is the incoming authored edit against `base`, `q` is the receiving side's own context edit against
    * the same `base`, and the result is `p` transformed through `q` and applied to `q`'s own result.
    */
  private def merge(incoming: Vector[String], context: Vector[String]): Either[SnapError, Vector[String]] =
    val p = Diff.diff(base, incoming)
    val q = Diff.diff(base, context)
    Text.apply(Ot.transform(p, q), context)

  test("22-ot-matrix.yaml delete/delete: the same base token is deleted only once"):
    // dd-a deletes "1\n" and "2\n"; dd-b (already integrated) deletes only "1\n".
    assertEquals(merge(Vector("0\n", "3\n", "4\n"), Vector("0\n", "2\n", "3\n", "4\n")), Right(Vector("0\n", "3\n", "4\n")))

  test("22-ot-matrix.yaml split: P insert, Q insert, unequal-count splitting, overlapping deletes, and a trailing P insert together"):
    // split-a prepends "A\n", drops "1\n"/"2\n", appends "TAIL\n"; split-b (already integrated) replaces "2\n" with "B\n".
    assertEquals(
      merge(Vector("A\n", "0\n", "3\n", "4\n", "TAIL\n"), Vector("0\n", "1\n", "B\n", "3\n", "4\n")),
      Right(Vector("A\n", "0\n", "B\n", "3\n", "4\n", "TAIL\n"))
    )

  test("22-ot-matrix.yaml retain/delete: a token retained by the incoming edit but deleted by context stays deleted"):
    // rd-b deletes "1\n"; rd-a (already integrated) retains everything and appends "A\n".
    assertEquals(merge(Vector("0\n", "2\n", "3\n", "4\n"), Vector("0\n", "1\n", "2\n", "3\n", "4\n", "A\n")), Right(Vector("0\n", "2\n", "3\n", "4\n", "A\n")))

  test("22-ot-matrix.yaml survive: an inserted token is never touched by a concurrent delete of a base token"):
    // survive-b inserts "B\n" before "1\n"; survive-a (already integrated) deletes "1\n".
    assertEquals(
      merge(Vector("0\n", "B\n", "1\n", "2\n", "3\n", "4\n"), Vector("0\n", "2\n", "3\n", "4\n")),
      Right(Vector("0\n", "B\n", "2\n", "3\n", "4\n"))
    )
