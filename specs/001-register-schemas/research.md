# Research: Register (Push) Schemas to Registry

## R1: Schema Registration API

**Decision**: Use `SchemaRegistryClient.register(subject, parsedSchema)` from
Confluent's `kafka-schema-registry-client` (already a dependency).

**Rationale**: The client already supports registering schemas — same client
used for download. Method `register(String, ParsedSchema)` returns the schema
ID. `AvroSchema`, `ProtobufSchema`, and `JsonSchema` implement `ParsedSchema`.

**Alternatives considered**:
- Raw HTTP calls to `/subjects/{subject}/versions` — unnecessary, client
  already wraps this with error handling and auth.
- Separate client library — adds a dependency for no benefit.

## R2: Multi-Format Schema Support

**Decision**: Use `ParsedSchema` subtypes — `AvroSchema`, `ProtobufSchema`,
`JsonSchema` — all provided by Confluent libraries already on classpath.

**Rationale**: `kafka-schema-registry-client` 8.x bundles support for all
three formats. `AvroSchema` is in the existing dependency. `ProtobufSchema`
requires `kafka-protobuf-provider` and `JsonSchema` requires
`kafka-json-schema-provider` — these are optional runtime deps.

**Alternatives considered**:
- Raw string registration without schema parsing — loses validation that
  the registry performs.
- Only support Avro — limits usefulness for teams using protobuf/json schema.

**Decision refinement**: Ship Avro support first (zero new dependencies).
Protobuf and JSON Schema require additional provider dependencies — document
these as optional user-added deps. The `SchemaType` ADT guides which
`ParsedSchema` subtype to construct.

## R3: Error Handling Pattern

**Decision**: New `RegistryError` sealed ADT separate from `DownloadError`.
Return `List[Either[RegistryError, RegisteredSchema]]` — process all
registrations, report all failures.

**Rationale**: Matches the existing `DownloadError` pattern. Collecting all
errors (not failing fast) gives users actionable feedback in one run.
Keeps registration error types distinct from download error types.

**Alternatives considered**:
- Extend `DownloadError` with registration cases — muddies the ADT, download
  and registration are separate concerns.
- Fail on first error — forces multiple fix-run cycles for multi-schema
  projects.

## R4: Client Construction / Reuse

**Decision**: Reuse `Downloader.buildConfig` for client configuration (auth +
properties). Construct client in the sbt task layer via the same `Using.resource`
pattern.

**Rationale**: Auth and properties settings are already defined and documented.
No reason to diverge — same registry, same credentials.

**Alternatives considered**:
- New settings for registration auth — unnecessary duplication, same registry.
- Share a single client instance across download + register — complex lifecycle
  management for no benefit (tasks run independently).

## R5: sbt Task Design

**Decision**: New `schemaRegistryRegister` task key + `schemaRegistryRegistrations`
setting key. Both scoped to `Compile` by default (mirroring download).

**Rationale**: Parallel to existing download task. Users configure registrations
declaratively in `build.sbt`. Task reports successes via `logger.info`, fails
build on any error.

**Alternatives considered**:
- Combine download and register in one task — violates single responsibility,
  users may want one without the other.
- Command (not task) — loses sbt dependency graph benefits.
