# Implementation Plan: Incremental Schema Download

**Branch**: `feat/005-incremental-download` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-incremental-download/spec.md`

## Summary

Add incremental download to `schemaRegistryDownload` — maintain a version manifest in sbt's cache directory, compare versions before fetching, skip unchanged schemas. Pure functional planning logic (`IncrementalResolver.plan`) separated from I/O. New `schemaRegistryIncremental` setting (default `true`) with `sbt clean` as recovery path.

## Technical Context

**Language/Version**: Scala 2.12.21

**Primary Dependencies**: sbt 1.12.11, Confluent kafka-schema-registry-client 8.2.1, Jackson (transitive via Confluent — used for manifest JSON)

**Storage**: JSON file in sbt's `cacheDirectory` (`.schema-versions.json`)

**Testing**: ScalaTest 3.2.20 (unit), mockito-scala 2.2.1 (unit), Testcontainers 1.21.3 (integration), sbt scripted (e2e)

**Target Platform**: JVM (Java 17+), sbt plugin

**Project Type**: sbt autoplugin (library)

**Performance Goals**: Eliminate redundant schema downloads — zero schema content fetches for unchanged subjects. One lightweight version metadata call per non-pinned subject.

**Constraints**: No new dependencies. Backward compatible — existing `build.sbt` configurations work without changes. `-Xfatal-warnings` must pass.

**Scale/Scope**: Typical usage: 5–50 subjects per project. Manifest file: small JSON (< 10KB).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Backward Compatibility | ✅ PASS | No existing sbt keys changed. New setting `schemaRegistryIncremental` added. Feature defaults to enabled but first run downloads everything (identical to current behavior). |
| II. Single Responsibility | ✅ PASS | `VersionManifest` owns manifest state. `IncrementalResolver` owns planning logic. `Downloader` unchanged. `SchemaDownloaderPlugin` orchestrates. |
| III. Test-First Development | ✅ PASS | Unit tests for pure `IncrementalResolver.plan()` (no mocks needed). Integration tests with real registry via Testcontainers. Scripted test for sbt wiring. |
| IV. Trunk-Based Release | ✅ PASS | Work on feature branch, merge to `main` via PR. |
| V. Format and Verify | ✅ PASS | `scalafmtAll` + `scalafmtSbt` before commit. CI runs full verify pipeline. |

**Post-Phase 1 re-check**: All gates still pass. No new dependencies added (Jackson is transitive). Each new class has single responsibility. Pure function design enables test-first without mocks.

## Project Structure

### Documentation (this feature)

```text
specs/005-incremental-download/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: research decisions
├── data-model.md        # Phase 1: entity definitions
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── sbt-settings.md  # Phase 1: public API contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── VersionManifest.scala        # NEW — immutable manifest + JSON ser/de
├── DownloadDecision.scala       # NEW — sealed ADT: Download | Skip
├── IncrementalResolver.scala    # NEW — pure planning logic
├── SchemaDownloaderPlugin.scala # MODIFY — add setting, wire incremental flow
├── Downloader.scala             # UNCHANGED
├── DownloadPlan.scala           # UNCHANGED
├── RegistrySubject.scala        # UNCHANGED
├── DownloadError.scala          # UNCHANGED
└── ...                          # other existing files unchanged

src/test/scala/org/galaxio/avro/
├── VersionManifestSpec.scala        # NEW — JSON round-trip, edge cases
├── IncrementalResolverSpec.scala    # NEW — pure plan logic tests
└── ...                              # existing tests unchanged

it/src/test/scala/org/galaxio/avro/
├── IncrementalDownloadIntegrationSpec.scala  # NEW — download→skip→update cycle
└── ...                                       # existing integration tests unchanged

src/sbt-test/schema-registry/
├── download-incremental/        # NEW — scripted test for incremental behavior
│   ├── build.sbt
│   └── test
└── ...                          # existing scripted tests unchanged
```

**Structure Decision**: Follows existing flat package layout (`org.galaxio.avro`). Three new source files in main, two new test files, one new integration test, one new scripted test. No structural changes to project layout.

## Complexity Tracking

No constitution violations. No complexity justification needed.
