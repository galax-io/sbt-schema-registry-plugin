# Implementation Plan: Parallel Schema Downloads

**Branch**: `feat/006-parallel-schema-downloads` | **Date**: 2026-06-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-parallel-schema-downloads/spec.md`

## Summary

Add bounded-concurrency schema downloading to the sbt plugin using `scala.concurrent.Future` with a fixed-size thread pool (loan pattern). The existing `Downloader.schemaSubjectToFile` stays synchronous and pure (`Either`-based). A new `ParallelDownloader` wraps it with concurrency, retry logic, and progress logging. No new library dependencies — stdlib `Future` + `ExecutionContext.fromExecutor` only.

## Technical Context

**Language/Version**: Scala 2.12.21

**Primary Dependencies**: Confluent `kafka-schema-registry-client` 8.2.1 (thread-safe `CachedSchemaRegistryClient`)

**Storage**: Local filesystem (schema files) + `.schema-versions.json` (incremental manifest)

**Testing**: ScalaTest 3.2.20 + mockito-scala 2.2.1 (unit), Testcontainers 1.21.4 (integration), sbt scripted (e2e)

**Target Platform**: JVM 17+, sbt 1.12.x plugin

**Project Type**: sbt autoplugin (library)

**Performance Goals**: ≥3x wall-clock speedup for 20+ subjects at parallelism=4

**Constraints**: No new runtime dependencies. Scala 2.12 stdlib only for concurrency. Max parallelism cap at 32. Retry up to 3 attempts default with exponential backoff.

**Scale/Scope**: Typical projects have 5–50 subjects. Registry client is thread-safe and shared.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Backward Compatibility | ✅ PASS | All existing sbt keys preserved. New keys added (`schemaRegistryParallelism`, `schemaRegistryRetries`). Default parallelism > 1 changes timing but not behavior. `parallelism=1` restores exact sequential path. |
| II. Single Responsibility | ✅ PASS | `ParallelDownloader` owns concurrency orchestration. `RetryPolicy` owns retry logic. `Downloader` unchanged — still owns single-subject fetch+write. No mixed responsibilities. |
| III. Test-First Development | ✅ PASS | Unit tests mock the registry client. Integration tests use Testcontainers with real registry. Thread pool cleanup testable via loan pattern lifecycle. |
| IV. Trunk-Based Release | ✅ PASS | Feature branch, no release workflow changes. |
| V. Format and Verify | ✅ PASS | Standard `scalafmtAll` + `-Xfatal-warnings` workflow. |
| New Dependencies | ✅ PASS | Zero new dependencies. Using `scala.concurrent.Future` + `java.util.concurrent.Executors` from stdlib. |

**Gate result**: ALL PASS — proceed to Phase 0.

## Project Structure

### Documentation (this feature)

```text
specs/006-parallel-schema-downloads/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── sbt-keys.md     # New sbt setting keys contract
└── tasks.md             # Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
src/
├── main/scala/org/galaxio/avro/
│   ├── Downloader.scala              # EXISTING — unchanged, single-subject fetch
│   ├── DownloadError.scala           # EXISTING — add InvalidParallelism, RetryExhausted
│   ├── DownloadDecision.scala        # EXISTING — unchanged
│   ├── IncrementalResolver.scala     # EXISTING — unchanged
│   ├── SchemaDownloaderPlugin.scala  # MODIFY — add parallelism/retry settings, wire ParallelDownloader
│   ├── ParallelDownloader.scala      # NEW — concurrency orchestration + progress logging
│   └── RetryPolicy.scala             # NEW — configurable retry with exponential backoff
│
├── test/scala/org/galaxio/avro/
│   ├── ParallelDownloaderSpec.scala  # NEW — unit tests with mocked Downloader
│   └── RetryPolicySpec.scala         # NEW — retry logic unit tests
│
└── it/src/test/scala/org/galaxio/avro/
    └── ParallelDownloaderIntegrationSpec.scala  # NEW — Testcontainers integration

src/sbt-test/
└── parallel-download/                # NEW — sbt scripted e2e test
    ├── build.sbt
    ├── project/plugins.sbt
    └── test
```

**Structure Decision**: Single sbt plugin project (existing structure). Two new source files (`ParallelDownloader.scala`, `RetryPolicy.scala`) plus corresponding test files. No structural changes.

## Complexity Tracking

No constitution violations — table omitted.
