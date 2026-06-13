import org.galaxio.avro.RegistrySubject

// Download via RegistrySubject.latest — file must have resolved version number, not "latest".
lazy val root = (project in file("."))
  .settings(
    scalaVersion           := "2.12.21",
    schemaRegistryUrl      := RegistryFixture.url,
    schemaRegistrySubjects += RegistrySubject.latest(RegistryFixture.subject),
  )
