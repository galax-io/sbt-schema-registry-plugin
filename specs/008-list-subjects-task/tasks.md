---
description: "Task list for List Subjects Task (008)"
---

# Tasks: List Subjects Task

**Input**: Design documents from `specs/008-list-subjects-task/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/plugin-api.md](contracts/plugin-api.md), [quickstart.md](quickstart.md)

**Tests**: INCLUDED — the constitution mandates test-first (unit + integration + scripted) and the spec's acceptance criteria require them. Write each test FIRST and confirm it FAILS before the implementation task that makes it pass.

**Organization**: Grouped by user story. MVP = User Story 1.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (story phases only)
- All paths are repository-relative.

## Stack reminder (from plan.md)

Scala 2.12.21, sbt autoplugin, Confluent `kafka-schema-registry-client` 8.2.1, **stdlib only — no cats**, `-Xfatal-warnings`. Format with `scalafmtAll`/`scalafmtSbt` before commit. Mirror existing sibling files (`SubjectResolver`, `Downloader`, `DownloadError`).

---

## Phase 1: Setup

**Purpose**: Confirm a clean baseline before additive changes.

- [x] T001 Verify clean baseline — run `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and confirm green before making changes

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared, pure domain types + error case that every user story depends on. Pure value types are test-first and self-contained here.

**⚠️ CRITICAL**: No user-story work begins until this phase is complete.

- [x] T002 [P] Add `final case class SubjectVersionsFetchFailed(subject: String, error: Throwable)` to `DownloadError` (message `Failed to fetch versions for $subject: ${error.getMessage}`, `override def cause = Some(error)`) in src/main/scala/org/galaxio/avro/DownloadError.scala
- [x] T003 [P] Write FAILING unit test src/test/scala/org/galaxio/avro/SubjectInfoSpec.scala — `versionRange` for empty (`none`), single (`3`), many (`1..5`); `latestVersion` = last/None
- [x] T004 [P] Write FAILING unit test src/test/scala/org/galaxio/avro/SubjectListingSpec.scala — `matching` keeps case-insensitive substring matches; `Some("")`/empty filter ⇒ all; `size`
- [x] T005 [P] Implement src/main/scala/org/galaxio/avro/SubjectInfo.scala — `final case class SubjectInfo(name: String, versions: List[Int], compatibility: Option[String])` with `latestVersion` and `versionRange` (makes T003 pass)
- [x] T006 [P] Implement src/main/scala/org/galaxio/avro/SubjectListing.scala — `final case class SubjectListing(subjects: List[SubjectInfo])` with `size` and `matching(filter: String)` (case-insensitive `contains`) (makes T004 pass)

**Checkpoint**: Pure types + error case compile and their unit tests pass — the engine for all stories is ready.

---

## Phase 3: User Story 1 — Discover All Subjects from sbt (Priority: P1) 🎯 MVP

**Goal**: `sbt schemaRegistryListSubjects` connects with the build's existing settings, fetches every subject (sorted) with versions + compatibility, prints count + one line per subject. Fails clearly when the registry is unreachable.

**Independent Test**: Register subjects in a registry, run the task → log shows the count and every subject with its version range and compatibility; with a bad URL the task fails with an actionable message.

### Tests for User Story 1 (write first, must FAIL)

- [x] T007 [P] [US1] Write FAILING unit test src/test/scala/org/galaxio/avro/SubjectExplorerSpec.scala — mock `SchemaRegistryClient`: `listAll(client, None)` returns `Right` sorted by name with versions; `getAllSubjects` throws ⇒ `Left(DownloadError.SubjectListFailed)`; `getAllVersions` throws ⇒ `Left(DownloadError.SubjectVersionsFetchFailed(subject, _))` (fail-fast, no partial)
- [x] T008 [P] [US1] Write FAILING integration test it/src/test/scala/org/galaxio/avro/SubjectExplorerIntegrationSpec.scala — mirror the Testcontainers setup of the sibling `SubjectResolverIntegrationSpec.scala` (package `org.galaxio.avro`); register 2+ subjects in a real registry, `SubjectExplorer.listAll(realClient, None)` returns all sorted with correct version ranges; no HTTP mocking

### Implementation for User Story 1

