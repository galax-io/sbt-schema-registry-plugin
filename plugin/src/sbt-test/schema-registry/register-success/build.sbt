import org.galaxio.avro.RegistryRegistration

lazy val root = (project in file("."))
  .settings(
    scalaVersion                := "2.12.21",
    schemaRegistryUrl           := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.RegisterTest", baseDirectory.value / "src/main/avro/Test.avsc"),
    ),
  )
