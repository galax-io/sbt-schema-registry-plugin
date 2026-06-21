import org.galaxio.avro.RegistrySubject

lazy val root = (project in file("."))
  .settings(
    scalaVersion              := "2.12.21",
    schemaRegistryUrl         := RegistryFixture.url,
    schemaRegistryParallelism := 1,
    schemaRegistrySubjects ++= Seq(
      RegistrySubject(RegistryFixture.subjectA, 1),
      RegistrySubject.latest(RegistryFixture.subjectB),
    ),
  )
