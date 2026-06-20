# Implementation Plan: List Subjects Task

**Branch**: `008-list-subjects-task` | **Date**: 2026-06-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-list-subjects-task/spec.md` (issue [#32](https://github.com/galax-io/sbt-schema-registry-plugin/issues/32))

## Summary

Add a read-only sbt task `schemaRegistryListSubjects` that lists registry subjects — each with its version range and compatibility level — directly from sbt, for discovery and debugging. A pure core `SubjectExplorer.listAll(client, filter, parallelism)` fetches and shapes the data into immutable value types (`SubjectInfo`, `SubjectListing`) and returns `Either[DownloadError, SubjectListing]`; all formatting and logging live only in the sbt task. An optional `schemaRegistrySubjectFilter` (case-insensitive substring) narrows the listing — applied to subject names *before* fetching, so excluded subjects are never hit. Per-subject metadata is fetched concurrently via a shared `BoundedParallel.traverse` (also used by `ParallelDownloader`), reusing the existing `schemaRegistryParallelism` budget. The task reuses the existing client construction (`Downloader.buildClient` via `withRegistryClient`) and connection settings — no new connection config, fully additive, backward-compatible.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt's Scala), sbt 1.12.x autoplugin API

**Primary Dependencies**: Confluent `kafka-schema-registry-client` 8.2.1 (`SchemaRegistryClient`); Scala standard library only — **no cats** (issue's cats syntax replaced with stdlib `Try`/`Either`/`foldLeft`, see research D2)

**Storage**: N/A (reads from a remote Schema Registry; no local persistence)

**Testing**: ScalaTest 3.2.x + mockito-scala (unit), Testcontainers (`it/test`, real Schema Registry + Kafka), sbt `scripted` (plugin e2e)

**Target Platform**: JVM (Java 17+); runs inside an sbt build

**Project Type**: Single published sbt plugin (one project + `it/` integration subproject)

**Performance Goals**: Interactive discovery. Per-subject metadata (versions + compatibility) fetched concurrently on a bounded pool sized by `schemaRegistryParallelism` (research D4); `1` = sequential. No hard latency target.

**Constraints**: `-Xfatal-warnings` (no warnings tolerated); backward compatibility of all published keys/tasks; no new runtime dependency

**Scale/Scope**: Client-manageable subject counts (thousands, not millions); full list fetched and filtered client-side. ~3 new core files + 2 new keys + 1 new error case + 3 test tiers.

## Constitution Check

*GATE: must pass before Phase 0 and re-checked after Phase 1.*

| Principle | Assessment | Status |
|---|---|---|
| I. Backward Compatibility | Only additive: 1 new `taskKey`, 1 new `settingKey` (default `None`), 1 new internal `DownloadError` case. No existing key/task/behavior changes. | ✅ PASS |
| II. Single Responsibility | `SubjectExplorer` fetches+transforms (returns `Either`, no logging); `SubjectInfo`/`SubjectListing` are pure value types; presentation only in the task. Client is **injected**. | ✅ PASS |
| III. Test-First | Unit (mock client) + integration (`it/`, real registry, no HTTP mocking) + scripted e2e. Tests authored before implementation. | ✅ PASS |
| IV. Trunk-Based Release | No release-process impact at plan stage; work branches from `main`. | ✅ PASS |
| V. Format & Verify | Plain Scala compiles clean under `-Xfatal-warnings`; `scalafmtAll`/`scalafmtSbt` before commit; CI gate unchanged. | ✅ PASS |

**Result**: PASS — no violations. Complexity Tracking not required.

*Post-Phase-1 re-check*: Design introduces no new dependency, no new project, no cross-class responsibility bleed, no public-API break. Still PASS.

## Project Structure

### Documentation (this feature)

```text
specs/008-list-subjects-task/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1–D9
├── data-model.md        # Phase 1 — entities & keys
├── quickstart.md        # Phase 1 — validation scenarios V1–V6
├── contracts/
│   └── plugin-api.md     # Phase 1 — sbt keys + core API contract
├── checklists/
│   └── requirements.md   # spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT here)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── SchemaDownloaderPlugin.scala   # MODIFY: add taskKey schemaRegistryListSubjects,
│                                  #         settingKey schemaRegistrySubjectFilter (default None),
│                                  #         default in defaultSettings, root→Compile delegation + task body
├── DownloadError.scala            # MODIFY: add case SubjectVersionsFetchFailed(subject, error)
├── ParallelDownloader.scala       # MODIFY: route through shared BoundedParallel.traverse (no dup pool code)
├── BoundedParallel.scala          # NEW: bounded, order-preserving traverse; shared by ParallelDownloader + SubjectExplorer
├── SubjectInfo.scala              # NEW: value type (name, versions, compatibility) + versionRange/latestVersion
├── SubjectListing.scala           # NEW: value type wrapping List[SubjectInfo] + matching(filter) + nameMatches predicate
└── SubjectExplorer.scala          # NEW: pure core listAll(client, filter, parallelism): Either[DownloadError, SubjectListing]

