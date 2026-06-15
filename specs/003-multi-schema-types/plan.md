# Implementation Plan: Multi-Schema Type Support

**Branch**: `003-multi-schema-types` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-multi-schema-types/spec.md`

## Summary

Extend the plugin to fully support Protobuf and JSON Schema alongside Avro for all three operations (register, download, compatibility check). The codebase already has `SchemaType` ADT, extension-based type detection, and reflection-based `ParsedSchema` construction. Remaining work: fix hardcoded `.avsc` in download, add schema reference support, enrich `SchemaType` with extension/label fields, and add `fromRegistryLabel` constructor for download-path type detection.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt's Scala version)

**Primary Dependencies**: `io.confluent:kafka-schema-registry-client:8.2.1` (runtime). `kafka-protobuf-serializer` and `kafka-json-schema-serializer` are optional — loaded via reflection.

**Storage**: Filesystem (schema files read/written by plugin tasks)

**Testing**: ScalaTest + mockito-scala (unit), Testcontainers with real Schema Registry + Kafka (integration), sbt scripted (e2e plugin tests)

**Target Platform**: JVM 17+, sbt 1.12.x plugin

**Project Type**: sbt autoplugin (library)

**Performance Goals**: N/A — build-time tool, performance is bounded by Schema Registry API latency

**Constraints**: Scala 2.12 only (sbt constraint). Must maintain backward compatibility for Avro-only users (FR-008). Protobuf/JSON serializer libs are optional runtime deps.

**Scale/Scope**: Small codebase (~13 source files, ~5 unit test files, ~3 integration test files, ~16 scripted tests)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Backward Compatibility | PASS | FR-008 ensures no config changes for Avro-only users. `RegistryRegistration` already defaults `schemaType = Avro`. New `references` field will default to empty. |
| II. Single Responsibility | PASS | `SchemaType` owns type mapping. `Downloader` owns download. `Registrar` owns registration. No new classes needed beyond enriching existing ones. |
| III. Test-First Development | PASS | Plan includes unit, integration, and scripted tests for all new paths. |
| IV. Trunk-Based Release | PASS | Feature branch from main, standard PR flow. |
| V. Format and Verify | PASS | `scalafmtAll` + `scalafmtSbt` before every commit. `-Xfatal-warnings` enforced. |

No violations. No complexity tracking needed.

## Project Structure

### Documentation (this feature)

```text
specs/003-multi-schema-types/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── sbt-keys.md      # Public sbt settings/tasks contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── SchemaType.scala              # Enrich with extension/registryLabel fields, add fromRegistryLabel
├── SchemaReference.scala         # NEW: schema reference model (subject + version)
├── RegistryRegistration.scala    # Add optional references field
├── Downloader.scala              # Fix hardcoded .avsc, detect type from registry metadata
├── Registrar.scala               # Pass references to client.register()
├── CompatibilityChecker.scala    # Pass references to testCompatibilityVerbose()
├── SchemaDownloaderPlugin.scala  # No changes needed (already exposes RegistryRegistration)
├── RegistryError.scala           # Already has UnsupportedSchemaType
├── DownloadError.scala           # No changes needed
├── RegistrySubject.scala         # No changes needed
├── SchemaRegistryAuth.scala      # No changes needed
├── RegisteredSchema.scala        # No changes needed
├── CompatibilityResult.scala     # No changes needed
└── CompatibilityReport.scala     # No changes needed

src/test/scala/org/galaxio/avro/
├── SchemaTypeSpec.scala              # Add fromRegistryLabel tests
├── DownloaderSpec.scala              # Add multi-type download tests
├── RegistrarSpec.scala               # Add reference tests
└── CompatibilityCheckerSpec.scala    # Add multi-type tests

it/src/test/scala/org/galaxio/avro/
├── DownloaderIntegrationSpec.scala            # Add Protobuf/JSON download tests
├── RegistrarIntegrationSpec.scala             # Add Protobuf/JSON registration + references
└── CompatibilityCheckerIntegrationSpec.scala  # Add Protobuf/JSON compat tests

src/sbt-test/schema-registry/
├── register-protobuf/    # NEW: scripted test for Protobuf registration
├── register-json/        # NEW: scripted test for JSON Schema registration
├── download-multi-type/  # NEW: scripted test for multi-type download
└── register-references/  # NEW: scripted test for schema references
```

**Structure Decision**: Existing single-project structure. Only one new source file (`SchemaReference.scala`). All other changes are modifications to existing files.

## Complexity Tracking

No violations — table not needed.
