import org.galaxio.avro.RegistrySubject

// Hook download into compile via dependsOn.
// Registry is unreachable — if compile fails, the hook fired.
lazy val root = (project in file("."))
  .settings(
    scalaVersion      := "2.13.18",
    schemaRegistryUrl := "http://127.0.0.1:1",
    schemaRegistrySubjects += RegistrySubject("hook-test", 1),
    Compile / compile := (Compile / compile).dependsOn(Compile / schemaRegistryDownload).value,
  )
