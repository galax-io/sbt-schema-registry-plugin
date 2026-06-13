resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/")

sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % v)
  case None    => sys.error("plugin.version system property not set (provided by scriptedLaunchOpts)")
}
