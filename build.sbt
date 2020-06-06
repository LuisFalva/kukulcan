val projectVersion = "0.1.0-SNAPSHOT"

val repos = Seq(
  "Confluent Maven Repo" at "https://packages.confluent.io/maven/",
  Resolver.mavenLocal
)

lazy val settings = new {
  val projectScalaVersion = "2.12.11"

  val common = Seq(
    organization := "com.github.mmolimar",
    version := projectVersion,
    scalaVersion := projectScalaVersion,
    resolvers ++= repos,
    licenses := Seq("Apache License, Version 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(Developer(
      "mmolimar",
      "Mario Molina",
      "",
      url("https://github.com/mmolimar")
    )),
    compileOrder := CompileOrder.ScalaThenJava,
    javacOptions ++= Seq("-source", "11", "-target", "11", "-Xlint:unchecked"),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  val root = Seq(
    name := "kukulcan",
    publish / skip := true
  )
  val api = Seq(
    name := "kukulcan-api",
    libraryDependencies ++= dependencies.api
  )
  val repl = Seq(
    name := "kukulcan-repl",
    sourceGenerators in Compile += {
      Def.task {
        val file = (sourceManaged in Compile).value / "com" / "github" / "mmolimar" / "kukulcan" / "repl" / "BuildInfo.scala"
        IO.write(
          file,
          s"""package com.github.mmolimar.kukulcan.repl
             |private[repl] object BuildInfo {
             |  val version = "${version.value}"
             |}""".stripMargin
        )
        Seq(file)
      }.taskValue
    },
    javacOptions ++= Seq(
      "--add-modules=jdk.jshell",
      "--add-exports=jdk.jshell/jdk.internal.jshell.tool=ALL-UNNAMED",
      "--add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED"
    )
  )
  val dependencies = new {
    val kafkaVersion = "2.5.0"
    val kafkaConnectClientVersion = "3.1.0"
    val circeVersion = "0.12.3"
    val asciiGraphsVersion = "0.0.6"

    val api = Seq(
      "org.apache.kafka" %% "kafka" % kafkaVersion,
      "org.apache.kafka" % "kafka-clients" % kafkaVersion,
      "org.apache.kafka" % "kafka-tools" % kafkaVersion,
      "org.apache.kafka" %% "kafka-streams-scala" % kafkaVersion,
      "org.apache.kafka" % "kafka-streams-test-utils" % kafkaVersion,
      "org.scala-lang" % "scala-compiler" % projectScalaVersion,
      "org.sourcelab" % "kafka-connect-client" % kafkaConnectClientVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.github.mutcianm" %% "ascii-graphs" % asciiGraphsVersion
    )
  }
}

lazy val apiProject = project
  .in(file("kukulcan-api"))
  .settings(
    settings.common,
    settings.api
  )
lazy val replProject = project
  .in(file("kukulcan-repl"))
  .dependsOn(apiProject)
  .settings(
    settings.common,
    settings.repl
  )
lazy val root = project
  .in(file("."))
  .dependsOn(apiProject, replProject)
  .enablePlugins(PackPlugin)
  .settings(
    settings.common,
    settings.root,
    packExpandedClasspath := true,
    packGenerateMakefile := false
  )
  .aggregate(apiProject, replProject)
