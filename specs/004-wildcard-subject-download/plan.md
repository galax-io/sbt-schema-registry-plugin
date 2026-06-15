# Implementation Plan: Wildcard Subject Download

**Branch**: `004-wildcard-subject-download` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-wildcard-subject-download/spec.md`

## Summary

Add regex-based subject matching for schema downloads. A new `schemaRegistrySubjectPatterns` sbt setting accepts regex patterns. At download time, a `SubjectResolver` fetches the full subject list from Schema Registry, applies full-match regex filtering, and merges results with explicitly listed subjects (explicit wins on overlap). Uses new `SubjectSpec` ADT to model both exact and pattern subject specifications, and `DownloadPlan` to represent the resolved, deduplicated download list.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt's Scala version)

**Primary Dependencies**: `io.confluent:kafka-schema-registry-client:8.2.1` — provides `SchemaRegistryClient.getAllSubjects()` for subject listing

**Storage**: Filesystem (schema files written by download task)

**Testing**: ScalaTest + mockito-scala (unit), Testcontainers with real Schema Registry + Kafka (integration), sbt scripted (e2e plugin tests)

**Target Platform**: JVM 17+, sbt 1.12.x plugin

**Project Type**: sbt autoplugin (library)

**Performance Goals**: N/A — build-time tool. One extra network call (`getAllSubjects`) per download when patterns configured.

**Constraints**: Scala 2.12 only. Full backward compatibility required — no changes to existing `schemaRegistrySubjects` behavior. Full-match regex semantics. Fail-fast on invalid patterns.

**Scale/Scope**: 3 new source files (`SubjectSpec`, `SubjectResolver`, `DownloadPlan`), modifications to `SchemaDownloaderPlugin` and `DownloadError`. ~5 new test files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Backward Compatibility | PASS | New `schemaRegistrySubjectPatterns` key defaults to `Seq.empty`. Existing `schemaRegistrySubjects` semantics unchanged. When no patterns configured, behavior identical to current. |
| II. Single Responsibility | PASS | `SubjectSpec` models specs, `SubjectResolver` resolves patterns, `DownloadPlan` carries resolved list. Plugin wires them. Downloader unchanged. |
| III. Test-First Development | PASS | Plan includes unit tests for `SubjectResolver`, integration tests with real Schema Registry, scripted e2e test. |
| IV. Trunk-Based Release | PASS | Feature branch from main, standard PR flow. |
| V. Format and Verify | PASS | `scalafmtAll` + `scalafmtSbt` before every commit. `-Xfatal-warnings` enforced. |

No violations. No complexity tracking needed.

## Project Structure

### Documentation (this feature)

```text
specs/004-wildcard-subject-download/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── sbt-keys.md      # Public sbt settings contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── SubjectSpec.scala             # NEW: sealed ADT — Exact | Pattern
├── DownloadPlan.scala            # NEW: resolved subject list
├── SubjectResolver.scala         # NEW: pattern resolution logic
├── DownloadError.scala           # ADD: InvalidPattern, SubjectListFailed cases
├── SchemaDownloaderPlugin.scala  # ADD: schemaRegistrySubjectPatterns key, resolve before download
├── Downloader.scala              # No changes needed
├── RegistrySubject.scala         # No changes needed
├── SchemaType.scala              # No changes needed
├── SchemaRegistryAuth.scala      # No changes needed
├── Registrar.scala               # No changes needed
├── CompatibilityChecker.scala    # No changes needed
├── RegistryError.scala           # No changes needed
├── RegisteredSchema.scala        # No changes needed
├── RegistryRegistration.scala    # No changes needed
├── CompatibilityResult.scala     # No changes needed
├── CompatibilityReport.scala     # No changes needed
└── SchemaReference.scala         # No changes needed

src/test/scala/org/galaxio/avro/
├── SubjectResolverSpec.scala     # NEW: unit tests for pattern resolution
├── SubjectSpecSpec.scala         # NEW: unit tests for ADT construction
└── DownloadPlanSpec.scala        # NEW: unit tests for deduplication logic

it/src/test/scala/org/galaxio/avro/
└── SubjectResolverIntegrationSpec.scala  # NEW: pattern matching against real Registry

src/sbt-test/schema-registry/
└── download-wildcard/            # NEW: scripted e2e test for pattern download
```

**Structure Decision**: Existing single-project structure. Three new source files for the domain model and resolver. All integration via modifications to existing plugin file.

## Complexity Tracking

No violations — table not needed.
