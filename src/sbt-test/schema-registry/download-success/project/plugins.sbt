resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/")

// Testcontainers on the meta-build classpath so RegistryFixture can boot a real registry.
libraryDependencies += "org.testcontainers" % "kafka" % "1.21.3"

// Real Avro codegen — proves the downloaded schema is valid by generating + compiling Scala from it.
addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.17.0")

sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % v)
  case None    => sys.error("plugin.version system property not set (provided by scriptedLaunchOpts)")
}
