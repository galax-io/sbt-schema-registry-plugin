// No subjects — task succeeds (warns) when scoped correctly.
lazy val root = (project in file("."))
  .settings(
    scalaVersion := "2.13.18",
  )