- [x] T009 [US1] Implement src/main/scala/org/galaxio/avro/SubjectExplorer.scala — `object SubjectExplorer { def listAll(client: SchemaRegistryClient, filter: Option[String]): Either[DownloadError, SubjectListing] }`: `getAllSubjects().asScala.toList.sorted` (stdlib `Try(...).toEither.left.map(SubjectListFailed)`); per subject sequentially fetch `getAllVersions(...).asScala.map(_.intValue).toList` (left ⇒ `SubjectVersionsFetchFailed`) accumulating via `foldLeft` short-circuit; best-effort `getCompatibility` → `Option`; apply `filter.fold(listing)(listing.matching)` (makes T007 pass; depends on T002, T005, T006)
- [x] T010 [US1] Wire the task in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — add `schemaRegistryListSubjects = taskKey[Unit](...)` to `autoImport`; add root→`Compile`-scoped delegation; `Compile / schemaRegistryListSubjects` body mirrors existing tasks: `val logger = streams.value.log`; `withRegistryClient(url, cacheSize, auth, properties) { client => SubjectExplorer.listAll(client, None) match { case Left(e) => sys.error(e.message); case Right(listing) => log count + one line per subject (name padded, `versions: <range>`, `compat: <level|(default)>`) } }`
- [x] T011 [US1] Create scripted e2e src/sbt-test/schema-registry/list-subjects/ mirroring `download-wildcard`: `build.sbt` (`schemaRegistryUrl := RegistryFixture.url`), `project/` fixture wiring (`docker.sbt`/`plugins.sbt`), and `test` script asserting `> schemaRegistryListSubjects` SUCCEEDS; add a bad-URL negative path asserting `-> schemaRegistryListSubjects` FAILS

**Checkpoint**: US1 fully functional — discovery from sbt works and fails cleanly. MVP shippable.

---

## Phase 4: User Story 2 — Filter Subjects by Pattern (Priority: P2)

**Goal**: Setting `schemaRegistrySubjectFilter := Some(text)` restricts the listing to subjects whose name contains `text` (case-insensitive); reported count reflects the filtered set; unset ⇒ all.

**Independent Test**: With several subjects, set the filter to match one group → only those listed and the count matches; unset → all listed; casing ignored.

### Tests for User Story 2 (write first, must FAIL)

- [x] T012 [P] [US2] Add FAILING cases to src/test/scala/org/galaxio/avro/SubjectExplorerSpec.scala — `listAll(client, Some("order"))` keeps only matching (case-insensitive); `Some("")` ⇒ all
- [x] T013 [P] [US2] Add FAILING filter scenario to it/src/test/scala/org/galaxio/avro/SubjectExplorerIntegrationSpec.scala — filter narrows the real listing and count reflects it

### Implementation for User Story 2

- [x] T014 [US2] Add `schemaRegistrySubjectFilter = settingKey[Option[String]](...)` to `autoImport` and its default `None` in `defaultSettings` in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala
- [x] T015 [US2] Pass `schemaRegistrySubjectFilter.value` into `SubjectExplorer.listAll(client, _)` in the task body (replacing the hard-coded `None`) so the logged count/lines reflect filtering, in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala (depends on T010, T014)
- [x] T016 [US2] Extend src/sbt-test/schema-registry/list-subjects/test — `> set schemaRegistrySubjectFilter := Some("Order")` then `> schemaRegistryListSubjects` SUCCEEDS (filtered run)

**Checkpoint**: US1 + US2 both work independently; filtering narrows results case-insensitively.

---

## Phase 5: User Story 3 — Inspect Versions & Compatibility for Debugging (Priority: P3)

**Goal**: Each subject's entry surfaces its version range and compatibility level; subjects without a subject-level override are shown as `(default)` rather than failing.

**Independent Test**: Register one subject with a subject-level compatibility override and multiple versions, and one without; run the task → first shows its level and range, second shows `(default)`.

### Tests for User Story 3 (write first, must FAIL)

- [x] T017 [P] [US3] Add FAILING cases to src/test/scala/org/galaxio/avro/SubjectExplorerSpec.scala — multi-version subject ⇒ `versionRange == "1..N"`; subject-level compat ⇒ `Some(level)`; `getCompatibility` throws ⇒ `None` (best-effort, task still `Right`)
- [x] T018 [P] [US3] Add FAILING scenario to it/src/test/scala/org/galaxio/avro/SubjectExplorerIntegrationSpec.scala — register a subject with a subject-level compatibility override + multiple versions, and one with NO subject-level override; assert the first yields `compatibility == Some(level)` with the correct `versionRange`, and EXPLICITLY assert the override-less subject yields `compatibility == None` (pins research D3: `getCompatibility` returns subject-level only / `None` ⇒ `(default)`, against the real registry)

