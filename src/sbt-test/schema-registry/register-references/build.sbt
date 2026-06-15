import org.galaxio.avro.{RegistryRegistration, SchemaReference}

lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.Base", baseDirectory.value / "src/main/avro/Base.avsc"),
      RegistryRegistration(
        "it.e2e.Dependent",
        baseDirectory.value / "src/main/avro/Dependent.avsc",
        references = List(SchemaReference("Base", "it.e2e.Base", 1)),
      ),
    ),
  )
