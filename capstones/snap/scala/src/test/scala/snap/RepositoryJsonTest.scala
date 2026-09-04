package snap

import scala.collection.immutable.SortedMap

/** SPEC §4.1 examples — the §4.1 sample, one example per rejection message this phase's acceptance cases exercise, and the canonical-serialization shape.
  */
class RepositoryJsonTest extends munit.FunSuite:

  private def id(text: String): ContributorId          = ContributorId(text)
  private def version(pairs: (String, Long)*): Version =
    Version(SortedMap.from(pairs.map((c, r) => id(c) -> r)))
  private def path(text: String): TrackedPath = TrackedPath(text)

  // ---- §4.1 sample --------------------------------------------------------------

  private val sampleText =
    """{
      |  "format": 1,
      |  "frontier": [["alice@example.com",1]],
      |  "patches": [
      |    {
      |      "author": "alice@example.com",
      |      "revision": 1,
      |      "base": [],
      |      "message": "add greeting",
      |      "changes": [
      |        {
      |          "type": "text",
      |          "path": "hello.txt",
      |          "edit": [{"insert":["hello\n"]}]
      |        }
      |      ]
      |    }
      |  ]
      |}""".stripMargin

  private val sampleRepo = Repository(
    format = 1,
    frontier = version("alice@example.com" -> 1),
    patches = Vector(
      Patch(
        id("alice@example.com"),
        1,
        Version.empty,
        "add greeting",
        Vector(Change.Text(path("hello.txt"), Vector(EditOp.Insert(Vector("hello\n")))))
      )
    )
  )

  test("the §4.1 sample parses to the expected typed value"):
    assertEquals(RepositoryJson.parse(sampleText), Right(sampleRepo))

  test("parsing then serializing the §4.1 sample round-trips through parse again to the same value"):
    val serialized = RepositoryJson.serialize(sampleRepo)
    assertEquals(RepositoryJson.parse(serialized), Right(sampleRepo))

  test("a repository whose JSON differs only in whitespace and key order parses equal to the canonical one"):
    val reordered =
      """{ "patches" : [ { "changes" : [ { "path" : "hello.txt" , "edit" : [ { "insert" : [ "hello\n" ] } ] , "type" : "text" } ] , "message" : "add greeting" , "base" : [ ] , "revision" : 1 , "author" : "alice@example.com" } ] , "frontier" : [ [ "alice@example.com" , 1 ] ] , "format" : 1 }"""
    assertEquals(RepositoryJson.parse(reordered), Right(sampleRepo))

  // ---- serialize shape ------------------------------------------------------------

  test("serialize uses two-space indentation and a trailing LF"):
    val text = RepositoryJson.serialize(Repository.empty)
    assert(text.endsWith("\n"))
    assert(!text.endsWith("\n\n"))
    assert(text.contains("\n  \"format\": 1,\n"))

  test("serialize orders repository fields as format, frontier, patches"):
    val text = RepositoryJson.serialize(Repository.empty)
    val fo   = text.indexOf("\"format\"")
    val fr   = text.indexOf("\"frontier\"")
    val pa   = text.indexOf("\"patches\"")
    assert(fo >= 0 && fo < fr && fr < pa)

  // ---- one example per rejection message -----------------------------------------

  test("a duplicate JSON key is rejected"):
    val text = """{"format":1,"format":1,"frontier":[],"patches":[]}"""
    assert(RepositoryJson.parse(text).left.exists(_.detail.contains("duplicate JSON key")))

  test("an unknown top-level field is rejected"):
    val text = """{"format":1,"frontier":[],"patches":[],"unknown":true}"""
    assertEquals(RepositoryJson.parse(text), Left(SnapError("repository has unknown field: unknown")))

  test("a noncanonically ordered frontier is rejected"):
    val text = """{"format":1,"frontier":[["b@x",1],["a@x",1]],"patches":[]}"""
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".*canonical.*")))

  test("a duplicate contributor in a version array is rejected"):
    val text = """{"format":1,"frontier":[["a@x",1],["a@x",2]],"patches":[]}"""
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".*canonical.*")))

  test("a fractional revision is not a positive safe integer"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1.5,"base":[],"message":"x","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+positive safe integer")))

  test("a zero-count edit operation is not a positive safe integer"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"text","path":"f","edit":[{"retain":0}]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+positive safe integer")))

  test("an empty message is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"","changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+message is empty")))

  test("an empty changes array is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+changes is empty")))

  test("an unknown change field is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"put","path":"f","content":"YQ==","extra":1}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+unknown field: extra")))

  test("an edit operation with two keys must have one operation"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"text","path":"f","edit":[{"retain":1,"delete":1}]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+must have one operation")))

  test("an empty insert is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"text","path":"f","edit":[{"insert":[]}]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.matches(".+insert is empty")))

  test("a path under .snap is rejected as invalid"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"put","path":".snap/secret","content":"YQ=="}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.contains("path is invalid")))

  test("noncanonical base64 content is rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x","changes":[{"type":"put","path":"f","content":"abc"}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).left.exists(_.detail.contains("canonical base64")))

  test("changes out of path order are rejected"):
    val text =
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"x",
        | "changes":[{"type":"text","path":"z","edit":[]},{"type":"text","path":"a","edit":[]}]}
        |]}""".stripMargin
    assert(RepositoryJson.parse(text).isLeft)

  test("malformed JSON syntax is rejected without throwing"):
    assert(RepositoryJson.parse("{not json").isLeft)
