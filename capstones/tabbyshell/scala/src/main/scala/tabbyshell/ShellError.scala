package tabbyshell

/** Every error carries a stable message format (SPEC 3.3). */
enum ShellError:
  case Parse(detail: String, column: Int)

  def message: String = this match
    case Parse(detail, column) => s"parse error: $detail at column $column"
