ThisBuild / versionScheme        := Some("semver-spec")
ThisBuild / organization         := "org.galaxio"
ThisBuild / organizationName     := "Galaxio Team"
ThisBuild / organizationHomepage := Some(url("https://github.com/galax-io"))
ThisBuild / description          := "Sbt plugin for download schemas from schema registry"
ThisBuild / homepage             := Some(url("https://github.com/galax-io/sbt-schema-registry-plugin"))
ThisBuild / scmInfo              := Some(
  ScmInfo(
    url("https://github.com/galax-io/sbt-schema-registry-plugin"),
    "git@github.com:galax-io/sbt-schema-registry-plugin.git",
  ),
)

ThisBuild / developers := List(
  Developer(
    id = "jigarkhwar",
    name = "Ioann Akhaltsev",
    email = "jigarkhwar88@gmail.com",
    url = url("https://github.com/jigarkhwar"),
  ),
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
