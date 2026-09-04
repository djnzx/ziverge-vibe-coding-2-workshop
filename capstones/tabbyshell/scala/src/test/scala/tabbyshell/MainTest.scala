package tabbyshell

import java.nio.file.Path

class MainTest extends munit.FunSuite:

  test("state initialization freezes valid TABBY_NOW and resolves color precedence"):
    val state = Main.initialState(
      Map("HOME" -> "/tmp/tabby-home", "TABBY_NOW" -> "1700000000", "NO_COLOR" -> "1"),
      Path.of("."),
      stdoutIsTty = true,
      noColor = false
    )

    assertEquals(state.now, 1700000000L)
    assert(!state.color)
    assert(Path.of(state.cwd).isAbsolute)
    assert(Path.of(state.home).isAbsolute)

  test("explicit no-color takes precedence over a TTY"):
    val state = Main.initialState(Map.empty, Path.of("."), stdoutIsTty = true, noColor = true)

    assert(!state.color)
