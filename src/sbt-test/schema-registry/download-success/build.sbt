import org.galaxio.avro.RegistrySubject
import sbtavrohugger.SbtAvrohugger.autoImport._

// Download a real schema from a containerized registry through the plugin task,
// then generate Scala from it via avrohugger and compile — proving the schema is valid.
lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl          := RegistryFixture.url,
    schemaRegistrySubjects     += RegistrySubject(RegistryFixture.subject, 1),
    Compile / sourceGenerators += (Compile / avroScalaGenerate).taskValue,
  )
