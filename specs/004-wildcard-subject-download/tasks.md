# Tasks: Wildcard Subject Download

**Input**: Design documents from `specs/004-wildcard-subject-download/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/sbt-keys.md, quickstart.md

**Tests**: Required by Constitution Principle III (Test-First Development). Tests written before implementation.

**Organization**: Tasks grouped by user story. US1 and US2 are both P1 but US2 depends on US1's core implementation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: No new project setup needed — existing project structure. This phase covers only the new source files.

- [x] T001 [P] Create `SubjectSpec` sealed ADT (Exact | Pattern) in `src/main/scala/org/galaxio/avro/SubjectSpec.scala`
- [x] T002 [P] Create `DownloadPlan` case class with `subjects: List[RegistrySubject]` in `src/main/scala/org/galaxio/avro/DownloadPlan.scala`
- [x] T003 Add `InvalidPattern` and `SubjectListFailed` cases to `src/main/scala/org/galaxio/avro/DownloadError.scala`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `SubjectResolver` core and sbt key — blocks all user story verification

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Write unit tests for `SubjectResolver.resolve` — pattern compilation, full-match semantics, invalid regex fail-fast — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala`. Tests MUST fail before T005.
- [x] T005 Implement `SubjectResolver` object with `resolve(client: SchemaRegistryClient, specs: List[SubjectSpec]): Either[DownloadError, DownloadPlan]` in `src/main/scala/org/galaxio/avro/SubjectResolver.scala`. Uses `client.getAllSubjects()`, full-match regex via `Regex.pattern.matcher(s).matches()`, fail-fast on invalid pattern, exact-before-pattern dedup by name.
- [x] T006 Add `schemaRegistrySubjectPatterns` setting key (type `Seq[String]`, default `Seq.empty`) to `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` in `autoImport` and `defaultSettings`.

**Checkpoint**: Foundation ready — data types, resolver, and sbt key exist. User story implementation can begin.

---

## Phase 3: User Story 1 — Download Schemas by Regex Pattern (Priority: P1) 🎯 MVP

**Goal**: Users configure regex patterns via `schemaRegistrySubjectPatterns` and download matching schemas at latest version.

**Independent Test**: Register 3 subjects in Schema Registry, configure 1 pattern matching 2 of them, run download, verify only 2 schema files appear in output directory.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T007 [US1] Write integration test: register 3 subjects, resolve pattern matching 2, verify `DownloadPlan` contains exactly 2 latest subjects — in `it/src/test/scala/org/galaxio/avro/SubjectResolverIntegrationSpec.scala`
- [x] T008 [US1] Write integration test: pattern matches zero subjects, verify empty plan (no error) — in `it/src/test/scala/org/galaxio/avro/SubjectResolverIntegrationSpec.scala`
- [x] T009 [US1] Write unit test: invalid regex pattern returns `Left(InvalidPattern(...))` — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala` (extend from T004)

### Implementation for User Story 1

- [x] T010 [US1] Wire `SubjectResolver` into `Compile / schemaRegistryDownload` task in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala`: when `schemaRegistrySubjectPatterns` is non-empty, build `SubjectSpec.Pattern` list, call `SubjectResolver.resolve`, then download each subject in the resulting plan. Log resolved subject count.
- [x] T011 [US1] Handle patterns-only flow: when `schemaRegistrySubjects` is empty but `schemaRegistrySubjectPatterns` is non-empty, skip the "no subjects configured" warning and proceed with pattern resolution.
- [x] T012 [US1] Run `sbt scalafmtAll scalafmtSbt` and verify `sbt compile test` passes.

**Checkpoint**: User Story 1 fully functional — pattern-only download works end-to-end.

---

## Phase 4: User Story 2 — Combine Exact Subjects with Pattern Subjects (Priority: P1)

**Goal**: Users configure both `schemaRegistrySubjects` (exact, possibly pinned) and `schemaRegistrySubjectPatterns` (regex, latest). Downloads merge both with exact taking precedence on overlap.

**Independent Test**: Register subjects, configure one exact subject at pinned version and one pattern that also matches it, verify the pinned version is downloaded (not latest).

### Tests for User Story 2

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T013 [US2] Write unit test: exact subjects appear before pattern subjects in `DownloadPlan`, dedup by name keeps first (exact wins) — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala`
- [x] T014 [US2] Write integration test: configure exact `Pinned("com.myorg.User-value", 3)` + pattern `"com\\.myorg\\..*-value"`, verify `User-value` resolved at version 3 (not latest) and other matches at latest — in `it/src/test/scala/org/galaxio/avro/SubjectResolverIntegrationSpec.scala`
- [x] T015 [US2] Write unit test: when only `schemaRegistrySubjects` configured (no patterns), no `getAllSubjects` call is made — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala`

