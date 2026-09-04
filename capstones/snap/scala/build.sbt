ThisBuild / scalaVersion := "3.9.0"

lazy val root = (project in file("."))
  .settings(
    name                 := "snap",
    version              := "0.1.0",
    run / fork           := true,
    run / connectInput   := true,
    assembly / mainClass := Some("snap.Main"),
    // `../run` and `../run_tests` locate the fat JAR by globbing
    // target/scala-*/*-assembly-*.jar. sbt 2 moved its default output under
    // target/out/jvm/..., so pin the path the tooling expects.
    assembly / assemblyOutputPath := baseDirectory.value / "target" /
      s"scala-${scalaVersion.value}" / s"${name.value}-assembly-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case _ => MergeStrategy.first
    },
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    Compile / compile / wartremoverErrors ++= Seq(
      Wart.Return,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.Null
    ),
    libraryDependencies ++= Seq(
      "io.circe"       %% "circe-parser"     % "0.14.16",
      "org.scalameta"  %% "munit"            % "1.3.6"  % Test,
      "org.scalameta"  %% "munit-scalacheck" % "1.3.1"  % Test,
      "org.scalacheck" %% "scalacheck"       % "1.20.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
