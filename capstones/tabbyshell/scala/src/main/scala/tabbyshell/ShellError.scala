package tabbyshell

/** Every error carries a stable message format (SPEC 3.3). */
enum ShellError:
  case Parse(detail: String, column: Int)
  case TypeMismatch(command: String, expected: String, got: String)

  def message: String = this match
    case Parse(detail, column)                => s"parse error: $detail at column $column"
    case TypeMismatch(command, expected, got) => s"$command: expected $expected, got $got"