### Implementation for User Story 2

- [x] T016 [US2] Wire combined flow in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala`: build `SubjectSpec.Exact` from `schemaRegistrySubjects.value` + `SubjectSpec.Pattern` from `schemaRegistrySubjectPatterns.value`, pass combined list to `SubjectResolver.resolve`. When patterns list is empty, skip resolver entirely and use subjects directly (backward compatible).
- [x] T017 [US2] Run `sbt scalafmtAll scalafmtSbt` and verify `sbt compile test it/test` passes.

**Checkpoint**: User Stories 1 AND 2 both work — exact+pattern composition verified.

---

## Phase 5: User Story 3 — Multiple Patterns (Priority: P2)

**Goal**: Users configure multiple regex patterns. Plugin unions all matches and deduplicates.

**Independent Test**: Register subjects across 2 namespaces, configure 2 patterns each matching a different namespace, verify union is downloaded without duplicates.

### Tests for User Story 3

- [x] T018 [P] [US3] Write unit test: two patterns matching different subjects, verify union in `DownloadPlan` — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala`
- [x] T019 [P] [US3] Write unit test: two patterns matching same subject, verify deduplicated to one entry — in `src/test/scala/org/galaxio/avro/SubjectResolverSpec.scala`
- [x] T020 [US3] Write integration test: 2 patterns across namespaces, verify correct union downloaded — in `it/src/test/scala/org/galaxio/avro/SubjectResolverIntegrationSpec.scala`

### Implementation for User Story 3

- [x] T021 [US3] Verify existing `SubjectResolver.resolve` handles multiple patterns correctly (should work from US1 implementation — `specs.traverse` over pattern list). Add per-pattern match count logging in `src/main/scala/org/galaxio/avro/SubjectResolver.scala`.
- [x] T022 [US3] Run `sbt scalafmtAll scalafmtSbt` and verify `sbt compile test it/test` passes.

**Checkpoint**: All user stories functional and independently tested.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: E2E validation, formatting, final verification

- [x] T023 [P] Create scripted e2e test in `src/sbt-test/schema-registry/download-wildcard/` — test sbt project with `schemaRegistrySubjectPatterns` configured, verify schemas downloaded correctly.
- [x] T024 Run full verification: `sbt scalafmtCheckAll scalafmtSbtCheck compile test` and `sbt it/test` and `sbt scripted`.
- [x] T025 Run `specs/004-wildcard-subject-download/quickstart.md` validation scenarios.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately. T001 and T002 are parallel.
- **Foundational (Phase 2)**: Depends on Phase 1. T004 depends on T001+T002. T005 depends on T004. T006 is parallel with T004/T005.
- **US1 (Phase 3)**: Depends on Phase 2 (T005, T006 complete)
- **US2 (Phase 4)**: Depends on US1 (Phase 3) — extends plugin wiring
- **US3 (Phase 5)**: Depends on US1 (Phase 3) — uses same resolver, adds multi-pattern tests
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Start after Foundational — core pattern matching
- **US2 (P1)**: Start after US1 — composition logic builds on US1's wiring
- **US3 (P2)**: Start after US1 — can run parallel with US2

### Within Each User Story

- Tests written FIRST and MUST fail before implementation (Constitution III)
- Models/data types before services/logic
- Core logic before plugin wiring
- Format check after each story completes

### Parallel Opportunities

- T001 and T002 (Setup data types) run in parallel
- T018 and T019 (US3 unit tests) run in parallel
- T023 (scripted test) can run in parallel with T024/T025
- US2 and US3 can start in parallel after US1 completes

---

## Parallel Example: Phase 1

```
Task: "Create SubjectSpec sealed ADT in src/main/scala/org/galaxio/avro/SubjectSpec.scala"
Task: "Create DownloadPlan case class in src/main/scala/org/galaxio/avro/DownloadPlan.scala"
```

## Parallel Example: User Story 3 Tests

```
Task: "Write unit test: two patterns matching different subjects"
Task: "Write unit test: two patterns matching same subject"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T006)
3. Complete Phase 3: User Story 1 (T007-T012)
4. **STOP and VALIDATE**: Test pattern-only download independently
5. Merge if ready — delivers core value

### Incremental Delivery

1. Setup + Foundational → data types and resolver ready
2. Add US1 → pattern download works → **MVP!**
3. Add US2 → exact+pattern composition → full P1 scope
4. Add US3 → multiple patterns → full feature
5. Polish → e2e tests, formatting, final validation

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Constitution III requires test-first — all test tasks precede implementation
- `SubjectResolver` is pure logic (aside from client call) — highly testable with mocked client
- Commit after each task or logical group using Conventional Commits
- Run `sbt scalafmtAll scalafmtSbt` before each commit (Constitution V)
