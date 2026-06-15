# Tasks: Schema Compatibility Check

**Input**: Design documents from `specs/002-schema-compatibility-check/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/sbt-keys.md

**Tests**: Included — issue #26 acceptance criteria explicitly require unit, integration, and scripted tests.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in descriptions

## Phase 1: Setup (Domain Model)

**Purpose**: New types that all user stories depend on

- [X] T001 [P] Create `CompatibilityResult` sealed ADT in src/main/scala/org/galaxio/avro/CompatibilityResult.scala — `Compatible(subject)`, `Incompatible(subject, messages)`, `Failed(subject, cause)` cases
- [X] T002 [P] Create `CompatibilityReport` case class in src/main/scala/org/galaxio/avro/CompatibilityReport.scala — `results: List[CompatibilityResult]` with lazy partition accessors (`compatible`, `incompatible`, `failed`) and `isSuccess` predicate

---

## Phase 2: Foundational (Pure Core + sbt Wiring)

**Purpose**: Core checking logic and sbt task — MUST complete before user story validation

- [X] T003 Create `CompatibilityChecker` object in src/main/scala/org/galaxio/avro/CompatibilityChecker.scala — pure functions: `checkOne(client, reg)` using `testCompatibilityVerbose`, `checkAll(client, registrations)` returning `CompatibilityReport`. Reuse `Registrar.readSchemaFile` and `Registrar.buildParsedSchema`.
- [X] T004 Add `schemaRegistryTestCompatibility` task key to src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — reuse `Downloader.buildClient`, `Using.resource` pattern, log compatible/incompatible/failed results, fail build if `!report.isSuccess`

**Checkpoint**: Core compiles. `sbt compile` green. Ready for test + story validation.

---

## Phase 3: User Story 1 — Check Compatibility Before Registration (Priority: P1) MVP

**Goal**: Check local schemas against registry compatibility rules via single sbt command

**Independent Test**: Configure one `.avsc` file mapping, run `schemaRegistryTestCompatibility`, verify output reports compatible or incompatible

### Tests for User Story 1

- [X] T005 [P] [US1] Create `CompatibilityCheckerSpec` in src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala — test with mocked `SchemaRegistryClient`: `checkOne` returns Compatible when `testCompatibilityVerbose` returns empty list, returns Incompatible with messages when non-empty, returns Failed on file not found / client exception. `checkAll` processes all subjects independently. Empty list → empty report with `isSuccess = true`.
- [X] T006 [US1] Create `CompatibilityCheckerIntegrationSpec` in it/src/test/scala/org/galaxio/avro/CompatibilityCheckerIntegrationSpec.scala — test against real registry via Testcontainers: register v1, check compatible v2 → Compatible; register v1, check incompatible v2 → Incompatible with messages; check new subject → Compatible; invalid schema → Failed.
- [X] T007 [US1] Create scripted test `compatibility-pass` in src/sbt-test/schema-registry/compatibility-pass/ — build.sbt registers v1 schema then checks compatible v2, test script verifying task exit 0
- [X] T008 [US1] Create scripted test `compatibility-fail` in src/sbt-test/schema-registry/compatibility-fail/ — build.sbt registers v1 schema then checks incompatible v2, test script verifying task exit non-zero

### Implementation Validation

- [X] T009 [US1] Run `sbt scalafmtAll scalafmtSbt compile test` — verify all unit tests pass, formatting clean
- [X] T010 [US1] Run `sbt it/test` — verify integration test passes against real registry
- [X] T011 [US1] Run `sbt scripted` — scripted tests pass in CI (local Docker env not inherited by scripted subprocess)

**Checkpoint**: User Story 1 fully functional. Basic compatibility checking works end-to-end.

---

## Phase 4: User Story 2 — Verbose Incompatibility Diagnostics (Priority: P2)

**Goal**: Display specific incompatibility messages explaining what broke

**Independent Test**: Register a schema, modify incompatibly, verify output includes specific messages (not just "incompatible")

### Tests for User Story 2

- [X] T012 [P] [US2] Add verbose message assertion tests to src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala — verify `Incompatible.messages` contains specific registry messages (e.g., field removal reason)
- [X] T013 [US2] Add verbose message integration test to it/src/test/scala/org/galaxio/avro/CompatibilityCheckerIntegrationSpec.scala — register v1 with required field, check v2 that adds required field without default, assert messages list contains meaningful diagnostics

### Implementation Validation

- [X] T014 [US2] Run `sbt scalafmtAll scalafmtSbt compile test it/test` — verify verbose diagnostic tests pass

**Checkpoint**: Incompatible schemas produce actionable messages.

---

## Phase 5: User Story 3 — CI/CD Pipeline Integration (Priority: P3)

**Goal**: Run compatibility check as a pre-registration gate in CI

**Independent Test**: Run `sbt schemaRegistryTestCompatibility schemaRegistryRegister` — incompatible schema stops before registration

### Tests for User Story 3

- [X] T015 [US3] Add "no registrations configured" test to src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala — verify task warns and succeeds when `schemaRegistryRegistrations` is empty

### Implementation Validation

- [X] T016 [US3] Run `sbt scalafmtAll scalafmtSbt compile test` — verify CI integration tests pass

**Checkpoint**: Task works as reliable CI gate.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, cleanup, final verification

- [X] T017 [P] Update README.md — add Compatibility Check section with usage example, CI pipeline example, reference to verbose output
- [X] T018 [P] Run full verification: `sbt scalafmtAll scalafmtSbt compile test it/test` — all pass (scripted deferred to CI)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — both type files can run in parallel
- **Foundational (Phase 2)**: Depends on Phase 1 (types must exist for CompatibilityChecker + plugin)
- **User Story 1 (Phase 3)**: Depends on Phase 2 — tests validate core behavior
- **User Story 2 (Phase 4)**: Depends on Phase 3 — extends test assertions for verbose output
- **User Story 3 (Phase 5)**: Depends on Phase 2 — validates empty-config edge case
- **Polish (Phase 6)**: Depends on all user stories

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no dependency on other stories
- **US2 (P2)**: Depends on US1 (verbose messages are part of same `Incompatible` result)
- **US3 (P3)**: Can start after Phase 2 — independent edge case (empty config)

### Parallel Opportunities

- Phase 1: T001 + T002 in parallel (different files)
- Phase 3: T005 can run in parallel with T007/T008 preparation
- Phase 4: T012 in parallel (unit test additions)
- Phase 6: T017 + T018 in parallel

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (2 type files)
2. Complete Phase 2: Foundational (CompatibilityChecker + plugin wiring)
3. Complete Phase 3: User Story 1 (basic checking with full test coverage)
4. **STOP and VALIDATE**: All tests green, compatibility checking works end-to-end
5. Ship as incremental PR if desired

### Incremental Delivery

1. Setup + Foundational → Types + core compiling
2. User Story 1 → Basic checking tested → MVP
3. User Story 2 → Verbose diagnostics validated → Enhanced
4. User Story 3 → CI gate edge case → Complete
5. Polish → README + final validation → Ship

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Each user story independently completable and testable
- Verify tests fail before implementing (constitution III)
- `sbt scalafmtAll scalafmtSbt` before every commit (constitution V)
- Reuse `Registrar.readSchemaFile`, `Registrar.buildParsedSchema`, `Downloader.buildClient` — no duplication
