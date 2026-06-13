import org.galaxio.avro.RegistrySubject

// Registry is unreachable (port 1): the download must fail and fail the build.
lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl      := "http://127.0.0.1:1",
    schemaRegistrySubjects += RegistrySubject("does-not-matter", 1),
  )
