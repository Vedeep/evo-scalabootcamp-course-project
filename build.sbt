ThisBuild / scalaVersion     := "2.13.4"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"
ThisBuild / name             := "evobootcamp-black-jack"

val scalaTestVersion = "3.1.0.0-RC2"
val catsVersion = "2.2.0"
val catsEffectVersion = "2.2.0"
val http4sVersion = "0.21.22"
val circeVersion = "0.13.0"
val doobieVersion = "0.9.0"
val log4CatsVersion = "1.2.0"
val jwtVersion = "7.1.4"

lazy val root = (project in file("."))
  .settings(
    name := "black-jack-modules"
  )
  .aggregate(core, tests)

lazy val tests = (project in file("modules/tests"))
  .configs(Test)
  .settings(
    name := "black-jack-tests",
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "org.scalatestplus" %% "scalatestplus-scalacheck" % scalaTestVersion % Test
    )
  ).dependsOn(core)

lazy val core = (project in file("modules/core"))
  .settings(
    name := "black-jack",
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-blaze-server" % http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.3.6",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-optics" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-h2" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.github.jwt-scala" %% "jwt-core" % jwtVersion,

      // Tests
      "org.scalatestplus" %% "scalatestplus-scalacheck" % scalaTestVersion % Test
    )
  )
