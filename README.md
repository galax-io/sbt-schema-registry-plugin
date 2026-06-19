[![CI](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/sbt-schema-registry-plugin_2.12_1.0.svg)](https://central.sonatype.com/artifact/org.galaxio/sbt-schema-registry-plugin)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# SBT Schema Registry Plugin

SBT plugin for downloading Avro schemas from a Confluent Schema Registry. Adds a `schemaRegistryDownload` task
and configuration settings to declare which schemas to fetch.

## Installation

Add the following to your `project/plugins.sbt`:

```sbt
resolvers ++= Seq(
  "Confluent" at "https://packages.confluent.io/maven/",
)

addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % "<plugin-version>")
```

## Compatibility

| sbt version | Scala version | Plugin artifact                                    |
|-------------|---------------|----------------------------------------------------|
| 1.x         | 2.12.x        | `sbt-schema-registry-plugin_2.12_1.0`              |

The plugin is built as a standard sbt 1.x autoplugin and requires **Scala 2.12** (the Scala version used by sbt
itself). It does **not** depend on the Scala version of your project — you can use it in a Scala 2.13 or Scala 3
project without any changes.

## Configuration

In your `build.sbt`:

```sbt
import org.galaxio.avro.{RegistrySubject, SchemaRegistryAuth}

val schemas = Seq(
  RegistrySubject("hello.world.schema", 2),      // specific version
  RegistrySubject("schema1-name", 12),            // specific version
  RegistrySubject.latest("schema2-name"),          // latest version — resolved at task run time
)

lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl      := "http://schema-registry-host:8081",
    schemaRegistrySubjects ++= schemas,
  )
```

### Authentication

```sbt
schemaRegistryAuth := Some(SchemaRegistryAuth.BasicAuth("username", "password"))
```

### Extra Properties

Pass any Confluent client properties (SSL, timeouts, etc.):

```sbt
schemaRegistryProperties := Map(
  "schema.registry.ssl.truststore.location" -> "/path/to/truststore.jks",
  "schema.registry.ssl.truststore.password" -> "changeit",
)
```

## Usage

Download all schemas listed in `schemaRegistrySubjects`:

```bash
sbt "Compile / schemaRegistryDownload"
```

Schema files are saved as `<subject>-<version>.avsc` in `schemaRegistryTargetFolder` (default: `src/main/avro`).
The build will fail if any schema download fails.

> **Output file behaviour**: by default incremental download is enabled
> (`schemaRegistryIncremental := true`). The task records downloaded versions in a manifest
> (`.schema-versions.json` under the project's sbt cache directory) and skips any subject whose
> version is already current, so unchanged schemas are not re-fetched or re-written. Set
> `schemaRegistryIncremental := false` to overwrite every file on every run. For
> `RegistrySubject.latest(...)` subjects the version number is resolved at task-run time, so the
> output filename (e.g. `my-subject-7.avsc`) may change between runs when the latest version advances.

## Settings

| Parameter                         | Description                                                       | Default                 |
|-----------------------------------|------------------------------------------------------------------|-------------------------|
| `schemaRegistrySubjects`          | List of schema subjects with versions                            | `Seq()`                 |
| `schemaRegistrySubjectPatterns`   | Regex patterns to match subjects for download                    | `Seq()`                 |
| `schemaRegistryUrl`               | URL of the schema registry                                       | `http://localhost:8081` |
| `schemaRegistryTargetFolder`      | Output directory for downloaded schemas                          | `src/main/avro`         |
| `schemaRegistryCacheSize`         | Schema registry client cache size                                | `200`                   |
| `schemaRegistryAuth`              | Authentication credentials                                       | `None`                  |
| `schemaRegistryProperties`        | Additional schema registry client config                         | `Map.empty`             |
| `schemaRegistryIncremental`       | Enable incremental download — skip unchanged schemas             | `true`                  |
| `schemaRegistryParallelism`       | Number of concurrent schema downloads (1 = sequential)           | `4`                     |
| `schemaRegistryRetries`           | Maximum retry attempts for transient download failures (0 = none) | `3`                    |
| `schemaRegistryResolveReferences` | Auto-download referenced schemas transitively                    | `true`                  |

## Schema References

Schemas can reference other schemas (e.g. an `Order` that embeds a `Customer` type). Confluent
Schema Registry tracks these references; downloading the referencing schema alone is not enough —
consumers also need the referenced schemas.

When `schemaRegistryResolveReferences` is `true` (the default), downloading a subject also
downloads every schema it references, transitively. Each referenced schema is fetched at the
**exact version** recorded in the reference (pinned, not latest) and written using the normal
`<subject>-<version>.<ext>` layout, so a single download run produces a complete, self-contained
set of files.

```sbt
schemaRegistrySubjects += RegistrySubject.latest("order-value")
// If order-value references customer-value, both files are downloaded:
//   src/main/avro/order-value-3.avsc
//   src/main/avro/customer-value-1.avsc
```

Resolution is cycle-safe (a reference graph with cycles terminates) and de-duplicates by
subject+version, so a shared dependency is written once while two genuinely different pinned
versions of the same subject are written as separate files. The first failed reference fetch fails
the whole task with an error naming the failing subject.

To download only the explicitly requested subjects (the pre-1.7 behaviour), disable it:

```sbt
schemaRegistryResolveReferences := false
```

> **Known limitation**: the incremental-download manifest keys by subject name (one version per
> subject). If a single run resolves two different versions of the same subject, only the
> last-written version is recorded, so the other may be re-downloaded (identical bytes) on a
> later incremental run. This is wasteful but never incorrect, and only affects the rare
> divergent-version case.

## Download Options

### Wildcard subject patterns

Select subjects by regex instead of (or in addition to) listing them explicitly. Patterns are
matched against every subject reported by the registry; each match is downloaded at its latest
version.

```sbt
schemaRegistrySubjectPatterns := Seq("""order-.*-value""", """.*\.events""")
```

### Incremental download

Enabled by default. Downloaded versions are tracked in a `.schema-versions.json` manifest under
the project's sbt cache directory, and subjects already at the current version are skipped.
Disable to always re-fetch and overwrite:

```sbt
schemaRegistryIncremental := false
```

### Parallel downloads

Schemas are fetched concurrently with a bounded thread pool. Tune the degree of concurrency
(1 = sequential) and the retry budget for transient failures:

```sbt
schemaRegistryParallelism := 8   // 1..32
schemaRegistryRetries     := 3   // 0..10, 0 = no retry
```

## Schema Registration (Push)

Register (push) local schema files to Schema Registry:

```sbt
import org.galaxio.avro.{RegistryRegistration, SchemaType}

schemaRegistryRegistrations := Seq(
  RegistryRegistration("user-value", baseDirectory.value / "src/main/avro/User.avsc"),
  RegistryRegistration("order-value", baseDirectory.value / "src/main/avro/Order.avsc"),
)
```

```bash
sbt "Compile / schemaRegistryRegister"
```

### Schema Types

The default schema type is Avro. For Protobuf or JSON Schema, specify the type explicitly:

```sbt
RegistryRegistration("user-value", file("src/main/avro/User.proto"), SchemaType.Protobuf)
RegistryRegistration("user-value", file("src/main/avro/User.json"), SchemaType.Json)
```

> **Note**: Protobuf and JSON Schema require the corresponding Confluent provider dependencies
> (`kafka-protobuf-provider` or `kafka-json-schema-provider`) on the sbt classpath.

### Registration Settings

| Parameter                     | Description                             | Default    |
|-------------------------------|-----------------------------------------|------------|
| `schemaRegistryRegistrations` | List of subject-to-file schema mappings | `Seq()`    |

All connection settings (`schemaRegistryUrl`, `schemaRegistryAuth`, `schemaRegistryProperties`) are
shared between download, registration, and compatibility tasks.

## Schema Compatibility Check

Verify that local schemas are compatible with versions already registered in Schema Registry
before deploying:

```bash
sbt "Compile / schemaRegistryTestCompatibility"
```

The task uses the same `schemaRegistryRegistrations` setting as `schemaRegistryRegister`. For each
subject it calls `testCompatibilityVerbose` against the registry and reports:

- **Compatible** — the new schema can safely be registered
- **Incompatible** — the registry would reject registration; verbose messages explain why
- **Failed** — an error occurred (file not found, parse error, network issue)

The build fails if any subject is incompatible or fails. Use this in CI before registration to
catch breaking changes early:

```sbt
Compile / compile := (Compile / compile)
  .dependsOn(Compile / schemaRegistryTestCompatibility)
  .value
```

## Workflow Integration

### Run manually

```bash
sbt "Compile / schemaRegistryDownload"
```

### Hook into `compile`

To ensure schemas are always downloaded before compilation, make `compile` depend on the download task:

```sbt
Compile / compile := (Compile / compile)
  .dependsOn(Compile / schemaRegistryDownload)
  .value
```

With this in place a plain `sbt compile` (or `sbt test`) will pull fresh schemas first.

### Hook into `sourceGenerators`

If you use a code-generation tool (e.g. `sbt-avro`) that reads `.avsc` files and produces Scala sources,
register the download task as a source generator so sbt's task graph runs it before code generation:

```sbt
Compile / sourceGenerators += (Compile / schemaRegistryDownload).map(_ => Seq.empty[File])
```

This wires `schemaRegistryDownload` into the standard `sourceGenerators` chain without declaring any
generated source files itself (the actual source generation is handled by the Avro plugin downstream).

### Full example

```sbt
import org.galaxio.avro.{RegistrySubject, SchemaRegistryAuth}

lazy val root = (project in file("."))
  .settings(
    // Schema registry connection
    schemaRegistryUrl  := sys.env.getOrElse("SCHEMA_REGISTRY_URL", "http://localhost:8081"),
    schemaRegistryAuth := sys.env.get("SCHEMA_REGISTRY_USER").map { user =>
      SchemaRegistryAuth.BasicAuth(user, sys.env("SCHEMA_REGISTRY_PASSWORD"))
    },

    // Schemas to download
    schemaRegistrySubjects ++= Seq(
      RegistrySubject("com.example.OrderCreated", 3),
      RegistrySubject.latest("com.example.OrderUpdated"),
    ),

    // Output directory (must match your Avro code-gen plugin's source directory)
    schemaRegistryTargetFolder := file("src/main/avro"),

    // Download schemas before compiling
    Compile / compile := (Compile / compile)
      .dependsOn(Compile / schemaRegistryDownload)
      .value,
  )
```

## Troubleshooting

### Authentication failure (401 Unauthorized)

**Symptom**: the task fails with an HTTP 401 or a message like `Unauthorized`.

**Checks**:
- Confirm the credentials are correct. Test directly:
  ```bash
  curl -u "$USER:$PASS" "$SCHEMA_REGISTRY_URL/subjects"
  ```
- Make sure `schemaRegistryAuth` is set **before** the task runs. If credentials come from environment
  variables, verify they are exported in the shell that runs `sbt`.
- Basic auth must be enabled on the registry side; some deployments use mTLS or token-based auth instead —
  those require custom `schemaRegistryProperties` rather than `BasicAuth`.

### SSL / TLS handshake failure

**Symptom**: the task fails with `SSLHandshakeException`, `PKIX path building failed`, or similar.

**Solution**: supply the truststore (and optionally keystore) via `schemaRegistryProperties`:

```sbt
schemaRegistryProperties := Map(
  // Trust store — the CA that signed the registry's certificate
  "schema.registry.ssl.truststore.location" -> "/etc/ssl/certs/registry-ca.jks",
  "schema.registry.ssl.truststore.password" -> "changeit",

  // Key store — only needed for mutual TLS (mTLS)
  "schema.registry.ssl.keystore.location"   -> "/etc/ssl/certs/client.jks",
  "schema.registry.ssl.keystore.password"   -> "changeit",
)
```

The keys map directly to [Confluent Schema Registry client configuration properties](https://docs.confluent.io/platform/current/schema-registry/develop/api.html).

If the registry certificate is signed by a well-known CA that is already in the JVM's default truststore,
no additional configuration is required.

### Subject not found (404)

**Symptom**: build fails with HTTP 404 or `Subject not found`.

**Checks**:
- Verify the subject name is spelled exactly as registered (subject names are case-sensitive).
- Confirm the requested version exists:
  ```bash
  curl "$SCHEMA_REGISTRY_URL/subjects/<subject-name>/versions"
  ```
- For `RegistrySubject.latest(...)`, ensure at least one version has been registered under that subject.

### `latest` version keeps changing

`RegistrySubject.latest("subject")` resolves the current latest version **at task-run time**. Each time a
new schema version is published to the registry the downloaded file name will change (e.g. from
`subject-4.avsc` to `subject-5.avsc`). If you need a stable, reproducible build, pin to a specific
version number instead:

```sbt
RegistrySubject("subject", 4)   // always downloads version 4
```

## Development

Built with **sbt 1.12.11** on **Scala 2.12.21** (the Scala version sbt runs on). The build is split into two
modules: the plugin itself (root) and an `it` subproject that holds the Testcontainers-based integration tests.

```bash
sbt scalafmtAll scalafmtSbt   # format
sbt compile test              # compile + unit tests (no external services)
sbt it/test                   # integration tests — spins up Schema Registry + Kafka, requires Docker
sbt scripted                  # plugin e2e tests (download-success needs Docker)
```

`ci.yml` runs formatting, unit tests, integration tests, and scripted tests on every PR and on `main` /
`release/*`.

### Release process

Trunk-based: `main` is the trunk; cut `release/*` branches from it for stabilization. Releases are
**tag-driven** — push a `vX.Y.Z` tag on `main` or a `release/*` branch and `release.yml` will:

1. verify the tag sits on `main` / `release/*`,
2. run `sbt compile test`,
3. publish to Maven Central via `sbt-ci-release` (version derived from the tag by dynver),
4. generate release notes from Conventional Commits with [git-cliff](https://git-cliff.org) (`cliff.toml`) and
   create a GitHub Release.

## License

[Apache 2.0](LICENSE)
