import org.galaxio.avro.RegistrySubject

// Run download twice — second invocation must succeed (overwrite is safe).
lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.13.18",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistrySubjects += RegistrySubject(RegistryFixture.subject, 1),
  )
