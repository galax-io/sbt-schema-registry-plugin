resolvers ++= Seq(
  "Confluent" at "https://packages.confluent.io/maven/",
)

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")
// sbt-ci-release 1.12.0 dropped its sbt-git dependency (sbt/sbt-ci-release#471); publish.sbt needs it
addSbtPlugin("com.github.sbt" % "sbt-git"        % "2.1.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.6.2")
