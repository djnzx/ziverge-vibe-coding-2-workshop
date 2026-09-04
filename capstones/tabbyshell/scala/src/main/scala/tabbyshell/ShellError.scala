package tabbyshell

/** Every error carries a stable message format (SPEC 3.3). */
enum ShellError:
  case Parse(detail: String, column: Int)
  case TypeMismatch(command: String, expected: String, got: String)
  case MissingColumn(command: String, column: String)
  case MissingArg(command: String, argument: String)
  case BadArg(command: String, detail: String)
  case IoError(command: String, osMessage: String)
  case ExternalFailed(name: String, status: Int)

  def message: String = this match
    case Parse(detail, column)                => s"parse error: $detail at column $column"
    case TypeMismatch(command, expected, got) => s"$command: expected $expected, got $got"
    case MissingColumn(command, column)       => s"$command: column not found: $column"
    case MissingArg(command, argument)        => s"$command: missing required argument: $argument"
    case BadArg(command, detail)              => s"$command: $detail"
    case IoError(command, osMessage)          => s"$command: $osMessage"
    case ExternalFailed(name, status)         => s"$name: external command exited with status $status"
