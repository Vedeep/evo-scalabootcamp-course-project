ThisBuild / scalaVersion     := "2.13.4"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"
ThisBuild / name             := "evobootcamp-black-jack"

val scalaTestVersion = "3.1.0.0-RC2"
val catsVersion = "2.2.0"
val catsEffectVersion = "2.2.0"
val akkaVersion = "2.5.31"
val akkaHttpVersion = "10.1.11"
val akkaHttpCirceVersion = "1.31.0"
val http4sVersion = "0.21.22"
val circeVersion = "0.13.0"
val doobieVersion = "0.9.0"

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
      "org.scalatestplus" %% "scalatestplus-scalacheck" % scalaTestVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
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
//      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
//      "de.heikoseeberger" %% "akka-http-circe" % akkaHttpCirceVersion,
//      "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
//      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
//      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
//      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
//      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
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
      // Tests
      "org.scalatestplus" %% "scalatestplus-scalacheck" % scalaTestVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

      "com.evolutiongaming" %% "akka-effect-actor" % "0.1.0",
      "com.evolutiongaming" %% "akka-effect-persistence" % "0.1.0"
    )
  )
