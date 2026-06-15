resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/")

libraryDependencies ++= Seq(
  "org.testcontainers" % "kafka"                       % "1.21.3",
  "io.confluent"       % "kafka-json-schema-provider"  % "7.5.0",
)

sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % v)
  case None    => sys.error("plugin.version system property not set (provided by scriptedLaunchOpts)")
}
