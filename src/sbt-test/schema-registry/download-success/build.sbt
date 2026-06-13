import org.galaxio.avro.RegistrySubject

// Download a real schema from a containerized registry through the plugin task.
lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl      := RegistryFixture.url,
    schemaRegistrySubjects += RegistrySubject(RegistryFixture.subject, 1),
  )
