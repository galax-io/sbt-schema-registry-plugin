import Dependencies.*

val sbtV = "1.12.12"

lazy val commonSettings = Seq(
  scalaVersion := "2.13.18",
  scalacOptions ++= Seq(
    "-encoding",
    "UTF-8",
    "-Xfatal-warnings",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps",
  ),
)

lazy val sbtSchemaRegistryPlugin = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings)
  .settings(
    name                          := "sbt-schema-registry-plugin",
    sbtPlugin                     := true,
    pluginCrossBuild / sbtVersion := sbtV,
    resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/"),
    libraryDependencies ++= Seq(
      schemaRegistryClient,
      scalatest    % Test,
      mockitoScala % Test,
    ),
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      "-Dplugin.version=" + version.value,
    ),
    scriptedBufferLog             := false,
  )

lazy val it = (project in file("it"))
  .dependsOn(sbtSchemaRegistryPlugin)
  .settings(commonSettings)
  .settings(
    name           := "sbt-schema-registry-plugin-it",
    publish / skip := true,
    Test / fork    := true,
    libraryDependencies ++= Seq(
      "org.scala-sbt"    %% "util-logging" % sbtV % Test,
      scalatest           % Test,
      testcontainersKafka % Test,
    ),
  )
