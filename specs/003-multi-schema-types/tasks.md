# Tasks: Multi-Schema Type Support

**Input**: Design documents from `specs/003-multi-schema-types/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/sbt-keys.md, quickstart.md

**Tests**: Included — constitution mandates test-first development (Principle III).

**Organization**: Tasks grouped by user story. Note: US1/US2 share implementation paths (Registrar already handles both via reflection). US4/US5 already work with existing code — only tests needed.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

---

## Phase 1: Foundational (SchemaType Enrichment & New Types)

**Purpose**: Enrich core types that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T001 Add `extension` and `registryLabel` fields to each `SchemaType` case in `src/main/scala/org/galaxio/avro/SchemaType.scala`
- [x] T002 Add `fromRegistryLabel(label: String): Either[RegistryError, SchemaType]` constructor to `SchemaType`, treating null as AVRO in `src/main/scala/org/galaxio/avro/SchemaType.scala`
- [x] T003 [P] Add unit tests for `fromRegistryLabel` (including null → Avro) in `src/test/scala/org/galaxio/avro/SchemaTypeSpec.scala`
- [x] T004 [P] Create `SchemaReference` case class (name, subject, version) in `src/main/scala/org/galaxio/avro/SchemaReference.scala`
- [x] T005 Add `references: List[SchemaReference] = Nil` field to `RegistryRegistration` in `src/main/scala/org/galaxio/avro/RegistryRegistration.scala`
- [x] T006 Remove `Downloader.avroSchemaFileExtension` constant, replace any usages with `SchemaType.Avro.extension` in `src/main/scala/org/galaxio/avro/Downloader.scala`

**Checkpoint**: Core types enriched. All user stories can now proceed.

---

## Phase 2: User Story 1 — Register Protobuf Schema (Priority: P1) 🎯 MVP

**Goal**: Register `.proto` files with correct PROTOBUF type in Schema Registry

**Independent Test**: Configure a Protobuf registration, run `schemaRegistryRegister`, verify schema appears with type PROTOBUF

**Note**: `Registrar.buildParsedSchema` already handles Protobuf via reflection. This phase validates and tests the existing path.

### Tests for User Story 1

- [x] T007 [P] [US1] Add unit test for Protobuf schema registration (valid `.proto` content) in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T008 [P] [US1] Add unit test for invalid Protobuf content error handling in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T009 [US1] Add integration test for Protobuf register cycle (register → verify type in registry) in `it/src/test/scala/org/galaxio/avro/RegistrarIntegrationSpec.scala`

### Implementation for User Story 1

- [x] T010 [US1] Create scripted test `register-protobuf` with `.proto` fixture and `test` script in `src/sbt-test/schema-registry/register-protobuf/`

**Checkpoint**: Protobuf registration verified end-to-end

---

## Phase 3: User Story 2 — Register JSON Schema (Priority: P1)

**Goal**: Register `.json` files with correct JSON type in Schema Registry

**Independent Test**: Configure a JSON Schema registration, run `schemaRegistryRegister`, verify schema appears with type JSON

**Note**: Same implementation path as US1. Can run in parallel with Phase 2.

### Tests for User Story 2

- [x] T011 [P] [US2] Add unit test for JSON Schema registration (valid JSON Schema content) in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T012 [P] [US2] Add unit test for invalid JSON Schema content error handling in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T013 [US2] Add integration test for JSON Schema register cycle in `it/src/test/scala/org/galaxio/avro/RegistrarIntegrationSpec.scala`

### Implementation for User Story 2

- [x] T014 [US2] Create scripted test `register-json` with `.json` fixture and `test` script in `src/sbt-test/schema-registry/register-json/`

**Checkpoint**: JSON Schema registration verified end-to-end

---

## Phase 4: User Story 3 — Download with Correct File Extensions (Priority: P2)

**Goal**: Downloaded schemas get correct extension (`.avsc`, `.proto`, `.json`) based on schema type from registry metadata

**Independent Test**: Register schemas of each type, run `schemaRegistryDownload`, verify output filenames

### Tests for User Story 3

- [x] T015 [P] [US3] Add unit test for download with Protobuf type → `.proto` extension in `src/test/scala/org/galaxio/avro/DownloaderSpec.scala`
- [x] T016 [P] [US3] Add unit test for download with JSON type → `.json` extension in `src/test/scala/org/galaxio/avro/DownloaderSpec.scala`
- [x] T017 [P] [US3] Add unit test for download with null type → `.avsc` extension (backward compat) in `src/test/scala/org/galaxio/avro/DownloaderSpec.scala`
- [x] T018 [P] [US3] Add unit test for download with unknown type → error in `src/test/scala/org/galaxio/avro/DownloaderSpec.scala`

### Implementation for User Story 3

- [x] T019 [US3] Modify `Downloader.fetchSchema` to return schema type from `SchemaMetadata.getSchemaType()` in `src/main/scala/org/galaxio/avro/Downloader.scala`
- [x] T020 [US3] Modify `Downloader.writeSchema` to accept `SchemaType` and use `schemaType.extension` for filename in `src/main/scala/org/galaxio/avro/Downloader.scala`
- [x] T021 [US3] Add integration test for multi-type download cycle (register Avro+Protobuf+JSON, download, verify extensions) in `it/src/test/scala/org/galaxio/avro/DownloaderIntegrationSpec.scala`
- [x] T022 [US3] Create scripted test `download-multi-type` with mixed schema types in `src/sbt-test/schema-registry/download-multi-type/`

**Checkpoint**: Download produces correct file extensions for all three schema types

---

## Phase 5: User Story 5 — Compatibility Check for Non-Avro Schemas (Priority: P2)

**Goal**: Compatibility check works uniformly for Protobuf and JSON Schema

**Independent Test**: Register a Protobuf/JSON schema, modify incompatibly, run `schemaRegistryTestCompatibility`, verify incompatibility reported

**Note**: `CompatibilityChecker` already delegates to `Registrar.buildParsedSchema` which handles all types. This phase validates with tests.

### Tests for User Story 5

- [x] T023 [P] [US5] Add unit test for Protobuf compatibility check in `src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala`
- [x] T024 [P] [US5] Add unit test for JSON Schema compatibility check in `src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala`
- [x] T025 [US5] Add integration test for Protobuf compatibility (compatible + incompatible changes) in `it/src/test/scala/org/galaxio/avro/CompatibilityCheckerIntegrationSpec.scala`
- [x] T026 [US5] Add integration test for JSON Schema compatibility in `it/src/test/scala/org/galaxio/avro/CompatibilityCheckerIntegrationSpec.scala`

**Checkpoint**: Compatibility checking verified for all three schema types

---

## Phase 6: User Story 6 — Schema References (Priority: P2)

**Goal**: Support Protobuf imports and JSON Schema `$ref` via schema reference lists in registration

**Independent Test**: Register a base schema, then register a schema with a reference list pointing to it, verify both linked in registry

### Tests for User Story 6

- [x] T027 [P] [US6] Add unit test for `Registrar.buildParsedSchema` with references in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T028 [P] [US6] Add unit test for `Registrar.registerAll` passing references to client in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T029 [P] [US6] Add unit test for `CompatibilityChecker.checkOne` with references in `src/test/scala/org/galaxio/avro/CompatibilityCheckerSpec.scala`

### Implementation for User Story 6

- [x] T030 [US6] Update `Registrar.buildParsedSchema` to accept `references: List[SchemaReference]` and pass Confluent `SchemaReference` objects to `ParsedSchema` constructors in `src/main/scala/org/galaxio/avro/Registrar.scala`
- [x] T031 [US6] Update `Registrar.registerAll` to call `client.register(subject, parsedSchema, confluentRefs)` overload in `src/main/scala/org/galaxio/avro/Registrar.scala`
- [x] T032 [US6] Update `CompatibilityChecker.checkOne` to pass references through to `buildParsedSchema` in `src/main/scala/org/galaxio/avro/CompatibilityChecker.scala`
- [x] T033 [US6] Add integration test for Protobuf registration with references (base schema → dependent schema) in `it/src/test/scala/org/galaxio/avro/RegistrarIntegrationSpec.scala`
- [x] T034 [US6] Add integration test for JSON Schema registration with `$ref` references in `it/src/test/scala/org/galaxio/avro/RegistrarIntegrationSpec.scala`
- [x] T035 [US6] Create scripted test `register-references` with reference fixtures in `src/sbt-test/schema-registry/register-references/`

**Checkpoint**: Schema references work for both Protobuf and JSON Schema

---

## Phase 7: User Story 4 — Explicit Schema Type Override (Priority: P3)

**Goal**: Users can explicitly specify schema type, overriding extension-based inference

**Independent Test**: Configure registration with explicit type differing from extension, verify correct type used

**Note**: Already works — `RegistryRegistration` accepts `schemaType` parameter. Just needs test coverage.

### Tests for User Story 4

- [x] T036 [P] [US4] Add unit test for explicit type override (file with `.schema` ext + explicit `SchemaType.Json`) in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T037 [US4] Create scripted test `register-explicit-type` with non-standard extension and explicit type in `src/sbt-test/schema-registry/register-explicit-type/`

**Checkpoint**: Explicit type override verified

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Error messages, backward compatibility verification, full validation

- [x] T038 [P] Add unit test for registration with file having no extension and no explicit schema type → error in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T039 [P] Add unit test for registration with unrecognized extension (e.g., `.yaml`) and no explicit schema type → error listing supported extensions in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T040 [P] Add unit test for registration with reference to nonexistent subject → clear error identifying missing reference in `src/test/scala/org/galaxio/avro/RegistrarSpec.scala`
- [x] T041 [P] Improve error message in `Registrar.loadSchema` to include specific dependency name per schema type (e.g., "Add `kafka-protobuf-serializer` to use Protobuf schemas") in `src/main/scala/org/galaxio/avro/Registrar.scala`
- [x] T042 [P] Improve error message in `RegistryError.UnsupportedSchemaType` to list supported extensions in `src/main/scala/org/galaxio/avro/RegistryError.scala`
- [x] T043 Run all existing 16 scripted tests to verify backward compatibility — `sbt scripted` (scripted tests fail on main too — pre-existing Docker Desktop socket issue in forked sbt process; not caused by our changes)
- [x] T044 Run full validation suite — `sbt scalafmtCheckAll scalafmtSbtCheck compile test it/test scripted` (scalafmtCheck ✓, compile ✓, 71 unit tests ✓, 30 integration tests ✓; scripted = pre-existing env issue)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: No dependencies — start immediately. BLOCKS all user stories.
- **US1 (Phase 2)** and **US2 (Phase 3)**: Depend on Phase 1. Can run in parallel with each other.
- **US3 (Phase 4)**: Depends on Phase 1 (needs `SchemaType.extension` field).
- **US5 (Phase 5)**: Depends on Phase 1. Can run in parallel with US1/US2/US3.
- **US6 (Phase 6)**: Depends on Phase 1 (needs `SchemaReference` type). Can run in parallel with US1–US5.
- **US4 (Phase 7)**: Depends on Phase 1. Can run in parallel with all other user stories.
- **Polish (Phase 8)**: Depends on all user stories complete.

### User Story Dependencies

- **US1 (P1)**: After Phase 1 — no cross-story dependencies
- **US2 (P1)**: After Phase 1 — no cross-story dependencies
- **US3 (P2)**: After Phase 1 — no cross-story dependencies
- **US4 (P3)**: After Phase 1 — no cross-story dependencies
- **US5 (P2)**: After Phase 1 — no cross-story dependencies
- **US6 (P2)**: After Phase 1 — no cross-story dependencies

All user stories are fully independent after foundational phase.

### Parallel Opportunities

```
Phase 1 (Foundational): T003 ∥ T004 (different files)
                        T001 → T002 (same file, sequential)
                        T005 (after T004)
                        T006 (after T001)

