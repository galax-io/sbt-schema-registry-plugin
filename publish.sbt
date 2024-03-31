ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / organization  := "org.galaxio"
ThisBuild / scmInfo       := Some(
  ScmInfo(
    url("https://github.com/galax-io/sbt-schema-registry-plugin"),
    "git@github.com:galax-io/sbt-schema-registry-plugin.git",
  ),
)

ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / description   := "Sbt plugin for download schemas from schema registry"
ThisBuild / homepage      := Some(url("https://github.com/galax-io/sbt-schema-registry-plugin"))
