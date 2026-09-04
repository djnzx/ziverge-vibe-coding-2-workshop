package snap

/** Strict JSON checks shared by repository and configuration parsing.
  *
  * Circe's `JsonObject` retains only the last duplicate field, so an AST alone cannot distinguish `{"id":"a"}` from `{"id":"a","id":"b"}`. Call
  * [[duplicateKeys]] only after Circe has accepted the text syntactically; the scanner then relies on balanced braces and well-formed string literals.
  */
object JsonValidation:

  /** Rejects the first duplicate object field in the raw JSON text. */
  def duplicateKeys(text: String): Either[SnapError, Unit] =
    scan(text) match
      case Some(key) => Left(SnapError(s"duplicate JSON key: $key"))
      case None      => Right(())

  private def scan(text: String): Option[String] =
    val stack                     = scala.collection.mutable.Stack.empty[scala.collection.mutable.Set[String]]
    var i                         = 0
    val n                         = text.length
    var duplicate: Option[String] = None
    while i < n && duplicate.isEmpty do
      text.charAt(i) match
        case '"' =>
          val (value, next) = readString(text, i)
          i = next
          var after = i
          while after < n && text.charAt(after).isWhitespace do after += 1
          if after < n && text.charAt(after) == ':' && stack.nonEmpty then
            val fields = stack.top
            if fields.contains(value) then duplicate = Some(value) else fields += value
        case '{' =>
          stack.push(scala.collection.mutable.Set.empty)
          i += 1
        case '}' =>
          if stack.nonEmpty then stack.pop()
          i += 1
        case _ => i += 1
    duplicate

  /** Decodes one JSON string literal. The caller has already established syntactic validity, so every escape is complete and valid. Decoding is important:
    * `"id"` and `"\\u0069d"` name the same JSON member and must therefore collide.
    */
  private def readString(text: String, start: Int): (String, Int) =
    val n       = text.length
    var i       = start + 1
    val content = new StringBuilder
    var closed  = false
    while i < n && !closed do
      text.charAt(i) match
        case '\\' if i + 1 < n =>
          text.charAt(i + 1) match
            case '"'  => content.append('"'); i += 2
            case '\\' => content.append('\\'); i += 2
            case '/'  => content.append('/'); i += 2
            case 'b'  => content.append('\b'); i += 2
            case 'f'  => content.append('\f'); i += 2
            case 'n'  => content.append('\n'); i += 2
            case 'r'  => content.append('\r'); i += 2
            case 't'  => content.append('\t'); i += 2
            case 'u'  =>
              content.append(Integer.parseInt(text.substring(i + 2, i + 6), 16).toChar)
              i += 6
            case _ => i += 2
        case '"' =>
          closed = true
          i += 1
        case char =>
          content.append(char)
          i += 1
    (content.toString, i)
