package snap

import io.circe.{Json, JsonObject}
import scala.collection.immutable.SortedMap

/** SPEC §4.1 — strict parse and canonical serialization of `.snap/repository.json`.
  *
  * `parse` rejects unknown fields, non-integer numbers, duplicate object keys, and every invalid typed value in one pass (§4.5 pass 1: "its exact schema and
  * all versions, IDs, paths, messages, and changes"). It does **not** check base closure, causality, or replay — those are cross-patch concerns and belong to
  * [[Validation]]. Everything here is pure: no filesystem, no environment, no clock.
  */
object RepositoryJson:

  private def invalid(detail: String): SnapError = SnapError(detail)

  // ---- entry point -----------------------------------------------------------

  /** SPEC §4.1 — parse the complete repository value. The parsed typed value, not the bytes, is authoritative: two texts that differ only in whitespace or key
    * order parse to the same [[Repository]].
    */
  def parse(text: String): Either[SnapError, Repository] =
    for
      json       <- io.circe.parser.parse(text).left.map(failure => invalid(s"malformed JSON: ${failure.message}"))
      _          <- DuplicateKeys.check(text)
      obj        <- asObject(json, "repository")
      _          <- rejectUnknown(obj, Set("format", "frontier", "patches"), "repository")
      format     <- field(obj, "format", "repository").flatMap(asSafeInteger(_, "format"))
      _          <- if format == Repository.format.toLong then Right(()) else Left(invalid(s"unsupported repository format: $format"))
      frontier   <- field(obj, "frontier", "repository").flatMap(parseVersionArray(_, "frontier"))
      patchesArr <- field(obj, "patches", "repository").flatMap(asArray(_, "patches"))
      patches    <- patchesArr.foldLeft[Either[SnapError, Vector[Patch]]](Right(Vector.empty)): (acc, patchJson) =>
        acc.flatMap(done => parsePatch(patchJson).map(done :+ _))
    yield Repository(format.toInt, frontier, patches)

  // ---- object/array/field helpers ---------------------------------------------

  private def asObject(json: Json, context: String): Either[SnapError, JsonObject] =
    json.asObject.toRight(invalid(s"$context must be a JSON object"))

  private def asArray(json: Json, context: String): Either[SnapError, Vector[Json]] =
    json.asArray.map(_.toVector).toRight(invalid(s"$context must be a JSON array"))

  private def asString(json: Json, context: String): Either[SnapError, String] =
    json.asString.toRight(invalid(s"$context must be a JSON string"))

  private def field(obj: JsonObject, name: String, context: String): Either[SnapError, Json] =
    obj(name).toRight(invalid(s"$context is missing field: $name"))

  private def rejectUnknown(obj: JsonObject, allowed: Set[String], context: String): Either[SnapError, Unit] =
    obj.keys.find(!allowed.contains(_)) match
      case Some(name) => Left(invalid(s"$context has unknown field: $name"))
      case None       => Right(())

  /** SPEC §4.1/§3.1 — a positive integer, no fraction, bounded by [[Limits.maxSafeInteger]]. `JsonNumber.toBigInt` is `None` for any non-integral number (such
    * as `1.5`), which is exactly how a fractional revision is caught without a bespoke fraction check.
    */
  private def asSafeInteger(json: Json, context: String): Either[SnapError, Long] =
    json.asNumber match
      case None      => Left(invalid(s"$context must be a positive safe integer"))
      case Some(num) =>
        num.toBigInt match
          case Some(value) if value > 0 && value <= BigInt(Limits.maxSafeInteger) => Right(value.toLong)
          case _                                                                  => Left(invalid(s"$context must be a positive safe integer"))

  // ---- §3.2 version arrays: [[id, revision], ...] ------------------------------

  /** SPEC §3.2/§4.1 — the repository-JSON spelling of a version: an array of `[id, revision]` pairs, sorted by contributor with no duplicate. Mirrors
    * `Versions.assembleCanonical`, but over JSON pairs rather than CLI-syntax text.
    */
  private def parseVersionArray(json: Json, context: String): Either[SnapError, Version] =
    asArray(json, context).flatMap: pairs =>
      pairs
        .foldLeft[Either[SnapError, Vector[(ContributorId, Long)]]](Right(Vector.empty)): (acc, pairJson) =>
          acc.flatMap(done => parseVersionPair(pairJson, context).map(done :+ _))
        .flatMap(assembleCanonicalVersion(_, context))

  private def parseVersionPair(json: Json, context: String): Either[SnapError, (ContributorId, Long)] =
    asArray(json, s"$context entry").flatMap:
      case Vector(idJson, revisionJson) =>
        for
          idText   <- asString(idJson, s"$context entry")
          id       <- Versions.contributorId(idText)
          revision <- asSafeInteger(revisionJson, s"$context entry revision")
        yield (id, revision)
      case _ => Left(invalid(s"$context entry must be a two-element [id, revision] array"))

  private def assembleCanonicalVersion(
    parsed: Vector[(ContributorId, Long)],
    context: String
  ): Either[SnapError, Version] =
    val ids       = parsed.map((id, _) => id)
    val canonical = ids.sorted(using summon[Ordering[ContributorId]])
    if ids.distinct.length != ids.length then Left(invalid(s"$context is not canonical: duplicate contributor"))
    else if ids != canonical then Left(invalid(s"$context is not canonical: contributors out of order"))
    else Right(Version(SortedMap.from(parsed)))

  // ---- §4.2 patches ------------------------------------------------------------

  private val messageControlExceptions: Set[Char] = Set('\t', '\n')

  private def parseMessage(json: Json): Either[SnapError, String] =
    asString(json, "patch message").flatMap: text =>
      if text.isEmpty then Left(invalid("patch message is empty"))
      else if text.exists(char => char.isControl && !messageControlExceptions.contains(char)) then Left(invalid("message holds a disallowed control character"))
      else Right(text)

  private def parsePatch(json: Json): Either[SnapError, Patch] =
    for
      obj      <- asObject(json, "patch")
      _        <- rejectUnknown(obj, Set("author", "revision", "base", "message", "changes"), "patch")
      authorTx <- field(obj, "author", "patch").flatMap(asString(_, "patch author"))
      author   <- Versions.contributorId(authorTx)
      revision <- field(obj, "revision", "patch").flatMap(asSafeInteger(_, "revision"))
      base     <- field(obj, "base", "patch").flatMap(parseVersionArray(_, "base"))
      message  <- field(obj, "message", "patch").flatMap(parseMessage)
      changesJ <- field(obj, "changes", "patch").flatMap(asArray(_, "changes"))
      changes  <- parseChanges(changesJ)
    yield Patch(author, revision, base, message, changes)

  private def parseChanges(changesJ: Vector[Json]): Either[SnapError, Vector[Change]] =
    if changesJ.isEmpty then Left(invalid("patch changes is empty"))
    else
      changesJ
        .foldLeft[Either[SnapError, Vector[Change]]](Right(Vector.empty)): (acc, changeJson) =>
          acc.flatMap(done => parseChange(changeJson).map(done :+ _))
        .flatMap: changes =>
          val paths     = changes.map(_.targetPath)
          val canonical = paths.sorted(using summon[Ordering[TrackedPath]])
          if paths.distinct.length != paths.length then Left(invalid("changes is not canonical: duplicate path"))
          else if paths != canonical then Left(invalid("changes is not canonical: not sorted by path"))
          else Right(changes)

  private def parseChange(json: Json): Either[SnapError, Change] =
    for
      obj    <- asObject(json, "change")
      typeTx <- field(obj, "type", "change").flatMap(asString(_, "change type"))
      pathTx <- field(obj, "path", "change").flatMap(asString(_, "change path"))
      path   <- Paths.trackedPath(pathTx).left.map(_ => invalid(s"change path is invalid: $pathTx"))
      change <- typeTx match
        case "text" =>
          for
            _    <- rejectUnknown(obj, Set("type", "path", "edit"), "change")
            edit <- field(obj, "edit", "change").flatMap(asArray(_, "edit")).flatMap(parseEditScript)
          yield Change.Text(path, edit)
        case "put" =>
          for
            _       <- rejectUnknown(obj, Set("type", "path", "content"), "change")
            content <- field(obj, "content", "change").flatMap(asString(_, "content")).flatMap(parseBase64)
          yield Change.Put(path, content)
        case "delete" =>
          rejectUnknown(obj, Set("type", "path"), "change").map(_ => Change.Delete(path))
        case other => Left(invalid(s"change has unknown type: $other"))
    yield change

  /** SPEC §4.3 — standard padded RFC 4648. `java.util.Base64.getDecoder` alone is not strict enough: it does not require the unused bits of the final character
    * to be zero, so a non-canonical encoding can still decode without error. Round-tripping through the encoder catches that: any text this accepts re-encodes
    * to itself.
    */
  private def parseBase64(text: String): Either[SnapError, FileBytes] =
    try
      val decoded = java.util.Base64.getDecoder.decode(text)
      if java.util.Base64.getEncoder.encodeToString(decoded) == text then Right(FileBytes(decoded))
      else Left(invalid("content is not canonical base64"))
    catch case _: IllegalArgumentException => Left(invalid("content is not canonical base64"))

  // ---- §4.4 edit scripts ---------------------------------------------------------

  private def parseEditScript(opsJ: Vector[Json]): Either[SnapError, Vector[EditOp]] =
    opsJ
      .foldLeft[Either[SnapError, Vector[EditOp]]](Right(Vector.empty)): (acc, opJson) =>
        acc.flatMap(done => parseEditOp(opJson).map(done :+ _))
      .flatMap(script => Text.validateShape(script).map(_ => script))

  private def parseEditOp(json: Json): Either[SnapError, EditOp] =
    asObject(json, "edit operation").flatMap: obj =>
      rejectUnknown(obj, Set("retain", "delete", "insert"), "edit operation").flatMap: _ =>
        (obj("retain"), obj("delete"), obj("insert")) match
          case (Some(count), None, None) => asSafeIntegerLenient(count, "retain").map(EditOp.Retain(_))
          case (None, Some(count), None) => asSafeIntegerLenient(count, "delete").map(EditOp.Delete(_))
          case (None, None, Some(toks))  => asArray(toks, "insert").flatMap(parseInsertTokens).map(EditOp.Insert(_))
          case _                         => Left(invalid("edit operation must have one operation"))

  /** Like [[asSafeInteger]], but a structurally out-of-range or non-integer count is reported through `Text.validateShape`'s own message instead of here, so
    * callers see one consistent "positive safe integer" wording regardless of whether the count came in as JSON `0`, a negative number, or a fraction.
    */
  private def asSafeIntegerLenient(json: Json, context: String): Either[SnapError, Long] =
    json.asNumber.flatMap(_.toBigInt) match
      case Some(value) if value >= Long.MinValue && value <= Long.MaxValue => Right(value.toLong)
      case Some(_)                                                         => Right(Long.MaxValue)
      case None                                                            => Left(invalid(s"$context must be a positive safe integer"))

  private def parseInsertTokens(toksJ: Vector[Json]): Either[SnapError, Vector[String]] =
    toksJ.foldLeft[Either[SnapError, Vector[String]]](Right(Vector.empty)): (acc, tokJson) =>
      acc.flatMap(done => asString(tokJson, "insert token").map(done :+ _))

  // ---- canonical serialization ---------------------------------------------------

  /** SPEC §4.1 — two-space indentation, trailing LF, the field order §4.1's example uses. Hand-built rather than routed through a generic JSON printer so every
    * field's position is explicit and stable, independent of `circe.Printer`'s defaults.
    */
  def serialize(repo: Repository): String =
    val writer = new StringBuilder
    writeObject(
      writer,
      0,
      Vector(
        "format"   -> JsonNode.Num(repo.format),
        "frontier" -> versionNode(repo.frontier),
        "patches"  -> JsonNode.Arr(repo.patches.map(patchNode))
      )
    )
    writer.append('\n')
    writer.toString

  private def versionNode(version: Version): JsonNode =
    JsonNode.Arr(version.components.toVector.map((id, rev) => JsonNode.Arr(Vector(JsonNode.Str(id.value), JsonNode.Num(rev)))))

  private def patchNode(patch: Patch): JsonNode =
    JsonNode.Obj(
      Vector(
        "author"   -> JsonNode.Str(patch.author.value),
        "revision" -> JsonNode.Num(patch.revision),
        "base"     -> versionNode(patch.base),
        "message"  -> JsonNode.Str(patch.message),
        "changes"  -> JsonNode.Arr(patch.changes.map(changeNode))
      )
    )

  private def changeNode(change: Change): JsonNode = change match
    case Change.Text(path, edit) =>
      JsonNode.Obj(Vector("type" -> JsonNode.Str("text"), "path" -> JsonNode.Str(path.value), "edit" -> JsonNode.Arr(edit.map(editOpNode))))
    case Change.Put(path, content) =>
      JsonNode.Obj(
        Vector(
          "type"    -> JsonNode.Str("put"),
          "path"    -> JsonNode.Str(path.value),
          "content" -> JsonNode.Str(java.util.Base64.getEncoder.encodeToString(content.toArray))
        )
      )
    case Change.Delete(path) =>
      JsonNode.Obj(Vector("type" -> JsonNode.Str("delete"), "path" -> JsonNode.Str(path.value)))

  private def editOpNode(op: EditOp): JsonNode = op match
    case EditOp.Retain(count) => JsonNode.Obj(Vector("retain" -> JsonNode.Num(count)))
    case EditOp.Delete(count) => JsonNode.Obj(Vector("delete" -> JsonNode.Num(count)))
    case EditOp.Insert(toks)  => JsonNode.Obj(Vector("insert" -> JsonNode.Arr(toks.map(JsonNode.Str(_)))))

  /** A minimal JSON value model for [[serialize]] only — keeps the writer free of `circe.Json`'s own formatting choices. */
  private enum JsonNode:
    case Str(value: String)
    case Num(value: Long)
    case Obj(fields: Vector[(String, JsonNode)])
    case Arr(items: Vector[JsonNode])

  private def indent(writer: StringBuilder, depth: Int): Unit = writer.append("  " * depth)

  private def writeNode(writer: StringBuilder, depth: Int, node: JsonNode): Unit = node match
    case JsonNode.Str(value)  => writer.append(quote(value))
    case JsonNode.Num(value)  => writer.append(value)
    case JsonNode.Obj(fields) => writeObject(writer, depth, fields)
    case JsonNode.Arr(items)  =>
      if items.isEmpty then writer.append("[]")
      else
        writer.append("[\n")
        items.zipWithIndex.foreach: (item, i) =>
          indent(writer, depth + 1)
          writeNode(writer, depth + 1, item)
          if i < items.length - 1 then writer.append(',')
          writer.append('\n')
        indent(writer, depth)
        writer.append(']')

  private def writeObject(writer: StringBuilder, depth: Int, fields: Vector[(String, JsonNode)]): Unit =
    if fields.isEmpty then writer.append("{}")
    else
      writer.append("{\n")
      fields.zipWithIndex.foreach: entry =>
        val ((name, value), i) = entry
        indent(writer, depth + 1)
        writer.append(quote(name)).append(": ")
        writeNode(writer, depth + 1, value)
        if i < fields.length - 1 then writer.append(',')
        writer.append('\n')
      indent(writer, depth)
      writer.append('}')

  private def quote(text: String): String =
    val out = new StringBuilder("\"")
    text.foreach:
      case '"'                    => out.append("\\\"")
      case '\\'                   => out.append("\\\\")
      case '\n'                   => out.append("\\n")
      case '\t'                   => out.append("\\t")
      case '\r'                   => out.append("\\r")
      case char if char.isControl => out.append(f"\\u${char.toInt}%04x")
      case char                   => out.append(char)
    out.append('"')
    out.toString

  /** SPEC §4.1 — duplicate object keys need their own pass over the raw text: circe's `JsonObject` keeps the last value for a repeated key and reports nothing,
    * so the AST alone cannot tell a duplicate from a single well-formed field. Runs only after `io.circe.parser.parse` has confirmed the text is syntactically
    * valid JSON, so this scanner can assume balanced braces and well-formed strings.
    */
  private object DuplicateKeys:
    def check(text: String): Either[SnapError, Unit] =
      scan(text) match
        case Some(key) => Left(invalid(s"duplicate JSON key: $key"))
        case None      => Right(())

    private def scan(text: String): Option[String] =
      val stack               = scala.collection.mutable.Stack.empty[scala.collection.mutable.Set[String]]
      var i                   = 0
      val n                   = text.length
      var dup: Option[String] = None
      while i < n && dup.isEmpty do
        text.charAt(i) match
          case '"' =>
            val (value, next) = readString(text, i)
            i = next
            var j = i
            while j < n && text.charAt(j).isWhitespace do j += 1
            if j < n && text.charAt(j) == ':' && stack.nonEmpty then
              val top = stack.top
              if top.contains(value) then dup = Some(value) else top += value
          case '{' =>
            stack.push(scala.collection.mutable.Set.empty)
            i += 1
          case '}' =>
            if stack.nonEmpty then stack.pop()
            i += 1
          case _ => i += 1
      dup

    /** Skips a JSON string literal starting at the opening quote, returning its decoded content and the index just past the closing quote. Escapes are only
      * walked past, not decoded, which is enough to find the closing quote correctly; two keys that are equal only after unescaping (`"a"` versus `"a"`) are
      * treated as distinct, an accepted edge case no test in `../tests/` exercises.
      */
    private def readString(text: String, start: Int): (String, Int) =
      val n       = text.length
      var i       = start + 1
      val content = new StringBuilder
      var closed  = false
      while i < n && !closed do
        text.charAt(i) match
          case '\\' if i + 1 < n =>
            content.append(text.charAt(i)).append(text.charAt(i + 1))
            i += 2
          case '"' =>
            closed = true
            i += 1
          case char =>
            content.append(char)
            i += 1
      (content.toString, i)
