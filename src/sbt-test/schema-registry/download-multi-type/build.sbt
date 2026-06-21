import org.galaxio.avro.{RegistryRegistration, RegistrySubject, SchemaType}

lazy val root = (project in file("."))
  .settings(
    scalaVersion                   := "2.12.21",
    schemaRegistryUrl              := RegistryFixture.url,
    schemaRegistryTargetFolder     := baseDirectory.value / "target" / "schemas",
    schemaRegistryRegistrations    := Seq(
      RegistryRegistration("it.e2e.DownloadAvro", baseDirectory.value / "src/main/avro/Test.avsc"),
      RegistryRegistration("it.e2e.DownloadProto", baseDirectory.value / "src/main/protobuf/Test.proto", SchemaType.Protobuf),
      RegistryRegistration("it.e2e.DownloadJson", baseDirectory.value / "src/main/json/Test.json", SchemaType.Json),
    ),
    schemaRegistrySubjects ++= Seq(
      RegistrySubject.latest("it.e2e.DownloadAvro"),
      RegistrySubject.latest("it.e2e.DownloadProto"),
      RegistrySubject.latest("it.e2e.DownloadJson"),
    ),
    TaskKey[Unit]("checkDownload") := {
      val dir = baseDirectory.value / "target" / "schemas"
      Seq(
        "it.e2e.DownloadAvro-1.avsc",
        "it.e2e.DownloadProto-1.proto",
        "it.e2e.DownloadJson-1.json",
      ).foreach { name =>
        val f = dir / name
        if (!f.exists()) sys.error(s"Expected file not found: $f")
      }
    },
  )
