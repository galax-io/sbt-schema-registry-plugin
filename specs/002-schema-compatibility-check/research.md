# Research: Schema Compatibility Check

## R1: Confluent Client API for Compatibility Testing

**Decision**: Use `SchemaRegistryClient.testCompatibilityVerbose(String, ParsedSchema)` which returns `java.util.List[String]`.

**Rationale**: The verbose variant returns specific incompatibility messages (e.g., "new required field has no default value") rather than just a boolean. Available in the `kafka-schema-registry-client` 8.x already on the classpath. Empty list = compatible, non-empty = incompatible with reasons.

**Alternatives considered**:
- `testCompatibility(String, ParsedSchema)` — returns boolean only, no diagnostic messages. Rejected because US2 requires verbose output.
- REST API direct call — unnecessary complexity since the client already wraps it.

**Method signatures available** (from 8.0.0 jar):
```java
boolean testCompatibility(String subject, ParsedSchema schema)
List<String> testCompatibilityVerbose(String subject, ParsedSchema schema)
List<String> testCompatibilityVerbose(String subject, ParsedSchema schema, boolean verbose)
```

## R2: Behavior for New Subjects (Never Registered)

**Decision**: `testCompatibilityVerbose` on a subject with no prior versions returns empty list (compatible). No special handling needed.

**Rationale**: Confluent Schema Registry treats the first registration as always compatible — there's nothing to conflict with. The client returns empty messages list, which maps naturally to `CompatibilityResult.Compatible`.

**Alternatives considered**:
- Pre-check with `getAllSubjects()` and skip new subjects — unnecessary roundtrip, adds complexity for no benefit.

## R3: Architecture — Pure Core vs Effect Layer

**Decision**: `CompatibilityChecker` object with pure functions returning `CompatibilityResult` / `CompatibilityReport`. sbt task layer handles client lifecycle and logging.

**Rationale**: Matches existing `Registrar` pattern — pure core testable with mocked client, effects at the sbt task edge. `CompatibilityReport` is a data class with partition accessors (`.compatible`, `.incompatible`, `.failed`).

**Alternatives considered**:
- Embedding logic in sbt task — violates Single Responsibility (Constitution II), makes unit testing require sbt test harness.

## R4: Reusing Existing Infrastructure

**Decision**: Reuse `Downloader.buildClient` for client construction, `Registrar.readSchemaFile` and `Registrar.buildParsedSchema` for file reading and schema parsing. The compatibility task reuses `schemaRegistryRegistrations` setting — no new configuration.

**Rationale**: DRY. The registration and compatibility features share the same input (subject → file mappings) and the same client construction. Existing error types (`RegistryError.FileNotFound`, `FileReadFailed`, `RegistrationFailed`) cover all failure modes.

**Alternatives considered**:
- Separate `schemaRegistryCompatibilityRegistrations` setting — rejected per FR-007 (must reuse registration config).
- New error ADT — rejected because existing `RegistryError` cases already cover file/parse/network failures.

## R5: Exception Handling from Client

**Decision**: Wrap `client.testCompatibilityVerbose` call in `Try`. Map exceptions to `CompatibilityResult.Failed` with the cause wrapped in `RegistryError`.

**Rationale**: The client throws `IOException` (network) and `RestClientException` (registry errors). Both should surface as `Failed` results, not crash the entire check. Other subjects should still be checked.

**Alternatives considered**:
- Catch only specific exceptions — fragile against client version changes.
- Let exceptions propagate — would stop checking remaining subjects, violating FR-002.
