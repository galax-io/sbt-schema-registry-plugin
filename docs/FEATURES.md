# sbt-schema-registry-plugin — Feature Catalog

An sbt plugin that wires a Confluent Schema Registry into your build: download,
register, and compatibility-check **Avro / Protobuf / JSON Schema** schemas as
ordinary sbt tasks. Cross-built from one source tree for **sbt 1.x (Scala 2.12)**
and **sbt 2.x (Scala 3)** with identical public keys.

Each feature below maps to a single-responsibility class. Every link points at real
source on `main`; line numbers and PRs were verified against the repository.

Source root: [`src/main/scala/org/galaxio/avro/`](https://github.com/galax-io/sbt-schema-registry-plugin/tree/main/src/main/scala/org/galaxio/avro)

---

## 0. Basic Avro download (pre-specs)

**What:** Downloads schemas from the registry for a list of subjects and writes each
to disk. The subject name is validated; the body comes from a pinned version or
`latest`; the file extension is driven by the schema type.

[`Downloader.schemaSubjectToFile`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/Downloader.scala#L22)

```scala
def schemaSubjectToFile(subject: RegistrySubject): Either[DownloadError, Path] =
  for {
    _                  <- validateSubjectName(subject.name)
    resolved           <- fetchSchema(subject)
    (version, body, st) = resolved
    path               <- writeSchema(subject.name, version, body, st)
  } yield path
```

**Use:** set `schemaRegistryUrl` + `schemaRegistrySubjects` + `schemaRegistryTargetFolder`,
then run `schemaRegistryDownload`. Subjects are declared as
`RegistrySubject("name", version)` (pinned) or `RegistrySubject.latest("name")`.

---

## 1. Register (push) schemas — spec `001-register-schemas` · [PR #46](https://github.com/galax-io/sbt-schema-registry-plugin/pull/46)

**What:** Registers local schema files in the registry. Reads the file, builds the
`ParsedSchema` of the right type (references included), registers it under the
subject, and returns the assigned schema ID.

[`Registrar.registerAll`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/Registrar.scala#L96)

```scala
registrations.map { reg =>
  for {
    content  <- readSchemaFile(reg)
    parsed   <- buildParsedSchema(reg.subject, content, reg.schemaType, reg.references)
    schemaId <- Try(client.register(reg.subject, parsed)).toEither.left
                  .map(RegistryError.RegistrationFailed(reg.subject, _))
  } yield RegisteredSchema(reg.subject, schemaId)
}
```

**Use:** set `schemaRegistryRegistrations` (subject + file), then run `schemaRegistryRegister`.

---

## 2. Compatibility check — spec `002-schema-compatibility-check` · [PR #47](https://github.com/galax-io/sbt-schema-registry-plugin/pull/47)

**What:** Checks each local schema against the current registry version *before*
deploy and collects any incompatibility messages. Breaking changes surface early;
the plugin task fails the build (`sys.error`) on a non-success report.

[`CompatibilityChecker.checkOne`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/CompatibilityChecker.scala#L10)

```scala
val result = for {
  content  <- Registrar.readSchemaFile(reg)
  parsed   <- Registrar.buildParsedSchema(reg.subject, content, reg.schemaType, reg.references)
  messages <- Try(client.testCompatibilityVerbose(reg.subject, parsed).asScala.toList).toEither.left
                .map(RegistryError.CompatibilityCheckFailed(reg.subject, _))
} yield messages

result match {
  case Right(Nil)      => CompatibilityResult.Compatible(reg.subject)
  case Right(messages) => CompatibilityResult.Incompatible(reg.subject, messages)
  case Left(err)       => CompatibilityResult.Failed(reg.subject, err)
}
```

**Use:** configure `schemaRegistryRegistrations`, then run `schemaRegistryTestCompatibility`.

---

## 3. Protobuf + JSON Schema — spec `003-multi-schema-types` · [PR #49](https://github.com/galax-io/sbt-schema-registry-plugin/pull/49)

**What:** Not only Avro. Each type knows its file extension (`.avsc` / `.proto` /
`.json`) and its registry label (`AVRO` / `PROTOBUF` / `JSON`). Download writes the
right extension; register publishes the right type.

[`SchemaType`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/SchemaType.scala#L3)

```scala
sealed abstract class SchemaType(val extension: String, val registryLabel: String)
  extends Product with Serializable

object SchemaType {
  case object Avro     extends SchemaType("avsc", "AVRO")
  case object Protobuf extends SchemaType("proto", "PROTOBUF")
  case object Json     extends SchemaType("json", "JSON")

  def fromExtension(ext: String): Either[RegistryError, SchemaType] =
    byExtension.get(ext).toRight(RegistryError.UnsupportedSchemaType(ext))
}
```

**Use:** the type is inferred from the file extension in `schemaRegistryRegistrations`
(or set explicitly); all three tasks behave uniformly across types.

---

## 4. Wildcard download (regex) — spec `004-wildcard-subject-download` · [PR #50](https://github.com/galax-io/sbt-schema-registry-plugin/pull/50)

**What:** Declare subjects by regex pattern instead of by name. The plugin lists all
subjects from the registry, filters by the compiled patterns, fetches the matches at
`latest`, and de-duplicates against any exact subjects.

[`SubjectResolver.matchSubjects`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/SubjectResolver.scala#L41)

```scala
private def matchSubjects(
    client: SchemaRegistryClient,
    compiled: List[scala.util.matching.Regex],
): Either[DownloadError, List[RegistrySubject]] =
  Try(client.getAllSubjects.asScala.toList).toEither.left
    .map(e => DownloadError.SubjectListFailed(e))
    .map { allNames =>
      allNames
        .filter(name => compiled.exists(_.pattern.matcher(name).matches()))
        .map(RegistrySubject.latest)
    }
```

**Use:** `schemaRegistrySubjectPatterns += "it\\.e2e\\..*"`, then run `schemaRegistryDownload`.

---

## 5. Incremental download — spec `005-incremental-download` · [PR #51](https://github.com/galax-io/sbt-schema-registry-plugin/pull/51)

**What:** Skips unchanged schemas. Versions of previously downloaded subjects are kept
in a manifest; for each subject the pinned/latest version is compared with the cached
one — only new or changed schemas are fetched.

[`IncrementalResolver.plan`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/IncrementalResolver.scala#L5)

```scala
subjects.map {
  case s @ RegistrySubject.Pinned(name, version) =>
    manifest.versionOf(name) match {
      case Some(`version`) => DownloadDecision.Skip(name, version)
      case _               => DownloadDecision.Download(s, s"pinned v$version, not cached", Some(version))
    }
  case s @ RegistrySubject.Latest(name) =>
    registryVersions(name) match {
      case Right(v) if manifest.versionOf(name).contains(v) => DownloadDecision.Skip(name, v)
      case Right(v)                                         => DownloadDecision.Download(s, "...", Some(v))
      case Left(_)                                          => DownloadDecision.Download(s, "version check failed, re-downloading")
    }
}
```

**Use:** `schemaRegistryIncremental := true` (the default) — applied automatically in
`schemaRegistryDownload`. The manifest is `.schema-versions.json` under the sbt cache.

---

## 6. Parallel downloads — spec `006-parallel-schema-downloads` · [PR #52](https://github.com/galax-io/sbt-schema-registry-plugin/pull/52)

**What:** Downloads schemas concurrently with a bounded number of in-flight requests;
input order is preserved. Faster on large sets without hammering the registry.

> Note: `BoundedParallel` is *purely* bounded concurrency. Retry with exponential
> backoff lives separately in `RetryPolicy` / `ParallelDownloader`, not in this object.

[`BoundedParallel.traverse`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/BoundedParallel.scala#L19)

```scala
def traverse[A, B](items: List[A], parallelism: Int)(f: A => B): List[B] =
  if (items.isEmpty) Nil
  else if (parallelism <= 1) items.map(f)
  else {
    val pool = Executors.newFixedThreadPool(math.min(parallelism, items.size))
    try {
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      Await.result(Future.traverse(items)(a => Future(f(a))), AwaitTimeout)
    } finally pool.shutdownNow()
  }
```

**Use:** `schemaRegistryParallelism := 4` (1 = sequential, default 4) +
`schemaRegistryRetries := 3` (default 3), then run `schemaRegistryDownload`.

---

## 7. Resolve references (transitive) — spec `007-resolve-schema-references` · [PR #54](https://github.com/galax-io/sbt-schema-registry-plugin/pull/54)

**What:** Auto-downloads every schema a fetched schema references, recursively.
A breadth-first traversal (BFS) with cycle/duplicate protection. You get not just the
requested subjects but every dependent schema needed to compile.

[`ReferenceResolver.resolve`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/ReferenceResolver.scala#L36)

```scala
case Some(((subject, version), rest)) =>
  fetch(subject, version) match {
    case Left(err)       => Left(err) // fail-fast (FR-008)
    case Right(resolved) =>
      val resolvedKey = (resolved.subject, resolved.version)
      if (visited.contains(resolvedKey)) loop(rest, enqueued, visited, acc)
      else {
        val newChildren = resolved.references
          .map(r => (r.subject, Some(r.version))).distinct
          .filterNot { case (s, v) => enqueued.contains((s, v)) || v.exists(ver => visited.contains((s, ver))) }
        loop(newChildren.foldLeft(rest)(_.enqueue(_)), enqueued ++ newChildren,
             visited + resolvedKey, acc :+ RegistrySubject.Pinned(resolved.subject, resolved.version))
      }
  }
```

**Use:** `schemaRegistryResolveReferences := true` (the default) — runs inside
`schemaRegistryDownload`, between wildcard expansion and the incremental-skip check.

---

## 8. List subjects — spec `008-list-subjects-task` · [PR #55](https://github.com/galax-io/sbt-schema-registry-plugin/pull/55)

**What:** A discovery / debug task. Connects to the registry and prints every subject
with its version range and compatibility level. Optional case-insensitive substring
filter on the name.

[`SubjectExplorer.listAll`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/src/main/scala/org/galaxio/avro/SubjectExplorer.scala#L16)

```scala
def listAll(
    client: SchemaRegistryClient,
    filter: Option[String],
    parallelism: Int = 1,
): Either[DownloadError, SubjectListing] =
  for {
    names <- Try(client.getAllSubjects.asScala.toList.sorted).toEither.left
               .map(e => DownloadError.SubjectListFailed(e))
    infos <- fetchInfos(client, filterNames(names, filter), parallelism)
  } yield SubjectListing(infos)
```

**Use:** run `schemaRegistryListSubjects`; optionally `schemaRegistrySubjectFilter := Some("...")`.

---

## 9. Cross-build for sbt 2.x — spec `009-sbt2-cross-build` · [PR #59](https://github.com/galax-io/sbt-schema-registry-plugin/pull/59)

**What:** One source tree → two axes: Scala 2.12 → sbt 1.x (artifact `_2.12_1.0`) and
Scala 3.8.x → sbt 2.x (artifact `_sbt2_3`). sbt-2 users get the same plugin with the
same public keys. A `PluginCompat` seam hides sbt-version-only API differences (e.g.
`Def.uncached`).

[`build.sbt`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/build.sbt#L18)

```scala
def sbtLineFor(scalaBinVersion: String): String =
  if (scalaBinVersion.startsWith("2.")) sbt1 else sbt2

lazy val commonSettings = Seq(
  scalaVersion       := scala212,
  crossScalaVersions := Seq(scala212, scala3),
)

lazy val sbtSchemaRegistryPlugin = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings)
  .settings(
    sbtPlugin                     := true,
    pluginCrossBuild / sbtVersion := sbtLineFor(scalaBinaryVersion.value),
  )
```

**Use:** both axes — `sbt +compile +test`; sbt-2 only — `sbt ++3.8.4 compile`. Plugin
users configure nothing extra: just `addSbtPlugin` in `project/plugins.sbt`.

---

## Public keys reference

> Compatibility-sensitive: these names and types are stable across both sbt axes.

### Task keys (`taskKey[Unit]`)

| Key | Does |
| --- | --- |
| `schemaRegistryDownload` | Download schemas from the registry |
| `schemaRegistryRegister` | Register / push schemas to the registry |
| `schemaRegistryTestCompatibility` | Check compatibility; fails the build on a breaking change |
| `schemaRegistryListSubjects` | List subjects with version range + compatibility level |

### Setting keys

| Key | Type | Notes |
| --- | --- | --- |
| `schemaRegistryUrl` | `String` | Registry URL (required) |
| `schemaRegistryTargetFolder` | `File` | Output dir for downloads (default `src/main/avro`) |
| `schemaRegistrySubjects` | `Seq[RegistrySubject]` | Explicit subjects (pinned / latest) |
| `schemaRegistryRegistrations` | `Seq[RegistryRegistration]` | Schemas to register |
| `schemaRegistryCacheSize` | `Int` | Client cache size |
| `schemaRegistryAuth` | `Option[SchemaRegistryAuth]` | Credentials |
| `schemaRegistryProperties` | `Map[String, String]` | Extra Confluent client properties |
| `schemaRegistrySubjectPatterns` | `Seq[String]` | Regex patterns for wildcard download |
| `schemaRegistryIncremental` | `Boolean` | Skip unchanged schemas (default `true`) |
| `schemaRegistryParallelism` | `Int` | Bounded download concurrency (default `4`) |
| `schemaRegistryRetries` | `Int` | Download retry attempts (default `3`) |
| `schemaRegistryResolveReferences` | `Boolean` | Transitively resolve references (default `true`) |
| `schemaRegistrySubjectFilter` | `Option[String]` | Case-insensitive name filter for listing |
