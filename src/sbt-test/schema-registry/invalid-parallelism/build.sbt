import org.galaxio.avro.RegistrySubject

lazy val root = (project in file("."))
  .settings(
    scalaVersion              := "2.12.21",
    schemaRegistryUrl         := "http://127.0.0.1:1",
    schemaRegistryParallelism := 0,
    schemaRegistrySubjects    += RegistrySubject("does-not-matter", 1),
  )
