package snap

/** Snap's entry point.
  *
  * This file sits directly under `src/main/scala/` rather than beside the rest of the package in `src/main/scala/snap/`, because `../run` decides whether a
  * Scala implementation exists by testing exactly `src/main/scala/Main.scala` and reads its mtime to pick the freshest implementation when `--lang` is omitted.
  * Moving it into the package directory makes `../run --lang scala` and `../verify --lang scala` report that Scala is unavailable.
  *
  * Keep this file thin: argument grammar lives in [[Cli]].
  */
object Main:
  def main(args: Array[String]): Unit =
    val status = Cli.run(args.toVector, Streams.standard)
    Streams.standard.flush()
    sys.exit(status)
