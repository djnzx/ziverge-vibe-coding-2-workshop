package snap

/** SPEC §4.5 examples — one example per validation pass, each built from the exact scenario `../tests/15-repository-validation.yaml`,
  * `../tests/23-strict-validation-matrix.yaml`, `../tests/27-history-canonicality.yaml`, or `../tests/16-dot-collision.yaml` exercises, so this phase's unit
  * suite is evidence the acceptance suite will pass once Phase 11 wires a CLI to it.
  */
class ValidationTest extends munit.FunSuite:

  private def parse(text: String): Repository = RepositoryJson.parse(text) match
    case Right(repo) => repo
    case Left(err)   => fail(s"fixture failed to parse: ${err.detail}")

  // ---- pass 2: patch order ---------------------------------------------------------

  test("patches out of author/revision order are rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"b@x","revision":1,"base":[],"message":"b","changes":[{"type":"text","path":"b","edit":[]}]},
        |{"author":"a@x","revision":1,"base":[],"message":"a","changes":[{"type":"text","path":"a","edit":[]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).isLeft)

  // ---- pass 3: base closure and the revision formula -------------------------------

  test("a base referencing a missing patch is rejected by name"):
    val text =
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"gap","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.contains("missing a@x")))

  test("a revision that does not follow its own base is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[["a@x",1]],"message":"wrong dot","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).isLeft)

  // ---- pass 4: acyclic causality ----------------------------------------------------

  test("a mutual cycle between two contributors is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[["b@x",1]],"message":"cycle a","changes":[{"type":"text","path":"a","edit":[]}]},
        |{"author":"b@x","revision":1,"base":[["a@x",1]],"message":"cycle b","changes":[{"type":"text","path":"b","edit":[]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.contains("cyclic or incomplete patch history")))

  // ---- pass 5: every change against its materialized exact base --------------------

  test("a text edit that under-consumes the base tokens is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"base","changes":[{"type":"text","path":"f","edit":[{"insert":["one\n","two\n"]}]}]},
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"underconsume","changes":[{"type":"text","path":"f","edit":[{"retain":1}]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.contains("does not consume old content")))

  test("a text edit that over-consumes the base tokens is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"base","changes":[{"type":"text","path":"f","edit":[{"insert":["one\n"]}]}]},
        |{"author":"b@x","revision":1,"base":[["a@x",1]],"message":"overconsume","changes":[{"type":"text","path":"f","edit":[{"delete":2}]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.matches(".+consumes beyond old content")))

  test("a patch creating both a path and its child is rejected as a tree conflict"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"prefix","changes":[
        |  {"type":"put","path":"a","content":"YQ=="},
        |  {"type":"put","path":"a/b","content":"Yg=="}
        |]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.contains("tree paths conflict")))

  test("a put that changes nothing is a no-op"):
    val text =
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"base","changes":[{"type":"put","path":"f","content":"YQ=="}]},
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"no op","changes":[{"type":"put","path":"f","content":"YQ=="}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.contains("no-op change")))

  test("a delete of an absent path is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"base","changes":[{"type":"put","path":"f","content":"YQ=="}]},
        |{"author":"b@x","revision":1,"base":[],"message":"absent","changes":[{"type":"delete","path":"f"}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.matches("delete of absent path: f")))

  test("an edit that creates an empty file from an absent path is not a no-op"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"empty file","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assertEquals(Validation.validate(parse(text)), Right(()))

  test("a text edit against binary content is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"binary","changes":[{"type":"put","path":"f","content":"AA=="}]},
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"text over binary","changes":[{"type":"text","path":"f","edit":[{"delete":1}]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).isLeft)

  // ---- pass 6: frontier closure -----------------------------------------------------

  test("a patch outside the frontier's closure is unreachable"):
    val text =
      """{"format":1,"frontier":[],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"unreachable","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assert(Validation.validate(parse(text)).left.exists(_.detail.matches("unreachable patch: .+")))

  test("a valid single-patch repository validates"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"hello","changes":[{"type":"text","path":"f","edit":[{"insert":["hi\n"]}]}]}
        |]}""".stripMargin
    assertEquals(Validation.validate(parse(text)), Right(()))

  // ---- §3.5 dot collision -------------------------------------------------------------

  test("identical patches at the same dot are not a collision"):
    val local    = Vector(Patch(ContributorId("a@x"), 1, Version.empty, "m", Vector(Change.Delete(TrackedPath("f")))))
    val incoming = local
    assertEquals(Validation.dotCollision(local, incoming), Right(()))

  test("structurally different patches at the same dot collide"):
    val local    = Vector(Patch(ContributorId("a@x"), 1, Version.empty, "local", Vector(Change.Delete(TrackedPath("f")))))
    val incoming = Vector(Patch(ContributorId("a@x"), 1, Version.empty, "different", Vector(Change.Delete(TrackedPath("f")))))
    assertEquals(
      Validation.dotCollision(local, incoming),
      Left(SnapError("patch collision: a@x revision 1"))
    )

  // ---- §4.1 `known` ---------------------------------------------------------------------

  test("the frontier is known"):
    val repo = parse(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"hello","changes":[{"type":"text","path":"f","edit":[{"insert":["hi\n"]}]}]}
        |]}""".stripMargin
    )
    assert(Validation.known(repo, repo.frontier))

  test("a version selecting a nonexistent revision is not known"):
    val repo = parse(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"hello","changes":[{"type":"text","path":"f","edit":[{"insert":["hi\n"]}]}]}
        |]}""".stripMargin
    )
    val unknown = Version(scala.collection.immutable.SortedMap(ContributorId("a@x") -> 2L))
    assert(!Validation.known(repo, unknown))

  test("the empty version is always known"):
    val repo = parse("""{"format":1,"frontier":[],"patches":[]}""")
    assert(Validation.known(repo, Version.empty))
