// dependsOn hook with zero subjects: compile must succeed (download warns, no-op).
lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.13.18",
    Compile / compile := (Compile / compile).dependsOn(Compile / schemaRegistryDownload).value,
  )
