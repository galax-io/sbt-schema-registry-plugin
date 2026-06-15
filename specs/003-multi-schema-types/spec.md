# Feature Specification: Multi-Schema Type Support

**Feature Branch**: `003-multi-schema-types`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "Support Protobuf and JSON Schema types in addition to Avro (GitHub issue #27)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register Protobuf Schema (Priority: P1)

A developer using Protobuf for Kafka serialization wants to register `.proto` schema files with Confluent Schema Registry through the sbt plugin. They configure a registration entry pointing to their `.proto` file, and the plugin automatically detects the schema type from the file extension and registers it as a PROTOBUF schema.

**Why this priority**: Protobuf is the second most popular schema format in the Kafka ecosystem after Avro. Teams using Protobuf are currently blocked from using this plugin entirely.

**Independent Test**: Can be fully tested by configuring a Protobuf schema registration, running the register task, and verifying the schema appears in Schema Registry with type PROTOBUF.

**Acceptance Scenarios**:

1. **Given** a `.proto` file configured for registration, **When** the user runs the register task, **Then** the schema is registered in Schema Registry with type PROTOBUF.
2. **Given** a `.proto` file configured for registration, **When** the schema content is invalid Protobuf, **Then** the plugin reports a clear error indicating the Protobuf schema is malformed.
3. **Given** a Protobuf schema already registered for a subject, **When** the user registers an updated version, **Then** the new version is registered and compatibility is checked according to the subject's compatibility level.

---

### User Story 2 - Register JSON Schema (Priority: P1)

A developer using JSON Schema for Kafka serialization wants to register `.json` schema files with Confluent Schema Registry. They configure a registration entry pointing to their JSON Schema file, and the plugin detects the type from the file extension and registers it as a JSON schema.

**Why this priority**: JSON Schema is the third supported format in Schema Registry. Together with Story 1, this completes coverage of all three schema types that Schema Registry supports.

**Independent Test**: Can be fully tested by configuring a JSON Schema registration, running the register task, and verifying the schema appears in Schema Registry with type JSON.

**Acceptance Scenarios**:

1. **Given** a `.json` file configured for registration, **When** the user runs the register task, **Then** the schema is registered in Schema Registry with type JSON.
2. **Given** a `.json` file configured for registration, **When** the schema content is invalid JSON Schema, **Then** the plugin reports a clear error indicating the JSON Schema is malformed.

---

### User Story 3 - Download Schemas with Correct File Extensions (Priority: P2)

A developer downloads schemas from Schema Registry using the plugin. When a downloaded schema is of type Protobuf or JSON Schema, the plugin saves the file with the correct extension (`.proto` or `.json` respectively) instead of always using `.avsc`.

**Why this priority**: Download is a complement to registration. Correct file extensions ensure downloaded schemas can be used directly by downstream tooling (protoc, JSON Schema validators).

**Independent Test**: Can be fully tested by registering schemas of each type, running the download task, and verifying the output files have correct extensions.

**Acceptance Scenarios**:

1. **Given** a Protobuf schema registered under a subject, **When** the user downloads that subject, **Then** the downloaded file has a `.proto` extension.
2. **Given** a JSON Schema registered under a subject, **When** the user downloads that subject, **Then** the downloaded file has a `.json` extension.
3. **Given** an Avro schema registered under a subject, **When** the user downloads that subject, **Then** the downloaded file still has an `.avsc` extension (backward compatible).

---

### User Story 4 - Explicit Schema Type Override (Priority: P3)

A developer has a schema file with a non-standard extension (e.g., a JSON Schema file named `config.schema`) and wants to explicitly specify the schema type rather than relying on extension-based inference.

**Why this priority**: Edge case but important for flexibility. Most users will rely on automatic type detection; explicit override handles unusual setups.

**Independent Test**: Can be fully tested by configuring a registration with an explicit schema type that differs from what the extension would infer, and verifying the correct type is used.

**Acceptance Scenarios**:

1. **Given** a file with a non-standard extension and an explicit schema type specified, **When** the user runs the register task, **Then** the schema is registered with the explicitly specified type.
2. **Given** a file with a standard extension and an explicit schema type that differs, **When** the user runs the register task, **Then** the explicit type takes precedence over the inferred type.

---

