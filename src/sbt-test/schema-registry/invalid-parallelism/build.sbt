import org.galaxio.avro.RegistrySubject

lazy val root = (project in file("."))
  .settings(
    scalaVersion                := "2.12.21",
    schemaRegistryUrl           := RegistryFixture.url,
    schemaRegistryParallelism   := 0,
    schemaRegistrySubjects      += RegistrySubject(RegistryFixture.subjectA, 1),
  )
