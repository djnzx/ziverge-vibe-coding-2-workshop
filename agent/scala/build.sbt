ThisBuild / scalaVersion := "3.3.5"

val sttpOpenAiVersion = "0.4.11"

lazy val root = (project in file("."))
  .settings(
    name := "foundations-agent-scala",
    run / fork := true,
    run / connectInput := true,
    assembly / mainClass := Some("workshop.agent.main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case "module-info.class"                   => MergeStrategy.discard
      case x                                     => MergeStrategy.first
    },
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    Compile / compile / wartremoverErrors ++= Seq(
      Wart.Return,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.Null
    ),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.ai" %% "openai" % sttpOpenAiVersion,
      "dev.zio" %% "zio-blocks-schema" % "0.0.34",
      "org.jline" % "jline" % "3.27.0",
      "org.scalameta" %% "munit" % "1.1.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
