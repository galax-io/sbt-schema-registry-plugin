ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / organization  := "org.galaxio"
ThisBuild / scmInfo       := Some(
  ScmInfo(
    url("https://github.com/galax-io/sbt-schema-registry-plugin"),
    "git@github.com:galax-io/sbt-schema-registry-plugin.git",
  ),
)

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / description   := "Sbt plugin for download schemas from schema registry"
ThisBuild / homepage      := Some(url("https://github.com/galax-io/sbt-schema-registry-plugin"))
