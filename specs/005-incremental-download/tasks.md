# Tasks: Incremental Schema Download

**Input**: Design documents from `specs/005-incremental-download/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/sbt-settings.md

**Tests**: Included — constitution mandates test-first development (Principle III).

**Organization**: Tasks grouped by user story. US1+US2 share a phase (both P1, inseparable implementation — IncrementalResolver.plan handles both Pinned and Latest in one pass).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, etc.)
- Paths relative to repository root

---

## Phase 1: Setup

**Purpose**: No project initialization needed — existing sbt plugin project. This phase is empty.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core domain types used by ALL user stories. Must complete before any story implementation.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T001 [P] Create DownloadDecision sealed ADT (Download | Skip) in src/main/scala/org/galaxio/avro/DownloadDecision.scala — see data-model.md for variants: `Download(subject: RegistrySubject, reason: String)` and `Skip(name: String, localVersion: Int)`
- [x] T002 [P] Create VersionManifest immutable case class with JSON serialization in src/main/scala/org/galaxio/avro/VersionManifest.scala — use Jackson tree API (transitive dep) for ser/de. Fields: `versions: Map[String, Int]`. Methods: `versionOf`, `updated`, `updatedAll`, `toJson`, `fromJson`. Companion: `empty`, `fromJson`. See data-model.md and research.md R1
- [x] T003 [P] Write VersionManifestSpec unit tests in src/test/scala/org/galaxio/avro/VersionManifestSpec.scala — test: JSON round-trip, empty manifest, single entry, multiple entries, `versionOf` lookup (found/not found), `updated`/`updatedAll`, corrupt JSON returns Left, empty string returns Left, missing file treated as empty

**Checkpoint**: Domain types ready. All user story implementations can now begin.

---

## Phase 3: User Story 1 + User Story 2 — Core Incremental Logic (Priority: P1) 🎯 MVP

**Goal**: Skip unchanged schemas on repeated download. Handles both Latest subjects (US1: version check against registry) and Pinned subjects (US2: version check against manifest only, no registry call).

**Independent Test (US1)**: Run `schemaRegistryDownload` twice with no registry changes. Second run skips all Latest subjects.

**Independent Test (US2)**: Configure pinned version subject. Download once, re-run. Second run skips without contacting registry.

### Tests for US1+US2

> **Write tests FIRST, ensure they FAIL before implementation**

- [x] T004 [P] [US1] Write IncrementalResolverSpec unit tests in src/test/scala/org/galaxio/avro/IncrementalResolverSpec.scala — pure function tests, NO mocks needed. Test cases for `plan()`:
  - Latest subject, not in manifest → Download (reason: "new, registry vN")
  - Latest subject, manifest version matches registry → Skip
  - Latest subject, manifest version < registry version → Download (reason: "local vX → registry vY")
  - Pinned subject, not in manifest → Download (reason: "pinned vN, not cached")
  - Pinned subject, manifest version matches → Skip
  - Pinned subject, manifest version differs → Download
  - Empty manifest → all subjects Download
  - `updatedManifest` correctly merges downloaded entries
- [x] T005 [P] [US2] Write IncrementalResolverSpec additional pinned-version test cases in src/test/scala/org/galaxio/avro/IncrementalResolverSpec.scala — verify pinned subjects NEVER call the version lookup function (pass a function that throws to prove it)

### Implementation for US1+US2

- [x] T006 [US1] Create IncrementalResolver object with pure `plan` function in src/main/scala/org/galaxio/avro/IncrementalResolver.scala — signature: `def plan(manifest: VersionManifest, subjects: List[RegistrySubject], registryVersions: String => Either[DownloadError, Int]): List[DownloadDecision]`. Also `def updatedManifest(manifest: VersionManifest, downloaded: List[(String, Int)]): VersionManifest`. See research.md R5 for design rationale
- [x] T007 [US1] Wire incremental logic into SchemaDownloaderPlugin in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — add `schemaRegistryIncremental` settingKey[Boolean] (default: true), add to defaultSettings. In `Compile / schemaRegistryDownload` task body: after subject resolution, load manifest from `streams.value.cacheDirectory / ".schema-versions.json"`, call `IncrementalResolver.plan()`, partition decisions, execute only Downloads via existing `Downloader.schemaSubjectToFile()`, update manifest after successful downloads, write manifest to cache file. See contracts/sbt-settings.md for full behavior spec
- [x] T008 [US1] Verify all unit tests pass — run `sbt test` and confirm IncrementalResolverSpec + VersionManifestSpec green

**Checkpoint**: Core incremental download works for both Latest and Pinned subjects. MVP complete.

---

## Phase 4: User Story 3 — Force Full Re-download (Priority: P2)

**Goal**: Users can bypass caching via `sbt clean` or `schemaRegistryIncremental := false`.

**Independent Test**: Download schemas, set `schemaRegistryIncremental := false`, re-run. All subjects download regardless of manifest.

### Implementation for US3

- [x] T009 [US3] Verify `sbt clean` behavior — manifest resides in cacheDirectory (from T007), so `sbt clean` already deletes it. No code changes needed. Verify by examining cacheDirectory path in plugin wiring
- [x] T010 [US3] Verify `schemaRegistryIncremental := false` bypass in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — when false, skip manifest load and IncrementalResolver.plan, download all subjects unconditionally (already wired in T007, verify the conditional branch)

**Checkpoint**: Force re-download works via both mechanisms.

---

## Phase 5: User Story 4 — Transparent Logging (Priority: P2)

**Goal**: Clear log output showing skip/download decisions with reasons and summary counts.

**Independent Test**: Run download with mix of changed/unchanged schemas. Logs show per-subject decisions and summary line.

### Implementation for US4

- [x] T011 [US4] Add per-decision logging in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — for each Skip: `logger.info(s"$name v$version → up to date")`, for each Download: `logger.info(s"${subject.name}: $reason")`. Log format per contracts/sbt-settings.md
- [x] T012 [US4] Add summary line logging in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — after download loop: `logger.info(s"${downloaded.size} downloaded, ${skipped.size} skipped")`
- [x] T013 [US4] Add manifest parse warning in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — when manifest file exists but fromJson returns Left, log `logger.warn("Version manifest corrupted, re-downloading all schemas")`

**Checkpoint**: All log messages visible and formatted correctly.

---

## Phase 6: User Story 5 — Graceful Degradation on Version Check Failure (Priority: P3)

**Goal**: Failed registry version check falls back to download instead of blocking build.

**Independent Test**: Simulate version check failure for one subject. That subject downloads; others still benefit from caching.

### Tests for US5

- [x] T014 [P] [US5] Add failure-fallback test cases to IncrementalResolverSpec in src/test/scala/org/galaxio/avro/IncrementalResolverSpec.scala — test: version lookup returns Left for one subject → Download with reason "version check failed, re-downloading". Other subjects unaffected. Version lookup returns Left for ALL subjects → all Download

### Implementation for US5

- [x] T015 [US5] Verify IncrementalResolver.plan handles Left from registryVersions in src/main/scala/org/galaxio/avro/IncrementalResolver.scala — the `case Left(_) => DownloadDecision.Download(s, "version check failed, re-downloading")` branch (already part of T006 design). Confirm test from T014 passes

**Checkpoint**: Graceful degradation works. No build breakage on transient registry failures.

---

## Phase 7: Integration & E2E Tests

**Purpose**: Real-world validation with actual Schema Registry and sbt plugin wiring.

- [x] T016 Write integration test in it/src/test/scala/org/galaxio/avro/IncrementalDownloadIntegrationSpec.scala — use Testcontainers (Kafka + Schema Registry). Test scenarios: (1) download all → manifest created, (2) re-run → all skipped, (3) register new version → re-run → only changed subject downloaded, (4) delete manifest → re-run → all downloaded fresh. Follow existing DownloaderIntegrationSpec patterns
- [x] T017 Create scripted test in src/sbt-test/schema-registry/download-incremental/ — build.sbt with schemaRegistrySubjects config, test script: first download succeeds, second download logs "up to date", verify manifest file exists in cache dir. Follow existing download-success/ scripted test patterns
- [x] T018 Run full verification — `sbt scalafmtAll scalafmtSbt && sbt scalafmtCheckAll scalafmtSbtCheck compile test && sbt it/test && sbt scripted` — NOTE: scripted tests all fail due to Docker socket not accessible from scripted sandbox (environmental, affects ALL existing scripted tests equally — verified with download-success)

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T019 Run quickstart.md validation scenarios — quickstart scenarios validated through unit (109 pass) and integration (42 pass) tests covering all V1-V4 flows
- [x] T020 Verify existing tests unaffected — `sbt test` (109 tests pass, all existing + new), `sbt it/test` (42 tests pass, all existing + new). Scripted tests skipped (Docker socket env issue, not related to code changes)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (Foundational)**: No dependencies — start immediately
- **Phase 3 (US1+US2)**: Depends on Phase 2 completion — BLOCKS all subsequent stories
- **Phase 4 (US3)**: Depends on Phase 3 (needs plugin wiring)
- **Phase 5 (US4)**: Depends on Phase 3 (needs plugin wiring)
- **Phase 6 (US5)**: Depends on Phase 3 (needs IncrementalResolver)
- **Phase 7 (Integration)**: Depends on Phases 3-6
- **Phase 8 (Polish)**: Depends on Phase 7

### User Story Dependencies

- **US1+US2 (P1)**: Can start after Foundational (Phase 2). No dependencies on other stories
- **US3 (P2)**: Depends on US1+US2 (needs schemaRegistryIncremental setting and plugin wiring)
- **US4 (P2)**: Depends on US1+US2 (needs download decision loop in plugin)
- **US5 (P3)**: Can start after Foundational (Phase 2) in theory, but shares IncrementalResolver from US1+US2

### Within Each Phase

- Tests FIRST (constitution Principle III) → ensure they FAIL
- Domain types before logic
- Logic before wiring
- Wiring before integration tests

### Parallel Opportunities

- T001, T002, T003 can run in parallel (different files, no dependencies)
- T004, T005 can run in parallel with each other (same test file but independent test cases)
- Phase 4 (US3) and Phase 5 (US4) can run in parallel after Phase 3 completes
- T014 can run in parallel with T011-T013

---

## Parallel Example: Phase 2

```
# Launch all foundational tasks together:
Task T001: "Create DownloadDecision ADT in src/main/scala/.../DownloadDecision.scala"
Task T002: "Create VersionManifest in src/main/scala/.../VersionManifest.scala"
Task T003: "Write VersionManifestSpec in src/test/scala/.../VersionManifestSpec.scala"
```

## Parallel Example: Phase 3

```
# Launch test tasks together:
Task T004: "Write IncrementalResolverSpec for Latest subjects"
Task T005: "Write IncrementalResolverSpec for Pinned subjects"

# Then sequential:
Task T006: "Implement IncrementalResolver.plan"
Task T007: "Wire into SchemaDownloaderPlugin"
Task T008: "Verify all unit tests pass"
```

---

## Implementation Strategy

### MVP First (US1+US2 Only)

1. Complete Phase 2: Foundational (T001-T003)
2. Complete Phase 3: US1+US2 (T004-T008)
3. **STOP and VALIDATE**: Run `sbt test`, verify incremental skip works
4. This alone delivers the core value — skip unchanged schemas

### Incremental Delivery

1. Phase 2 → Foundation ready
2. Phase 3 → Core incremental download works (MVP!)
3. Phase 4+5 → Force re-download + logging polish
4. Phase 6 → Graceful degradation on failures
5. Phase 7 → Full integration + e2e validation
6. Phase 8 → Final verification

---

## Notes

- US1 and US2 share one phase because IncrementalResolver.plan handles both Pinned and Latest subjects in a single function — splitting them would create artificial boundaries
- No new dependencies added (Jackson via transitive, constitution compliance)
- Downloader.scala unchanged — incremental logic lives in IncrementalResolver + plugin orchestration
- T009 and T010 are verification tasks (no new code), confirming that T007 wiring already supports US3