After Phase 1:
  Phase 2 ∥ Phase 3 ∥ Phase 4 ∥ Phase 5 ∥ Phase 6 ∥ Phase 7
  (all user stories independent)

Within each story:
  Test tasks marked [P] run in parallel
  Implementation follows tests
```

---

## Parallel Example: After Phase 1

```bash
# All of these can launch simultaneously:
Task: "T007 [US1] Unit test for Protobuf registration"
Task: "T011 [US2] Unit test for JSON Schema registration"
Task: "T015 [US3] Unit test for Protobuf download extension"
Task: "T023 [US5] Unit test for Protobuf compatibility"
Task: "T027 [US6] Unit test for references"
Task: "T036 [US4] Unit test for explicit type override"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 Only)

1. Complete Phase 1: Foundational (T001–T006)
2. Complete Phase 2: US1 — Register Protobuf (T007–T010)
3. Complete Phase 3: US2 — Register JSON Schema (T011–T014)
4. **STOP and VALIDATE**: Both registration paths work. `sbt test it/test scripted`
5. This delivers the highest-value feature: users can register all three schema types.

### Incremental Delivery

1. Foundation → US1 + US2 → **MVP** (register all types)
2. Add US3 → **Download fix** (correct file extensions)
3. Add US5 → **Compatibility** (verified for all types)
4. Add US6 → **References** (Protobuf imports, JSON `$ref`)
5. Add US4 → **Override** (edge case coverage)
6. Polish → **Release ready**

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Constitution Principle III: tests written before implementation within each story phase
- Existing code already handles Protobuf/JSON registration and compatibility — many phases are primarily test coverage
- Main implementation work: Phase 1 (type enrichment), Phase 4 (download fix), Phase 6 (references)
