lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistrySubjectPatterns += "it\\.e2e\\..*",
  )
