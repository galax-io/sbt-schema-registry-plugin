import org.galaxio.avro.RegistrySubject

// Override target folder — schemas must land in custom-avro/, not src/main/avro/.
lazy val root = (project in file("."))
  .settings(
    scalaVersion               := "2.13.18",
    schemaRegistryUrl          := RegistryFixture.url,
    schemaRegistrySubjects += RegistrySubject(RegistryFixture.subject, 1),
    schemaRegistryTargetFolder := baseDirectory.value / "custom-avro",
  )
