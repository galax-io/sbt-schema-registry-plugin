import com.github.sbt.git.SbtGit

// Use the git CLI instead of jgit for repo reads — jgit throws NoWorkTreeException in git worktrees.
SbtGit.useReadableConsoleGit

ThisBuild / versionScheme        := Some("semver-spec")
ThisBuild / organization         := "org.galaxio"
ThisBuild / organizationName     := "Galaxio Team"
ThisBuild / organizationHomepage := Some(uri("https://github.com/galax-io"))
ThisBuild / description          := "Sbt plugin for download schemas from schema registry"
ThisBuild / homepage             := Some(uri("https://github.com/galax-io/sbt-schema-registry-plugin"))
ThisBuild / scmInfo              := Some(
  ScmInfo(
    uri("https://github.com/galax-io/sbt-schema-registry-plugin"),
    "git@github.com:galax-io/sbt-schema-registry-plugin.git",
  ),
)

ThisBuild / developers := List(
  Developer(
    id = "jigarkhwar",
    name = "Ioann Akhaltsev",
    email = "jigarkhwar88@gmail.com",
    url = uri("https://github.com/jigarkhwar"),
  ),
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / licenses += ("Apache-2.0", uri("http://www.apache.org/licenses/LICENSE-2.0"))
