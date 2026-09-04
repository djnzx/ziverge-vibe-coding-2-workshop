package tabbyshell

class CliTest extends munit.FunSuite:

  test("Decline parses each supported application mode"):
    assertEquals(Cli.parse(Seq("--eval", "pwd")), Right(CliOptions(CliMode.Eval("pwd"), noColor = false)))
    assertEquals(Cli.parse(Seq("--eval-file", "-", "--no-color")), Right(CliOptions(CliMode.EvalFile("-"), noColor = true)))
    assertEquals(Cli.parse(Seq("--interactive")), Right(CliOptions(CliMode.Interactive, noColor = false)))
    assertEquals(Cli.parse(Seq("--version")), Right(CliOptions(CliMode.Version, noColor = false)))

  test("no application-mode flag defaults to the interactive REPL"):
    assertEquals(Cli.parse(Seq("--no-color")), Right(CliOptions(CliMode.Interactive, noColor = true)))

  test("version takes precedence over the evaluator flags injected by the harness"):
    assertEquals(
      Cli.parse(Seq("--no-color", "--eval-file", "-", "--version")),
      Right(CliOptions(CliMode.Version, noColor = true))
    )

  test("application modes are mutually exclusive"):
    assertEquals(Cli.parse(Seq("--eval", "pwd", "--interactive")), Left("tabbyshell: application modes are mutually exclusive"))

  test("help and unsupported flags are rejected"):
    assert(Cli.parse(Seq("--help")).isLeft)
    assert(Cli.parse(Seq("--unexpected")).isLeft)
