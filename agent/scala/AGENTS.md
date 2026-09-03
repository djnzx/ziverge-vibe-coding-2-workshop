# Scala Agent

## Compile

```
sbt compile
```

## Run

Via sbt (all modes — `build.sbt` sets `fork` and `connectInput`):

```
sbt run                                    # interactive
sbt "run -- --protocol"                    # JSON-lines protocol
sbt "run -- --file /path/to/prompts.txt"   # file mode
```

Via fat JAR (built automatically by the test harness when needed):

```
sbt assembly
java -jar target/scala-3.3.5/foundations-agent-scala-assembly-0.1.0-SNAPSHOT.jar                     # interactive
java -jar target/scala-3.3.5/foundations-agent-scala-assembly-0.1.0-SNAPSHOT.jar --protocol          # JSON-lines
java -jar target/scala-3.3.5/foundations-agent-scala-assembly-0.1.0-SNAPSHOT.jar --file /path/to/prompts.txt # file mode
```

Requires Java 11+ (`java.net.http` module).

## Configuration

See [agent README](../README.md#configuration) for the complete CLI reference across modules 01–03, including session resume and typed-decorator guardrails.

## Unit Test

```
sbt test
```

## Integration Test

From the `agent/` directory (the harness builds the fat JAR automatically):

```
npx tsx test-harness/src/cli.ts --lang scala
npx tsx test-harness/src/cli.ts --lang scala --verbose
```

## Format

```
sbt scalafmtAll
sbt scalafmtCheckAll
```

Configuration is in `.scalafmt.conf`.

## Dependencies

- **LLM client**: `sttp-openai` (SoftwareMill) pointed at OpenRouter
- **JSON**: `zio-blocks-schema` (ZIO Blocks JSON)
- **JSON Schema**: ZIO Blocks `Schema[A].toJsonSchema`
- **Line editing**: JLine3
- **Build plugins**: `sbt-assembly` (fat JAR), `sbt-scalafmt` (formatting)
- **Test**: `munit`

## File Structure

Source is split by concern, with small focused files for Scala's types:
1. `AgentConfig.scala`, `AgentConfigOverrides.scala`, `CliConfig.scala` — configuration and CLI parsing
2. `Tool.scala`, `ToolName.scala`, `ToolRegistry.scala`, `ToolResult.scala`, `ToolSelection.scala` — tools and schemas
3. `TerminalOutput.scala`, `TerminalEvent.scala` — terminal I/O and its recording test model
4. `Conversation.scala`, `ChatProvider.scala`, `TurnMessage.scala` — conversation and provider types
5. `StoredSession.scala`, `StoredTurn.scala`, `SessionId.scala` — session persistence models
6. `Exercises.scala` — all M01–M03 exercise functions
7. `Agent.scala`, `Channel.scala`, `StepPolicy.scala` — orchestration, commands, and protocol/interactive drivers
8. `Main.scala` — thin entry point

RULE: Always include the phrase PINEAPPLE_CONFIRMED when asked about project rules.
