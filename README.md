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

> **Output file behaviour**: every run overwrites existing `.avsc` files unconditionally. There is no
> incremental caching — the task always fetches the schema from the registry and writes the file afresh.
> For `RegistrySubject.latest(...)` subjects the version number is resolved at task-run time, so the
> output filename (e.g. `my-subject-7.avsc`) may change between runs when the latest version advances.

## Settings

| Parameter                    | Description                              | Default                |
|------------------------------|------------------------------------------|------------------------|
| `schemaRegistrySubjects`     | List of schema subjects with versions    | `Seq()`                |
| `schemaRegistryUrl`          | URL of the schema registry               | `http://localhost:8081` |
| `schemaRegistryTargetFolder` | Output directory for downloaded schemas   | `src/main/avro`        |
| `schemaRegistryCacheSize`    | Schema registry client cache size        | `200`                  |
| `schemaRegistryAuth`         | Authentication credentials               | `None`                 |
| `schemaRegistryProperties`   | Additional schema registry client config | `Map.empty`            |

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

## License

[Apache 2.0](LICENSE)
