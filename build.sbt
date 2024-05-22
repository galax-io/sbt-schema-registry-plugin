import Dependencies.*

lazy val sbtSchemaRegistryPlugin = (project in file("."))
  .enablePlugins(SbtPlugin, GitVersioning)
  .settings(
    name                          := "sbt-schema-registry-plugin",
    scalaVersion                  := "2.12.19",
    sbtPlugin                     := true,
    pluginCrossBuild / sbtVersion := "1.9.9",
    resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/"),
    libraryDependencies ++= Seq(schemaRegistryClient),
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      "-Dplugin.version=" + version.value,
    ),
    scriptedBufferLog             := false,
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",            // Option and arguments on same line
      "-Xfatal-warnings", // New lines for each options
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-language:existentials",
      "-language:postfixOps",
    ),
  )
