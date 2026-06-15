# Feature Specification: Register (Push) Schemas to Registry

**Feature Branch**: `feat/register-schemas`

**Created**: 2026-06-14

**Status**: Draft

**Input**: User description: "feat: register (push) schemas to registry (issue #25)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register Local Avro Schemas (Priority: P1)

A developer has Avro schema files in their project and wants to push them
to Confluent Schema Registry as part of the build, so schema evolution is
managed as code rather than via manual curl scripts.

**Why this priority**: Core use case — without this, the feature has no
value. Mirrors what Gradle/Maven plugins already provide.

**Independent Test**: Configure one schema file mapping, run
`schemaRegistryRegister`, verify the schema appears in the registry with
the correct subject name and content.

**Acceptance Scenarios**:

1. **Given** a valid Avro schema file and a configured subject mapping,
   **When** `schemaRegistryRegister` runs,
   **Then** the schema is registered under the specified subject and a
   schema ID is returned.

2. **Given** multiple schema registrations configured,
   **When** `schemaRegistryRegister` runs,
   **Then** all schemas are registered and each registration is logged
   with subject name and schema ID.

3. **Given** a schema file that does not exist at the configured path,
   **When** `schemaRegistryRegister` runs,
   **Then** the build fails with a clear error message naming the missing
   file.

---

### User Story 2 - Register Non-Avro Schema Types (Priority: P2)

A developer uses Protobuf or JSON Schema and wants to register those
schema types alongside or instead of Avro.

**Why this priority**: Multi-format support is expected by teams using
Confluent Schema Registry in production, but Avro-only covers the
majority use case first.

**Independent Test**: Configure a `.proto` file with `SchemaType.Protobuf`,
run the task, verify it registers with the correct schema type in the
registry.

**Acceptance Scenarios**:

1. **Given** a Protobuf schema file with `SchemaType.Protobuf` configured,
   **When** `schemaRegistryRegister` runs,
   **Then** the schema is registered as a Protobuf schema.

2. **Given** a JSON Schema file with `SchemaType.Json` configured,
   **When** `schemaRegistryRegister` runs,
   **Then** the schema is registered as a JSON schema.

3. **Given** a file with an unsupported extension and no explicit schema type,
   **When** the registration is attempted,
   **Then** the build fails with a clear "unsupported schema type" error.

---

### User Story 3 - Round-Trip Verification (Priority: P3)

A CI pipeline registers schemas and then downloads them to verify content
integrity — ensuring the register-then-download cycle produces identical
schema content.

**Why this priority**: Validates end-to-end correctness and proves the
register task integrates cleanly with the existing download task.

**Independent Test**: Register a schema, download it via the existing
`schemaRegistryDownload` task, diff the original file against the
downloaded file.

**Acceptance Scenarios**:

1. **Given** a schema registered via `schemaRegistryRegister`,
   **When** `schemaRegistryDownload` fetches the same subject and version,
   **Then** the downloaded content matches the original file byte-for-byte.

---

### Edge Cases

- Schema file exists but is empty (0 bytes) — should fail with clear error
- Schema file contains invalid schema syntax — registry rejects it, error
  should surface the registry's validation message
- Subject name contains special characters — should pass through to registry
  as-is (registry handles validation)
- Network timeout during registration — error should identify which subject
  failed and the underlying cause
- Registry returns a conflict (incompatible schema evolution) — error should
  surface the compatibility violation message

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a `schemaRegistryRegister` sbt task that
  pushes local schema files to Confluent Schema Registry
- **FR-002**: System MUST accept a list of subject-to-file mappings via a
  `schemaRegistryRegistrations` sbt setting
- **FR-003**: System MUST support Avro, Protobuf, and JSON Schema types
- **FR-004**: System MUST reuse existing authentication and connection
  settings (`schemaRegistryUrl`, `schemaRegistryAuth`, `schemaRegistryProperties`)
- **FR-005**: System MUST report each successful registration with subject
  name and assigned schema ID
- **FR-006**: System MUST fail the build if any registration fails, reporting
  all failures (not stopping at the first)
- **FR-007**: System MUST use typed errors (sealed ADT) for all failure modes
- **FR-008**: System MUST separate pure logic (file reading, result
  partitioning) from effectful operations (logging, client calls)

### Key Entities

- **RegistryRegistration**: Maps a subject name to a local file path and
  schema type (default: Avro)
- **RegisteredSchema**: Result of successful registration — subject, schema
  ID, version
- **RegistryError**: Sealed ADT representing failure modes — file not found,
  registration failed, unsupported type
- **SchemaType**: Sealed ADT — Avro, Protobuf, Json

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can register schemas from local files in a single sbt
  command without external tools or scripts
- **SC-002**: Registration of 10 schemas completes within 30 seconds on a
  standard network connection
- **SC-003**: Error messages identify the exact file and subject that failed,
  enabling fix without debugging
- **SC-004**: Round-trip (register then download) produces identical schema
  content for all supported schema types
- **SC-005**: Existing download functionality remains unaffected (no
  regressions in `schemaRegistryDownload`)

## Assumptions

- Schema Registry is accessible at the configured URL when the task runs
- Users have write permissions on the registry for the subjects they register
- Schema files are UTF-8 encoded text
- The existing `buildClient` connection setup (auth, properties, SSL) works
  identically for registration as it does for download
- Schema compatibility mode is configured on the registry side (not managed
  by this plugin)
- Default schema type is Avro when not explicitly specified
