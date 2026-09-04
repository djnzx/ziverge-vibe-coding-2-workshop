package tabbyshell

/** Public domain model supplied to attendees; behavior is defined by SPEC.md. */
enum Value:
  case Null
  case Bool(value: Boolean)
  case Int(value: Long)
  case Float(value: Double)
  case Str(value: String)
  case Filesize(bytes: Long)
  case Date(seconds: Long)
  case List(items: Vector[Value])
  case Record(fields: Vector[(String, Value)])
  case Table(columns: Vector[String], rows: Vector[Vector[Value]])

/** A literal argument token (SPEC 3.1). TypeScript folds Int and Float into `number`;
  * Scala keeps them apart because `5` parses as Int and `5.0` as Float.
  */
enum Literal:
  case Str(value: String)
  case Int(value: Long)
  case Float(value: Double)
  case Bool(value: Boolean)
  case Filesize(bytes: Long)

/** Bare idents and comparison operators surface to commands as `Lit(Literal.Str(...))`. */
enum Arg:
  case Lit(value: Literal)
  case Flag(name: String)

final case class Command(name: String, args: Vector[Arg])
final case class Pipeline(commands: Vector[Command])
final case class ShellState(cwd: String, now: Long, color: Boolean, env: Map[String, String])
