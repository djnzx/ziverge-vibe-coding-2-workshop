package snap

/** SPEC §4.4 examples — the specific cases a reader will look up, plus one example per rejection message the plan's catalog names for this phase.
  */
class TextTest extends munit.FunSuite:

  private def bytes(text: String): FileBytes = FileBytes.utf8(text)

  // ---- isText -----------------------------------------------------------------

  test("valid UTF-8 without NUL is text"):
    assert(Text.isText(bytes("hello\n")))

  test("a NUL byte is never text"):
    assert(!Text.isText(FileBytes(Array[Byte]('a', 0, 'b'))))

  test("a malformed UTF-8 byte sequence is not text"):
    assert(!Text.isText(FileBytes(Array[Byte](0xc0.toByte, 0x80.toByte))))

  test("the empty file is text"):
    assert(Text.isText(FileBytes.empty))

  // ---- tokens / untokens --------------------------------------------------------

  test("a\\r\\nb tokenizes into two tokens, LF kept, CR left attached"):
    assertEquals(Text.tokens(bytes("a\r\nb")), Vector("a\r\n", "b"))

  test("the empty file has no tokens"):
    assertEquals(Text.tokens(FileBytes.empty), Vector.empty)

  test("a file ending without LF keeps its unterminated final token"):
    assertEquals(Text.tokens(bytes("a\nb")), Vector("a\n", "b"))

  test("untokens is the inverse of tokens"):
    val original = bytes("a\nb\nc")
    assertEquals(Text.untokens(Text.tokens(original)), original)

  // ---- validate: acceptance ----------------------------------------------------

  test("an empty script is valid when creating an empty file"):
    assertEquals(Text.validate(Vector.empty, 0), Right(()))

  test("a script of only inserts is valid against an empty old length"):
    assertEquals(Text.validate(Vector(EditOp.Insert(Vector("hello\n"))), 0), Right(()))

  test("retain, delete, and insert may appear in sequence without repeating a kind"):
    val script = Vector(EditOp.Retain(1), EditOp.Delete(1), EditOp.Insert(Vector("new\n")), EditOp.Retain(1))
    assertEquals(Text.validate(script, 3), Right(()))

  test("the last inserted token may omit its trailing LF"):
    assertEquals(Text.validate(Vector(EditOp.Insert(Vector("tail"))), 0), Right(()))

  // ---- validate: one example per rejection message ------------------------------

  test("an empty script against a nonempty old length must have one operation"):
    assert(Text.validate(Vector.empty, 1).left.exists(_.detail.matches(".+must have one operation")))

  test("an insert with zero tokens is empty"):
    assert(Text.validate(Vector(EditOp.Insert(Vector.empty)), 0).left.exists(_.detail.matches(".+insert is empty")))

  test("a script that retains more than the old length consumes beyond old content"):
    assert(Text.validate(Vector(EditOp.Retain(2)), 1).left.exists(_.detail.matches(".+consumes beyond old content")))

  test("a script that retains less than the old length does not consume it all"):
    assert(Text.validate(Vector(EditOp.Delete(1)), 2).left.exists(_.detail.matches(".+does not consume old content")))

  test("a zero count is not a positive safe integer"):
    assert(Text.validate(Vector(EditOp.Retain(0)), 0).left.exists(_.detail.matches(".+positive safe integer")))

  test("a count beyond Limits.maxSafeInteger is not a positive safe integer"):
    assert(Text.validate(Vector(EditOp.Delete(Limits.maxSafeInteger + 1)), 0).left.exists(_.detail.matches(".+positive safe integer")))

  test("adjacent retains are not canonical"):
    assert(Text.validate(Vector(EditOp.Retain(1), EditOp.Retain(1)), 2).left.exists(_.detail.matches(".*canonical.*")))

  test("adjacent deletes are not canonical"):
    assert(Text.validate(Vector(EditOp.Delete(1), EditOp.Delete(1)), 2).left.exists(_.detail.matches(".*canonical.*")))

  test("adjacent inserts are not canonical"):
    val script = Vector(EditOp.Insert(Vector("a\n")), EditOp.Insert(Vector("b\n")))
    assert(Text.validate(script, 0).left.exists(_.detail.matches(".*canonical.*")))

  test("a non-final inserted token missing its trailing LF is not canonical"):
    val script = Vector(EditOp.Insert(Vector("a")), EditOp.Retain(1))
    assert(Text.validate(script, 1).left.exists(_.detail.matches(".*canonical.*")))

  test("an inserted token holding an internal LF is not canonical"):
    val script = Vector(EditOp.Insert(Vector("a\nb\n")))
    assert(Text.validate(script, 0).left.exists(_.detail.matches(".*canonical.*")))

  // ---- apply --------------------------------------------------------------------

  test("the empty script applied to no tokens yields an empty file"):
    assertEquals(Text.apply(Vector.empty, Vector.empty), Right(Vector.empty))

  test("apply inserts, retains, and deletes exactly as scripted"):
    val script = Vector(EditOp.Retain(1), EditOp.Delete(1), EditOp.Insert(Vector("new\n")), EditOp.Retain(1))
    assertEquals(Text.apply(script, Vector("a\n", "b\n", "c\n")), Right(Vector("a\n", "new\n", "c\n")))

  test("apply rejects an invalid script instead of partially applying it"):
    assert(Text.apply(Vector(EditOp.Retain(5)), Vector("a\n")).isLeft)
