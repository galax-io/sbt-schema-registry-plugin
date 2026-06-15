# Feature Specification: Schema Compatibility Check

**Feature Branch**: `002-schema-compatibility-check`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "https://github.com/galax-io/sbt-schema-registry-plugin/issues/26"

## User Scenarios & Testing

### User Story 1 - Check Schema Compatibility Before Registration (Priority: P1)

A developer modifies a local schema file and wants to verify it is compatible with the version already registered in Schema Registry before pushing the change. They run a single command that reads their local schema files, checks each one against the registry's compatibility rules, and reports whether the schemas are safe to register.

**Why this priority**: This is the core value proposition — catching breaking schema changes before they reach production. Without this, teams only discover incompatibilities at registration time, which may be during a deploy pipeline.

**Independent Test**: Configure one `.avsc` file mapping, run the compatibility check command, and verify the output reports compatible or incompatible with the correct subject name.

**Acceptance Scenarios**:

1. **Given** a subject with an existing registered schema and a local schema file that is backward-compatible, **When** the user runs the compatibility check, **Then** the tool reports the subject as compatible and the build succeeds.
2. **Given** a subject with an existing registered schema and a local schema file that removes a required field, **When** the user runs the compatibility check, **Then** the tool reports the subject as incompatible with a descriptive message explaining what broke, and the build fails.
3. **Given** a subject that has never been registered, **When** the user runs the compatibility check, **Then** the tool reports the subject as compatible (no prior version to conflict with).

---

### User Story 2 - Verbose Incompatibility Diagnostics (Priority: P2)

When a schema is incompatible, the developer needs to understand exactly what broke — which field was removed, which type changed, which default is missing — so they can fix the issue without guessing.

**Why this priority**: Without verbose diagnostics, developers must manually diff schemas and reason about compatibility rules. Verbose messages from the registry eliminate guesswork.

**Independent Test**: Register a schema, modify it incompatibly, run the check, and verify the output includes specific incompatibility messages (not just "incompatible").

**Acceptance Scenarios**:

1. **Given** a registered schema and a locally modified schema that adds a required field without a default, **When** the user runs the compatibility check, **Then** the output includes the specific incompatibility reason (e.g., "new required field has no default value").
2. **Given** multiple schemas where some are compatible and some are not, **When** the user runs the compatibility check, **Then** each subject's result is reported individually — compatible subjects show success, incompatible subjects show their specific failure messages.

---

### User Story 3 - CI/CD Pipeline Integration (Priority: P3)

A team wants to add schema compatibility checking as a gate in their CI pipeline, running it before the registration step to prevent breaking changes from being deployed.

**Why this priority**: Builds on US1 and US2 — the command must work reliably as a pipeline gate, failing the build on incompatibility and succeeding silently on compatibility.

**Independent Test**: Configure a build pipeline that runs compatibility check followed by registration. Verify an incompatible schema stops the pipeline before registration occurs.

**Acceptance Scenarios**:

1. **Given** a CI pipeline configured to run compatibility check then registration, **When** all schemas are compatible, **Then** both steps succeed and schemas are registered.
2. **Given** a CI pipeline configured to run compatibility check then registration, **When** one schema is incompatible, **Then** the compatibility check fails the build and registration never executes.
3. **Given** a project with no registrations configured, **When** the compatibility check runs, **Then** the tool warns that no registrations are configured and succeeds without error.

---

### Edge Cases

- What happens when the schema file cannot be read (missing, permission denied)?
- What happens when the registry is unreachable during the check?
- What happens when the local schema file contains invalid content that cannot be parsed?
- What happens when the registry returns an unexpected error during compatibility testing?
- How does the tool behave when checking compatibility for a brand-new subject with no prior versions?

## Requirements

### Functional Requirements

- **FR-001**: System MUST provide a command to check local schemas against the registry's compatibility rules without registering them.
- **FR-002**: System MUST report each subject's compatibility result independently — one failure MUST NOT prevent checking remaining subjects.
- **FR-003**: System MUST display verbose incompatibility messages that explain what specifically broke (field added/removed, type changed, etc.).
- **FR-004**: System MUST fail the build when any schema is incompatible or when an unrecoverable error occurs during checking.
- **FR-005**: System MUST succeed when checking a subject that has no prior registered versions (first-time registration is always compatible).
- **FR-006**: System MUST reuse the same connection settings (registry URL, authentication, properties) as the existing download and registration commands.
- **FR-007**: System MUST reuse the same schema file-to-subject mappings already defined for registration — no separate configuration required.
- **FR-008**: System MUST support the same schema types (Avro, Protobuf, JSON Schema) as the registration command.
- **FR-009**: System MUST report file-level errors (missing file, unreadable file, parse failure) distinctly from compatibility results.
- **FR-010**: System MUST warn and succeed (not fail) when no registrations are configured.

### Key Entities

- **CompatibilityResult**: The outcome of checking one subject — compatible, incompatible (with messages), or failed (with error).
- **CompatibilityReport**: An aggregate of all individual results with accessors for compatible, incompatible, and failed partitions, and an overall success/failure indicator.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Developers can verify schema compatibility for all configured subjects in a single command invocation.
- **SC-002**: Incompatible schemas produce actionable messages that identify the specific breaking change, enabling developers to fix the issue without consulting external documentation.
- **SC-003**: The compatibility check command can be added to a CI pipeline as a pre-registration gate with zero additional configuration beyond what registration already requires.
- **SC-004**: 100% of schema types supported by the registration command are also supported by the compatibility check.
- **SC-005**: A project with 10 schema subjects completes the compatibility check within 30 seconds under normal network conditions.

## Assumptions

- The Confluent Schema Registry instance supports the `testCompatibility` endpoint (available in Confluent Platform 5.0+).
- The verbose compatibility API (`testCompatibilityVerbose`) is available — the plugin targets Confluent client 7.x+ which includes this endpoint.
- Users have already configured `schemaRegistryRegistrations` for the registration feature; the compatibility check reuses this configuration.
- The registry's compatibility level (BACKWARD, FORWARD, FULL, etc.) is configured server-side; the plugin does not set or override it.
- Network connectivity to the registry is available when the command runs.
