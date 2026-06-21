import org.galaxio.avro.RegistryRegistration

lazy val root = (project in file("."))
  .settings(
    scalaVersion                := "2.12.21",
    schemaRegistryUrl           := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.CompatPass", baseDirectory.value / "src/main/avro/V1.avsc"),
    ),
  )