### Implementation for User Story 3

- [x] T019 [US3] Verify (and adjust only if T010 left a gap) the task's per-subject log line in src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala renders compatibility as the level or `(default)` when `None`, and the version range via `SubjectInfo.versionRange` (single vs `first..last`). NOTE: T010 should already produce this line — this is the US3 acceptance gate; if T010 fully satisfies FR-004/FR-005, this task is a no-op confirmation, not new code (depends on T010)

**Checkpoint**: All three stories independently functional.

---

## Phase 6: Polish & Cross-Cutting

**Purpose**: Docs and full-stack verification.

- [x] T020 [P] Document `schemaRegistryListSubjects` and `schemaRegistrySubjectFilter` (usage + sample output) in README.md (and in AGENTS.md task list if one is maintained)
- [x] T021 Full verify — `sbt scalafmtAll scalafmtSbt` then `sbt scalafmtCheckAll scalafmtSbtCheck compile test`, `sbt it/test`, `sbt scripted` all green (confirms FR-013 backward compat: existing `download-*`/`register-*` scripted tests still pass)
- [x] T022 [P] Execute [quickstart.md](quickstart.md) scenarios V1–V6 and confirm each acceptance mapping holds

---

## Dependencies & Execution Order

### Phase order
- Setup (T001) → Foundational (T002–T006) → US1 (T007–T011) → US2 (T012–T016) → US3 (T017–T019) → Polish (T020–T022)
- Foundational BLOCKS all stories. After Foundational, stories are independently testable; US2/US3 build on US1's task body (shared `SchemaDownloaderPlugin.scala`), so run in priority order when staffed by one developer.

### Key task dependencies
- T009 depends on T002, T005, T006
- T010 depends on T009
- T015 depends on T010, T014
- T019 depends on T010
- T007/T008 (tests) precede T009; T012/T013 precede T014/T015; T017/T018 precede T019

### Same-file serialization (NOT parallel)
- `SchemaDownloaderPlugin.scala`: T010 → T014 → T015 → T019 (sequential)
- `SubjectExplorerSpec.scala`: T007 → T012 → T017 (sequential edits)
- `ListSubjectsIntegrationSpec.scala`: T008 → T013 → T018 (sequential edits)

### Parallel opportunities
- Foundational: T002, T003, T004 in parallel; then T005, T006 in parallel.
- US1 tests: T007 ∥ T008 (different files).
- Polish: T020 ∥ T022.

---

## Parallel Example: Foundational

```bash
# Different files, no inter-dependency:
T002  DownloadError.scala  (new error case)
T003  SubjectInfoSpec.scala (failing test)
T004  SubjectListingSpec.scala (failing test)
# then:
T005  SubjectInfo.scala
T006  SubjectListing.scala
```

## Parallel Example: User Story 1 tests

```bash
T007  SubjectExplorerSpec.scala (unit, mock client)
T008  ListSubjectsIntegrationSpec.scala (it/, Testcontainers)
```

---

## Implementation Strategy

### MVP first (US1 only)
1. Setup (T001) → Foundational (T002–T006) → US1 (T007–T011).
2. STOP and validate: register subjects, run `sbt schemaRegistryListSubjects`, confirm full sorted listing + clean failure on bad URL.
3. Ship MVP.

### Incremental delivery
- + US2 (filter) → validate narrowing → ship.
- + US3 (versions/compat debugging detail) → validate `(default)` + ranges → ship.
- Each increment keeps existing scripted tests green (backward compat).

---

## Notes
- Test-first: confirm each FAILING test fails before its implementation task.
- No cats — use `Try`/`Either`/`foldLeft`; `scala.collection.JavaConverters` for Java collections (2.12).
- `-Xfatal-warnings`: no unused imports / warnings.
- Run `scalafmtAll`/`scalafmtSbt` before any commit (sub-agents too).
- Commit after each task or logical group; keep commits green and semantic.
