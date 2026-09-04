ThisBuild / scalaVersion := "3.9.0"

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
      "com.softwaremill.sttp.ai" %% "openai" % "0.11.0",
      "dev.zio" %% "zio-blocks-schema" % "0.0.51",
      "org.jline" % "jline" % "4.4.2",
      "org.scalameta" %% "munit" % "1.3.6" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
