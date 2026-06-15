# Research: Multi-Schema Type Support

## R1: Schema type detection during download

**Decision**: Use `SchemaMetadata.getSchemaType()` from Confluent client to detect schema type on download. Map the returned label (`"AVRO"`, `"PROTOBUF"`, `"JSON"`) to `SchemaType` via `fromRegistryLabel()`. Default to AVRO when `getSchemaType()` returns null (legacy registries).

**Rationale**: The Confluent client API exposes schema type metadata since v5.5. The `getLatestSchemaMetadata()` and `getByVersion()` methods both return `SchemaMetadata` which has `getSchemaType()`. This is the standard approach — same as Gradle and Maven plugins.

**Alternatives considered**:
- Infer from content (parse as JSON, check for Protobuf syntax) — fragile, unnecessary when metadata is available
- Store type in subject name convention — non-standard, breaks interop

## R2: Schema references in Confluent client API

**Decision**: Use `client.register(subject, parsedSchema, references)` overload. References are `List[SchemaReference]` where each entry has `name` (import path), `subject` (registry subject), and `version` (schema version).

**Rationale**: Confluent's `SchemaRegistryClient.register()` has a 3-argument overload accepting references. `ParsedSchema` implementations (`ProtobufSchema`, `JsonSchema`) also accept references in constructors. This is the documented approach.

**Alternatives considered**:
- Resolve references client-side before registration — defeats registry-managed resolution
- Auto-detect references from schema content — complex, error-prone, not how Confluent designed it

## R3: Optional dependency loading pattern

**Decision**: Keep existing reflection-based approach in `Registrar.loadSchema()`. The current `Class.forName` pattern with `ClassNotFoundException` → actionable error message is correct and sufficient.

**Rationale**: Already implemented and working. The error message in `Registrar.loadSchema()` already says "Schema provider not on classpath ($className). Add the appropriate Confluent provider dependency." This satisfies FR-012.

**Alternatives considered**:
- `provided` scope in build.sbt — doesn't work for sbt plugins (classpath managed differently)
- Fat JAR with all providers — violates FR-008 (bloats Avro-only users)

## R4: SchemaType enrichment approach

**Decision**: Add `extension` and `registryLabel` fields to each `SchemaType` case, plus `fromRegistryLabel()` constructor. Keep existing `fromExtension()`.

**Rationale**: Single source of truth for extension ↔ label ↔ type mapping. Issue #27 proposes exactly this pattern. Eliminates stringly-typed constants scattered across code (e.g., `Downloader.avroSchemaFileExtension`).

**Alternatives considered**:
- Separate lookup maps — duplication risk, harder to keep in sync
- String constants per-class — current approach with `Downloader.avroSchemaFileExtension`, doesn't scale

## R5: Confluent SchemaReference model

**Decision**: Create `SchemaReference` case class in our domain with `name: String`, `subject: String`, `version: Int`. Convert to Confluent's `io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference` at the registration boundary.

**Rationale**: Own domain model avoids leaking Confluent API types into the public sbt plugin API. Conversion is trivial — Confluent's `SchemaReference` has a 3-arg constructor `(name, subject, version)`.

**Alternatives considered**:
- Expose Confluent's `SchemaReference` directly — couples plugin API to Confluent internals
- Tuple-based API — poor ergonomics, no named fields

## R6: CompatibilityChecker with references

**Decision**: `testCompatibilityVerbose(subject, parsedSchema)` already works without explicit references because the registry resolves references server-side from the registered schema. However, for new schemas with new references not yet registered, we should use `testCompatibilityVerbose(subject, parsedSchema, version, verbose)` and construct the `ParsedSchema` with references.

**Rationale**: The compatibility check uses the same `ParsedSchema` construction as registration. If references are needed for registration, they're needed for compatibility too.

**Alternatives considered**:
- Skip references in compatibility check — would fail for schemas that depend on references for parsing
