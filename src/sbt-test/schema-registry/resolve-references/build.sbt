import org.galaxio.avro.{RegistryRegistration, SchemaReference}

// Register a base schema and a dependent schema that references it, then download ONLY the
// dependent (selected via a regex pattern, not an exact subject). Reference resolution must
// transitively pull in the base schema — proving FR-009 composes with wildcard expansion.
// Incremental is disabled so each download re-fetches deterministically (the opt-out step
// deletes files and re-downloads).
lazy val root = (project in file("."))
  .settings(
    scalaVersion              := "2.12.21",
    schemaRegistryUrl         := RegistryFixture.url,
    schemaRegistryIncremental := false,
    schemaRegistryRegistrations := Seq(
      RegistryRegistration("it.e2e.Base", baseDirectory.value / "src/main/avro/Base.avsc"),
      RegistryRegistration(
        "it.e2e.Dependent",
        baseDirectory.value / "src/main/avro/Dependent.avsc",
        references = List(SchemaReference("Base", "it.e2e.Base", 1)),
      ),
    ),
    schemaRegistrySubjectPatterns := Seq("it\\.e2e\\.Dependent"),
  )
