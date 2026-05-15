[![CI](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/sbt-schema-registry-plugin_2.12_1.0.svg)](https://central.sonatype.com/artifact/org.galaxio/sbt-schema-registry-plugin)
[![codecov](https://codecov.io/github/galax-io/sbt-schema-registry-plugin/coverage.svg?branch=main)](https://codecov.io/github/galax-io/sbt-schema-registry-plugin?branch=main)
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

## Configuration

In your `build.sbt`:

```sbt
import org.galaxio.avro.{RegistrySubject, SchemaRegistryAuth}

val schemas = Seq(
  RegistrySubject("hello.world.schema", 2),      // specific version
  RegistrySubject("schema1-name", 12),            // specific version
  RegistrySubject.latest("schema2-name"),          // latest version
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

Schema files are saved as `<subject>-<version>.avsc` in the target folder.
The build will fail if any schema download fails.

## Settings

| Parameter                    | Description                              | Default                |
|------------------------------|------------------------------------------|------------------------|
| `schemaRegistrySubjects`     | List of schema subjects with versions    | `Seq()`                |
| `schemaRegistryUrl`          | URL of the schema registry               | `http://localhost:8081` |
| `schemaRegistryTargetFolder` | Output directory for downloaded schemas   | `src/main/avro`        |
| `schemaRegistryCacheSize`    | Schema registry client cache size        | `200`                  |
| `schemaRegistryAuth`         | Authentication credentials               | `None`                 |
| `schemaRegistryProperties`   | Additional schema registry client config | `Map.empty`            |

## License

[Apache 2.0](LICENSE)
