[![CI](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/galax-io/sbt-schema-registry-plugin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.galaxio/sbt-schema-registry-plugin.svg)](https://central.sonatype.com/artifact/org.galaxio/sbt-schema-registry-plugin)
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
import org.galaxio.avro.RegistrySubject

val schemas = Seq(
  RegistrySubject("hello.world.schema", 2),
  RegistrySubject("schema1-name", 12),
)

lazy val root = (project in file("."))
  .settings(
    schemaRegistryUrl := "http://schema-registry-host:8081",
    schemaRegistrySubjects ++= schemas,
  )
```

## Usage

Download all schemas listed in `schemaRegistrySubjects`:

```bash
sbt "Compile / schemaRegistryDownload"
```

Schema files are saved as `.avsc` files in the target folder.

## Settings

| Parameter                    | Description                            | Default                |
|------------------------------|----------------------------------------|------------------------|
| `schemaRegistrySubjects`     | List of schema subjects with versions  | `Seq()`                |
| `schemaRegistryUrl`          | URL of the schema registry             | `http://localhost:8081` |
| `schemaRegistryTargetFolder` | Output directory for downloaded schemas | `src/main/avro`        |

## License

[Apache 2.0](LICENSE)