### User Story 5 - Compatibility Check for Non-Avro Schemas (Priority: P2)

A developer wants to check schema compatibility before deploying a Protobuf or JSON Schema change. The existing compatibility check feature should work seamlessly with all three schema types.

**Why this priority**: Compatibility checking is a safety net for schema evolution. It must work uniformly across all schema types to prevent breaking changes.

**Independent Test**: Can be fully tested by registering a Protobuf/JSON schema, modifying it incompatibly, running the compatibility check, and verifying it reports the incompatibility.

**Acceptance Scenarios**:

1. **Given** an existing Protobuf schema registered for a subject, **When** the user checks compatibility with a backward-incompatible change, **Then** the plugin reports the incompatibility.
2. **Given** an existing JSON Schema registered for a subject, **When** the user checks compatibility with a compatible change, **Then** the plugin reports the schema is compatible.

---

### Edge Cases

- What happens when a file has no extension? The plugin should report a clear error asking the user to either rename the file or specify the schema type explicitly.
- What happens when a file has an unrecognized extension (e.g., `.yaml`)? The plugin should report an error listing the supported extensions.
- What happens when Schema Registry returns a schema type not known to the plugin during download? The plugin should report an error with the unknown type value.
- How does the plugin behave with mixed schema types in a single project? Each registration entry is independent — different subjects can use different schema types without conflict.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Plugin MUST support three schema types: Avro, Protobuf, and JSON Schema.
- **FR-002**: Plugin MUST automatically detect schema type from file extension: `.avsc` for Avro, `.proto` for Protobuf, `.json` for JSON Schema.
- **FR-003**: Plugin MUST allow users to explicitly specify the schema type per registration, overriding the inferred type.
- **FR-004**: Plugin MUST register schemas with the correct schema type in Schema Registry (AVRO, PROTOBUF, or JSON).
- **FR-005**: Plugin MUST download schemas and save them with the correct file extension based on the schema type returned by Schema Registry.
- **FR-006**: Plugin MUST report a clear error when a file has no extension and no explicit schema type is provided.
- **FR-007**: Plugin MUST report a clear error when a file has an unrecognized extension and no explicit schema type is provided.
- **FR-008**: Plugin MUST maintain full backward compatibility for existing Avro-only users — no configuration changes required for projects that only use Avro.
- **FR-009**: Compatibility check MUST work correctly for Protobuf and JSON Schema types, not only Avro.
- **FR-010**: Plugin MUST handle the case where Schema Registry returns a null or missing schema type by defaulting to Avro (this is the registry's own behavior for legacy schemas).

### Key Entities

- **Schema Type**: Represents one of the three supported schema formats (Avro, Protobuf, JSON Schema). Each type has a canonical file extension and a registry label used when communicating with Schema Registry.
- **Registration Entry**: A user-configured mapping from a subject name to a schema file, optionally with an explicit schema type override.
- **Downloaded Schema**: A schema fetched from Schema Registry, including its subject, version, detected type, and content.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can register Protobuf and JSON Schema files using the same workflow as Avro — no additional manual steps required beyond providing the schema file.
- **SC-002**: Downloaded schema files have the correct extension 100% of the time, matching their schema type in the registry.
- **SC-003**: Existing projects using only Avro schemas continue to work without any configuration changes after upgrading the plugin.
- **SC-004**: Schema type detection from file extension succeeds for all three standard extensions (`.avsc`, `.proto`, `.json`) without user intervention.
- **SC-005**: All error messages related to unsupported or missing schema types clearly indicate what went wrong and how to fix it (include supported extensions in the error).
- **SC-006**: Register, download, and compatibility check features all support all three schema types uniformly.

## Assumptions

- Schema Registry version 5.5+ is required, as multi-schema-type support was introduced in that version. Earlier versions only support Avro.
- Protobuf schemas are self-contained single-file `.proto` definitions. Multi-file Protobuf imports (referencing other `.proto` files) are out of scope for the initial implementation.
- JSON Schema files use standard JSON Schema draft specification as supported by Confluent Schema Registry.
- Users managing mixed schema types in a single project is a supported use case — no restriction on combining Avro, Protobuf, and JSON Schema registrations.
- The plugin's existing authentication and connection configuration applies uniformly to all schema types — no type-specific connection settings are needed.
