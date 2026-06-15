import org.galaxio.avro.{RegistryRegistration, RegistrySubject}

lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.RoundTrip", baseDirectory.value / "src/main/avro/RoundTrip.avsc"),
    ),
    schemaRegistrySubjects += RegistrySubject.latest("it.e2e.RoundTrip"),
    TaskKey[Unit]("checkRoundTrip") := {
      val original   = IO.read(baseDirectory.value / "src/main/avro/RoundTrip.avsc").trim
      val downloaded = IO.read(baseDirectory.value / "src/main/avro/it.e2e.RoundTrip-1.avsc").trim
      if (original != downloaded)
        sys.error(s"Content mismatch:\n  original:   $original\n  downloaded: $downloaded")
    },
  )
