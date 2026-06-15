import org.galaxio.avro.RegistrySubject

// Incremental download: second run should skip unchanged schemas.
lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistrySubjects += RegistrySubject(RegistryFixture.subject, 1),
  )
