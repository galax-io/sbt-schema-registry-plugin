// No schemaRegistrySubjects configured: the task must warn and no-op, build stays green.
lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl := "http://127.0.0.1:1",
  )
