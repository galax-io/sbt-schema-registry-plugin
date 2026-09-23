resolvers ++= Seq(
  "Confluent" at "https://packages.confluent.io/maven/",
)

addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.12.1")
// sbt-ci-release 1.12.0 dropped its sbt-git dependency (sbt/sbt-ci-release#471); publish.sbt needs it
addSbtPlugin("com.github.sbt" % "sbt-git"         % "2.2.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.6.2")
addSbtPlugin("org.scoverage"  % "sbt-scoverage"   % "2.4.4")
addSbtPlugin("com.typesafe"   % "sbt-mima-plugin" % "1.2.1")
