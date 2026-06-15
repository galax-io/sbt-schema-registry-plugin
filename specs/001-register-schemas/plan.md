# Implementation Plan: Register (Push) Schemas to Registry

**Branch**: `feat/register-schemas` | **Date**: 2026-06-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-register-schemas/spec.md`

## Summary

Add a `schemaRegistryRegister` sbt task that pushes local schema files to
Confluent Schema Registry. Pure core logic (`Registrar`) separated from
effectful sbt task layer. Supports Avro (built-in), Protobuf and JSON Schema
(user-provided provider deps). Reuses existing auth/connection settings.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt's Scala)

**Primary Dependencies**: sbt 1.12.x autoplugin API, Confluent
`kafka-schema-registry-client` 8.2.1 (existing)

**Storage**: N/A (registry is external state)

**Testing**: ScalaTest (unit), mockito-scala (mocking client), Testcontainers
(integration), sbt scripted (e2e)

**Target Platform**: JVM — sbt plugin

**Project Type**: Library (sbt plugin)

**Performance Goals**: Register 10 schemas < 30 seconds

**Constraints**: Zero new runtime dependencies for core Avro path. Backward
compatible with all existing public keys and behavior.

**Scale/Scope**: Typical project has 1–50 schema registrations

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Backward Compatibility | PASS | Additive only — new keys, no changes to existing |
| II. Single Responsibility | PASS | `Registrar` = pure registration logic, plugin wires task |
| III. Test-First | PASS | Unit (mocked), integration (Testcontainers), scripted (e2e) |
| IV. Trunk-Based Release | PASS | Feature branch, PR to main, no release impact |
| V. Format Before Commit | PASS | Standard workflow applies |

No violations. No complexity justification needed.

## Project Structure

### Documentation (this feature)

```text
specs/001-register-schemas/
├── spec.md
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── checklists/
│   └── requirements.md
└── contracts/
    └── sbt-keys.md
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── SchemaDownloaderPlugin.scala  (existing — add register keys + task)
├── Downloader.scala              (existing — unchanged)
├── DownloadError.scala           (existing — unchanged)
├── RegistrySubject.scala         (existing — unchanged)
├── SchemaRegistryAuth.scala      (existing — unchanged)
├── SchemaType.scala              (NEW)
├── RegistryRegistration.scala    (NEW)
├── RegisteredSchema.scala        (NEW)
├── RegistryError.scala           (NEW)
└── Registrar.scala               (NEW)

src/test/scala/org/galaxio/avro/
├── RegistrarSpec.scala           (NEW)
└── SchemaTypeSpec.scala          (NEW)

it/src/test/scala/org/galaxio/avro/
└── RegistrarIntegrationSpec.scala (NEW)

src/sbt-test/schema-registry/
├── register-success/             (NEW)
│   ├── build.sbt
│   ├── src/main/avro/test.avsc
│   └── test
└── register-then-download/       (NEW)
    ├── build.sbt
    ├── src/main/avro/test.avsc
    └── test
```

**Structure Decision**: All new code in existing `org.galaxio.avro` package.
No new subprojects. Registration is a peer feature to download, both exposed
through `SchemaDownloaderPlugin`.

## Complexity Tracking

No violations — no entry needed.
