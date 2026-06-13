resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/")

// Testcontainers on the meta-build classpath so RegistryFixture can boot a real registry.
libraryDependencies += "org.testcontainers" % "kafka" % "1.21.3"

sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % v)
  case None    => sys.error("plugin.version system property not set (provided by scriptedLaunchOpts)")
}
