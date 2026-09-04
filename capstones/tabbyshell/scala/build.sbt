ThisBuild / scalaVersion := "3.9.0"

lazy val root = (project in file("."))
  .settings(
    name    := "tabbyshell",
    version := "0.1.0",
    run / fork         := true,
    run / connectInput := true,
    assembly / mainClass := Some("tabbyshell.Main"),
    // `run`, `verify`, and test-harness/src/runner.ts all locate the fat JAR at
    // target/scala-<scalaVersion>/tabbyshell-assembly-<version>.jar. sbt 2 moved its
    // default output under target/out/jvm/..., so pin the path the tooling expects.
    assembly / assemblyOutputPath := baseDirectory.value / "target" /
      s"scala-${scalaVersion.value}" / s"${name.value}-assembly-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "module-info.class"                  => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    },
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    Compile / compile / wartremoverErrors ++= Seq(
      Wart.Return,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.Null
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-parse" % "1.1.0",
      "org.scalameta" %% "munit"      % "1.3.6" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
