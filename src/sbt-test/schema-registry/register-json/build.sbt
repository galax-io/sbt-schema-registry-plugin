import org.galaxio.avro.{RegistryRegistration, SchemaType}

lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration(
        "it.e2e.RegisterJsonTest",
        baseDirectory.value / "src/main/json/Test.json",
        SchemaType.Json,
      ),
    ),
  )
