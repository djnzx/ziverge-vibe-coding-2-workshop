package snap

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec

/** SPEC §4.4 — text detection, canonical tokenization, and edit-script validation and application.
  *
  * Everything here is pure: no filesystem, no environment, no clock.
  */
object Text:

  private def invalid(detail: String): SnapError =
    SnapError(s"invalid edit script: $detail")

  // ---- §4.4 text detection and tokenization -----------------------------------

  /** SPEC §4.4 — a file is text when its bytes are valid UTF-8 and contain no NUL. */
  def isText(bytes: FileBytes): Boolean =
    val array = bytes.toArray
    !array.contains(0.toByte) && isValidUtf8(array)

  /** A strict decode: `CodingErrorAction.REPORT` fails on the first malformed or unmappable byte, unlike `new String(...)`, which silently substitutes U+FFFD.
    */
  private def isValidUtf8(array: Array[Byte]): Boolean =
    val decoder = StandardCharsets.UTF_8.newDecoder
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try
      decoder.decode(ByteBuffer.wrap(array))
      true
    catch case _: CharacterCodingException => false

  /** SPEC §4.4 — split immediately after every LF byte, keeping the LF in the token; the empty file has no tokens. */
  def tokens(bytes: FileBytes): Vector[String] =
    val text = new String(bytes.toArray, StandardCharsets.UTF_8)
    if text.isEmpty then Vector.empty
    else
      val builder = Vector.newBuilder[String]
      var start   = 0
      var i       = 0
      while i < text.length do
        if text.charAt(i) == '\n' then
          builder += text.substring(start, i + 1)
          start = i + 1
        i += 1
      if start < text.length then builder += text.substring(start)
      builder.result()

  /** The inverse of [[tokens]]: concatenation reconstructs the exact bytes. */
  def untokens(tokens: Vector[String]): FileBytes =
    FileBytes.utf8(tokens.mkString)

  // ---- §4.4 edit-script validation and application -----------------------------

  private def sameKind(a: EditOp, b: EditOp): Boolean = (a, b) match
    case (EditOp.Retain(_), EditOp.Retain(_)) => true
    case (EditOp.Delete(_), EditOp.Delete(_)) => true
    case (EditOp.Insert(_), EditOp.Insert(_)) => true
    case _                                    => false

  private def isSafeCount(count: Long): Boolean =
    count > 0 && count <= Limits.maxSafeInteger

  /** A token is canonical in isolation when it holds no LF before its final byte — regardless of where it lands in the result. Whether it must also *end* in LF
    * depends on position, so that check lives in [[validate]].
    */
  private def hasNoInternalLf(token: String): Boolean =
    !token.dropRight(1).contains('\n')

  /** The index of the last operation that emits a token into the result: a `Retain` with a positive count, or an `Insert` with at least one token. A trailing
    * `Delete` emits nothing, so it can never be the one whose last token is exempt from ending in LF.
    */
  private def lastEmittingIndex(script: Vector[EditOp]): Int =
    script.lastIndexWhere:
      case EditOp.Retain(count) => count > 0
      case EditOp.Delete(_)     => false
      case EditOp.Insert(toks)  => toks.nonEmpty

  /** SPEC §4.4 — positive counts within [[Limits.maxSafeInteger]]; no adjacent operations of the same kind; no empty insert; every inserted token nonempty and
    * free of an internal LF; the script consumes exactly `oldLength` old tokens with no implicit trailing retain; the result is a canonical token sequence:
    * every token but possibly the last ends in LF.
    */
  def validate(script: Vector[EditOp], oldLength: Int): Either[SnapError, Unit] =
    if script.isEmpty then if oldLength == 0 then Right(()) else Left(invalid("must have one operation"))
    else
      val lastEmitting = lastEmittingIndex(script)

      def insertError(index: Int, toks: Vector[String]): Option[SnapError] =
        toks.zipWithIndex.collectFirst:
          case (token, _) if token.isEmpty           => invalid("insert token is empty")
          case (token, _) if !hasNoInternalLf(token) => invalid("is not canonical: insert token holds a newline before its final byte")
          case (token, tokenIndex) if !token.endsWith("\n") && !(index == lastEmitting && tokenIndex == toks.length - 1) =>
            invalid("is not canonical: token does not end with a newline")

      @tailrec
      def loop(index: Int, consumed: Long): Either[SnapError, Long] =
        if index >= script.length then Right(consumed)
        else
          val op       = script(index)
          val adjacent = index > 0 && sameKind(script(index - 1), op)
          op match
            case EditOp.Retain(count) =>
              if !isSafeCount(count) then Left(invalid("count must be a positive safe integer"))
              else if adjacent then Left(invalid("is not canonical: adjacent retain operations"))
              else loop(index + 1, consumed + count)
            case EditOp.Delete(count) =>
              if !isSafeCount(count) then Left(invalid("count must be a positive safe integer"))
              else if adjacent then Left(invalid("is not canonical: adjacent delete operations"))
              else loop(index + 1, consumed + count)
            case EditOp.Insert(toks) =>
              if toks.isEmpty then Left(invalid("insert is empty"))
              else if adjacent then Left(invalid("is not canonical: adjacent insert operations"))
              else
                insertError(index, toks) match
                  case Some(err) => Left(err)
                  case None      => loop(index + 1, consumed)

      loop(0, 0L).flatMap: consumed =>
        if consumed > oldLength then Left(invalid("consumes beyond old content"))
        else if consumed < oldLength then Left(invalid("does not consume all old content"))
        else Right(())

  /** SPEC §4.4 — apply a script to `old`'s tokens, producing the result's tokens. Validates first: an invalid script is never partially applied. */
  def apply(script: Vector[EditOp], old: Vector[String]): Either[SnapError, Vector[String]] =
    validate(script, old.length).map: _ =>
      val builder = Vector.newBuilder[String]
      var cursor  = 0
      script.foreach:
        case EditOp.Retain(count) =>
          builder ++= old.slice(cursor, cursor + count.toInt)
          cursor += count.toInt
        case EditOp.Delete(count) =>
          cursor += count.toInt
        case EditOp.Insert(toks) =>
          builder ++= toks
      builder.result()
