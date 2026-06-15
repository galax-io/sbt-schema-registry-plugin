import org.galaxio.avro.{RegistryRegistration, SchemaType}

lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.12.21",
    schemaRegistryUrl := RegistryFixture.url,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration(
        "it.e2e.RegisterProtobufTest",
        baseDirectory.value / "src/main/protobuf/Test.proto",
        SchemaType.Protobuf,
      ),
    ),
  )