src/test/scala/org/galaxio/avro/
├── SubjectExplorerSpec.scala      # NEW: mock client — extraction, sort, filter, fail-fast (incl. parallel + first-error order), best-effort compat
├── SubjectInfoSpec.scala          # NEW: versionRange / latestVersion edge cases
├── SubjectListingSpec.scala       # NEW: matching() case-insensitive substring, empty filter
└── BoundedParallelSpec.scala      # NEW: order preserved, sequential vs parallel, concurrency proof

it/src/test/scala/org/galaxio/avro/SubjectExplorerIntegrationSpec.scala   # NEW: integration spec — real registry list + filter (Testcontainers; mirrors SubjectResolverIntegrationSpec)

src/sbt-test/schema-registry/list-subjects/   # NEW scripted e2e (mirror download-wildcard)
├── build.sbt                      # uses RegistryFixture.url, schemaRegistrySubjectFilter
├── project/plugins.sbt|docker.sbt # fixture wiring
└── test                           # > schemaRegistryListSubjects (success), filtered run, negative (->)
```

**Structure Decision**: Single sbt-plugin project (the repo's established layout). Core logic in small single-responsibility files under `src/main/scala/org/galaxio/avro`, mirroring siblings (`SubjectResolver`, `Downloader`). Presentation stays in `SchemaDownloaderPlugin`. Tests span the project's three tiers (`src/test`, `it/`, `src/sbt-test`).

## Phase 0 — Research

Complete. See [research.md](research.md). Decisions: D1 reuse `DownloadError` (+`SubjectVersionsFetchFailed`, not `RegistryError.FetchFailed`); D2 no cats → stdlib; D3 best-effort compatibility; D4 parallel per-subject fetch reusing `schemaRegistryParallelism` (via shared `BoundedParallel.traverse`); D5 new `schemaRegistrySubjectFilter` case-insensitive substring; D6 client methods `getAllSubjects`/`getAllVersions`/`getCompatibility`; D7 new value types (not reuse `RegistrySubject`); D8 `taskKey[Unit]` mirroring existing tasks; D9 three-tier testing (scripted asserts success/failure, content via `it/`). No `NEEDS CLARIFICATION` remain.

## Phase 1 — Design & Contracts

Complete. Artifacts: [data-model.md](data-model.md) (entities, keys, the additive error case), [contracts/plugin-api.md](contracts/plugin-api.md) (sbt keys + core API + backward-compat assertions), [quickstart.md](quickstart.md) (V1–V6 validation mapped to spec items). Agent context (`CLAUDE.md` SPECKIT block) updated to point at this plan.

## Phase 2 — Task planning approach (for `/speckit-tasks`, not executed here)

Expected decomposition, test-first and dependency-ordered:
1. Pure value types + their unit tests (`SubjectInfo`, `SubjectListing`) — no client needed.
2. `DownloadError.SubjectVersionsFetchFailed` case.
3. `SubjectExplorer.listAll` + `SubjectExplorerSpec` (mock client) — depends on 1–2.
4. Plugin wiring: keys, default, task body — depends on 3.
5. Integration spec (`it/`) — depends on 3.
6. Scripted e2e `list-subjects/` — depends on 4.
7. Format/verify gate (`scalafmt*`, `compile test`, `it/test`, `scripted`).

## Complexity Tracking

No constitution violations — section intentionally empty.
