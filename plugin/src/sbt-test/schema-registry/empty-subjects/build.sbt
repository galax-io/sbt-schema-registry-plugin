// No schemaRegistrySubjects configured: the task must warn and no-op, build stays green.
// No registry URL needed — with zero subjects the task returns before it is used.
lazy val root = (project in file("."))
  .settings(
    scalaVersion := "2.12.21",
  )
