resolvers ++= Seq(
  Resolver.bintrayIvyRepo("rallyhealth", "sbt-plugins"),
  "Confluent" at "https://packages.confluent.io/maven/",
)

addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.5.0")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.11")
