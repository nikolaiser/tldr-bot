ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.2.2"

val zioVersion = "2.0.9"
val zioInteropCatsVersion = "23.0.0.1"
val telegramiumVersion = "7.65.0"

lazy val root = (project in file("."))
  .settings(
    name := "TlDrBot",
    Docker / packageName := "nikolaiser/tldr-bot",
    Docker / dockerRepository := Some("ghcr.io"),
    libraryDependencies := Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-concurrent" % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioInteropCatsVersion,
      "io.github.apimorphism" %% "telegramium-core" % telegramiumVersion,
      "io.github.apimorphism" %% "telegramium-high" % telegramiumVersion
    )
  )
  .enablePlugins(DockerPlugin, JavaAppPackaging)
