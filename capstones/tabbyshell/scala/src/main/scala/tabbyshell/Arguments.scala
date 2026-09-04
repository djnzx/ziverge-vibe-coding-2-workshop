package tabbyshell

/** Decodes a parsed command into positionals and flags for builtin functions.
  *
  * The decoder preserves the parser's lexical distinctions rather than reducing every argument to a string. That makes command-specific grammar rules, such as
  * `where` requiring a bare column identifier, enforceable without reparsing source text.
  */
final case class Arguments private (
  commandName: String,
  positionals: Vector[Arg],
  flags: Vector[Arg.Flag]
):

  def required(index: Int, argument: String): Either[ShellError, Arg] =
    positionals.lift(index).toRight(ShellError.MissingArg(commandName, argument))

  def hasFlag(names: String*): Boolean =
    flags.exists(flag => names.contains(flag.name))

  def bareIdentifier(index: Int, argument: String): Either[ShellError, String] =
    required(index, argument).flatMap:
      case Arg.BareIdent(value) => Right(value)
      case _                    => Left(ShellError.BadArg(commandName, s"expected bare identifier for $argument"))

  def columnName(index: Int, argument: String): Either[ShellError, String] =
    required(index, argument).flatMap:
      case Arg.BareIdent(value)            => Right(value)
      case Arg.Literal(Literal.Str(value)) => Right(value)
      case _                               => Left(ShellError.BadArg(commandName, s"expected column name for $argument"))

  def operator(index: Int, argument: String): Either[ShellError, String] =
    required(index, argument).flatMap:
      case Arg.Operator(value) => Right(value)
      case _                   => Left(ShellError.BadArg(commandName, s"expected comparison operator for $argument"))

  def literal(index: Int, argument: String): Either[ShellError, Literal] =
    required(index, argument).flatMap:
      case Arg.Literal(value) => Right(value)
      case _                  => Left(ShellError.BadArg(commandName, s"expected literal for $argument"))

object Arguments:

  def from(command: Command): Arguments =
    val (positionals, flags) = command.args.foldLeft((Vector.empty[Arg], Vector.empty[Arg.Flag])) {
      case ((args, foundFlags), flag @ Arg.Flag(_, _)) => (args, foundFlags :+ flag)
      case ((args, foundFlags), argument)              => (args :+ argument, foundFlags)
    }
    Arguments(command.name, positionals, flags)
