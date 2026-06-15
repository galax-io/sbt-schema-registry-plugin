# Tasks: Register (Push) Schemas to Registry

**Input**: Design documents from `specs/001-register-schemas/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/sbt-keys.md

**Tests**: Included — issue #25 acceptance criteria explicitly require unit, integration, and scripted tests.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in descriptions

## Phase 1: Setup (Domain Model)

**Purpose**: New types that all user stories depend on

- [x] T001 [P] Create `SchemaType` sealed ADT in src/main/scala/org/galaxio/avro/SchemaType.scala — `Avro`, `Protobuf`, `Json` cases with `fromExtension` method returning `Either[RegistryError, SchemaType]`
- [x] T002 [P] Create `RegistryError` sealed ADT in src/main/scala/org/galaxio/avro/RegistryError.scala — `FileNotFound`, `FileReadFailed`, `RegistrationFailed`, `UnsupportedSchemaType` cases
- [x] T003 [P] Create `RegistryRegistration` case class in src/main/scala/org/galaxio/avro/RegistryRegistration.scala — `subject: String`, `file: File`, `schemaType: SchemaType = SchemaType.Avro`
- [x] T004 [P] Create `RegisteredSchema` case class in src/main/scala/org/galaxio/avro/RegisteredSchema.scala — `subject: String`, `schemaId: Int`

---

## Phase 2: Foundational (Pure Core + sbt Wiring)

**Purpose**: Core registration logic and sbt task — MUST complete before user story validation

- [x] T005 Create `Registrar` object in src/main/scala/org/galaxio/avro/Registrar.scala — pure functions: `readSchemaFile`, `registerAll`, `partitionResults` per research.md R3 decisions
- [x] T006 Add `schemaRegistryRegistrations` setting key and `schemaRegistryRegister` task key to src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala — reuse existing `buildClient` config, `Using.resource` pattern, log successes, collect and report all errors

**Checkpoint**: Core compiles. `sbt compile` green. Ready for test + story validation.

---

## Phase 3: User Story 1 — Register Local Avro Schemas (Priority: P1) MVP

**Goal**: Register Avro schema files from local filesystem to Schema Registry via single sbt command

**Independent Test**: Configure one `.avsc` file mapping, run `schemaRegistryRegister`, verify schema appears in registry

### Tests for User Story 1

- [x] T007 [P] [US1] Create `SchemaTypeSpec` in src/test/scala/org/galaxio/avro/SchemaTypeSpec.scala — test `fromExtension` for avsc→Avro, proto→Protobuf, json→Json, unknown→Left(UnsupportedSchemaType)
- [x] T008 [P] [US1] Create `RegistrarSpec` in src/test/scala/org/galaxio/avro/RegistrarSpec.scala — test with mocked `SchemaRegistryClient`: `readSchemaFile` success/failure, `registerAll` all-succeed/partial-fail/all-fail, `partitionResults` correct split, empty registrations list
- [x] T009 [US1] Create `RegistrarIntegrationSpec` in it/src/test/scala/org/galaxio/avro/RegistrarIntegrationSpec.scala — test against real registry via Testcontainers: register Avro schema → get ID, register same schema twice → idempotent, file not found → error
- [x] T010 [US1] Create scripted test `register-success` in src/sbt-test/schema-registry/register-success/ — build.sbt with one `RegistryRegistration`, test.avsc fixture, test script verifying task exit 0

### Implementation Validation

- [x] T011 [US1] Run `sbt scalafmtAll scalafmtSbt compile test` — verify all unit tests pass, formatting clean
- [x] T012 [US1] Run `sbt it/test` — verify integration test passes against real registry
- [x] T013 [US1] Run `sbt scripted` — verify register-success and all existing download tests pass

**Checkpoint**: User Story 1 fully functional. Avro registration works end-to-end.

---

## Phase 4: User Story 2 — Register Non-Avro Schema Types (Priority: P2)

**Goal**: Support Protobuf and JSON Schema registration alongside Avro

**Independent Test**: Configure a `.proto` file with `SchemaType.Protobuf`, run task, verify registered as Protobuf in registry

### Tests for User Story 2

- [x] T014 [P] [US2] Add Protobuf registration test to src/test/scala/org/galaxio/avro/RegistrarSpec.scala — verify `ProtobufSchema` constructed for `SchemaType.Protobuf`
- [x] T015 [P] [US2] Add JSON Schema registration test to src/test/scala/org/galaxio/avro/RegistrarSpec.scala — verify `JsonSchema` constructed for `SchemaType.Json`

### Implementation for User Story 2

- [x] T016 [US2] Update `Registrar.registerAll` in src/main/scala/org/galaxio/avro/Registrar.scala — construct `ProtobufSchema` / `JsonSchema` based on `SchemaType`, handle missing provider gracefully (classpath error message)
- [x] T017 [US2] Run `sbt scalafmtAll scalafmtSbt compile test` — verify multi-format unit tests pass

**Checkpoint**: All three schema types supported. Protobuf/JSON require user-added provider deps.

---

## Phase 5: User Story 3 — Round-Trip Verification (Priority: P3)

**Goal**: Prove register→download cycle produces identical schema content

**Independent Test**: Register schema, download same subject, diff files match

### Tests for User Story 3

- [x] T018 [US3] Create scripted test `register-then-download` in src/sbt-test/schema-registry/register-then-download/ — build.sbt registers schema, then downloads it, test script diffs original vs downloaded file

### Implementation Validation

- [x] T019 [US3] Run `sbt scripted` — verify round-trip test passes alongside all existing tests

**Checkpoint**: Full round-trip verified. Feature complete.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, cleanup, final verification

- [x] T020 [P] Update README.md — add Registration section with usage example, `SchemaType` docs, troubleshooting for registration errors
- [x] T021 [P] Run full verification: `sbt scalafmtCheckAll scalafmtSbtCheck compile test it/test scripted`
- [x] T022 Run quickstart.md validation scenarios from specs/001-register-schemas/quickstart.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — all 4 type files can run in parallel
- **Foundational (Phase 2)**: Depends on Phase 1 (types must exist for Registrar + plugin)
- **User Story 1 (Phase 3)**: Depends on Phase 2 — tests validate core behavior
- **User Story 2 (Phase 4)**: Depends on Phase 2 — extends Registrar with multi-format
- **User Story 3 (Phase 5)**: Depends on Phase 3 (needs working register task)
- **Polish (Phase 6)**: Depends on all user stories

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no dependency on other stories
- **US2 (P2)**: Can start after Phase 2 — independent of US1 (extends same code but different path)
- **US3 (P3)**: Depends on US1 (needs working registration to do round-trip)

### Within Each User Story

- Tests written and verified to FAIL before implementation fixes them
- Models before services
- Core logic before sbt wiring
- Unit tests before integration before scripted

### Parallel Opportunities

- Phase 1: All 4 type files (T001–T004) in parallel
- Phase 3: T007 + T008 in parallel (unit tests for different files)
- Phase 4: T014 + T015 in parallel (independent test additions)

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (4 type files)
2. Complete Phase 2: Foundational (Registrar + plugin wiring)
3. Complete Phase 3: User Story 1 (Avro registration with full test coverage)
4. **STOP and VALIDATE**: All tests green, Avro registration works end-to-end
5. Ship as incremental PR if desired

### Incremental Delivery

1. Setup + Foundational → Types + core compiling
2. User Story 1 → Avro registration tested → MVP
3. User Story 2 → Multi-format support → Enhanced
4. User Story 3 → Round-trip verified → Complete
5. Polish → README + final validation → Ship

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Each user story independently completable and testable
- Verify tests fail before implementing (constitution III)
- `sbt scalafmtAll scalafmtSbt` before every commit (constitution V)
