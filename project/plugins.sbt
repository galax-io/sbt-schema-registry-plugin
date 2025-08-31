resolvers ++= Seq(
  "Confluent" at "https://packages.confluent.io/maven/",
)

addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.5.5")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
