import org.galaxio.avro.RegistrySubject

// Download two schemas in one task run — both files must be present.
lazy val root = (project in file("."))
  .settings(
    scalaVersion       := "2.12.21",
    schemaRegistryUrl  := RegistryFixture.url,
    schemaRegistrySubjects ++= Seq(
      RegistrySubject(RegistryFixture.subjectA, 1),
      RegistrySubject(RegistryFixture.subjectB, 1),
    ),
  )
