import org.galaxio.avro.RegistrySubject

// Guards the sbt 2.x task-caching behaviour: all tasks are cached by default and a cache hit
// silently skips side effects. The download task is wrapped in `Def.uncached` (no-op on sbt 1.x,
// native on sbt 2.x) so it re-executes every invocation. The `test` script proves it by deleting
// the downloaded file between two identical invocations and asserting it reappears.
//
// `schemaRegistryIncremental := false` is essential here: with incremental on (the default), the
// second download would be skipped by the plugin's OWN manifest logic (the deleted file isn't in
// the manifest as changed), which would mask — not test — sbt 2.x task caching. With incremental
// off the task always re-writes, so a skipped second run can only mean the sbt 2.x task cache
// served the result without running the body — exactly what Def.uncached must prevent.
lazy val root = (project in file("."))
  .settings(
    scalaVersion            := "2.12.21",
    schemaRegistryUrl       := RegistryFixture.url,
    schemaRegistryIncremental := false,
    schemaRegistrySubjects += RegistrySubject(RegistryFixture.subject, 1),
  )
