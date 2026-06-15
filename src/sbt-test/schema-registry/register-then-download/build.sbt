import org.galaxio.avro.{RegistryRegistration, RegistrySubject}

lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.RoundTrip", baseDirectory.value / "src/main/avro/RoundTrip.avsc"),
    ),
    schemaRegistrySubjects += RegistrySubject.latest("it.e2e.RoundTrip"),
  )
