# Research: Incremental Schema Download

**Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

## R1: JSON Serialization for Version Manifest

**Decision**: Use Jackson ObjectMapper (already on classpath via Confluent kafka-schema-registry-client transitive dependency).

**Rationale**: No new dependency required. Jackson `ObjectMapper` with `DefaultScalaModule` is not available (Scala 2.12 compatibility concerns), but the manifest structure is simple enough for manual JSON generation/parsing using Jackson's tree model (`JsonNode`). Alternatively, raw string manipulation for a flat `Map[String, Int]` serialized as `{"subject": version}` is trivial.

**Alternatives considered**:
- circe/play-json: Adds new dependency, violates constitution (new deps need approval). Overkill for flat key-value JSON.
- sbt's built-in sjson-new: Available on sbt's classpath but not designed for user-facing JSON files. Limited documentation.
- Manual string building: Fragile for parsing. Rejected.

**Final approach**: Use Jackson tree API (`ObjectMapper`, `ObjectNode`, `JsonNode`) — zero new dependencies, robust parsing, already tested in Confluent client.

## R2: Manifest Storage Location

**Decision**: `streams.value.cacheDirectory / ".schema-versions.json"` within the `schemaRegistryDownload` task scope.

**Rationale**: sbt's `cacheDirectory` is task-scoped (different for `Compile / schemaRegistryDownload` vs `Test / schemaRegistryDownload`), persistent across builds, and automatically cleaned by `sbt clean`. This matches the spec's FR-002 requirement.

**Alternatives considered**:
- `target/` root: Not scoped to task, could collide with other plugins.
- `project/` directory: Survives `clean`, violates spec requirement that `sbt clean` removes manifest.
- sbt's `FileFunction.cached`: Designed for file-to-file caching, not version metadata tracking.

## R3: Version Lookup Strategy for Latest Subjects

**Decision**: Use `client.getLatestSchemaMetadata(subject).getVersion` for each non-pinned subject. One lightweight API call per Latest subject.

**Rationale**: `getLatestSchemaMetadata` returns schema metadata including version number. This is the same call the existing `Downloader.fetchSchema` makes for `Latest` subjects (line 41 of Downloader.scala), but we only need the version number for the comparison — not the schema body. The registry client caches responses internally (`CachedSchemaRegistryClient` with configurable cache size).

**Alternatives considered**:
- Batch API: Confluent Schema Registry has no batch version-check endpoint. Would need one call per subject regardless.
- Content hash comparison: Requires downloading schema body — defeats the purpose of skipping.
- ETag/If-None-Match: Schema Registry REST API doesn't support conditional requests.

## R4: Manifest-vs-File-Existence Divergence

**Decision**: Trust manifest only. Do not verify schema output files exist on disk.

**Rationale**: Version manifest records what was downloaded. If output files are manually deleted, `sbt clean schemaRegistryDownload` is the recovery path. This matches Gradle's UP-TO-DATE mechanism (task-level, not file-level). Adding file existence checks would require I/O in the pure planning function, complicating testability (FR-012).

**Alternatives considered**:
- File existence check: Breaks pure function requirement. Adds I/O. Marginal benefit since manual file deletion is rare and `sbt clean` is the standard recovery.

## R5: Integration with Existing Download Flow

**Decision**: Insert incremental planning between subject resolution and the download loop in `SchemaDownloaderPlugin`. The `Downloader` class remains unchanged — only the plugin task orchestration changes.

**Rationale**: Keeps `Downloader` as a single-responsibility fetcher. The incremental planning layer is orthogonal — it decides WHAT to download, `Downloader` handles HOW. This preserves backward compatibility and testability.

**Alternatives considered**:
- Modify Downloader to accept manifest: Violates single responsibility. Downloader should not know about caching strategy.
- Create IncrementalDownloader wrapper: Unnecessary indirection when the plugin task can orchestrate directly.

## R6: Handling Duplicate Version Check in Download Path

**Decision**: When `IncrementalResolver.plan()` decides to download a Latest subject, the actual download via `Downloader.schemaSubjectToFile()` will call `getLatestSchemaMetadata` again (to fetch the full schema). This means two API calls for Latest subjects that need downloading: one for version check, one for schema fetch.

**Rationale**: Acceptable tradeoff. The version check call is cached by `CachedSchemaRegistryClient`. The second call hits the cache, not the network. Merging the two would require changing `Downloader.fetchSchema` to accept pre-fetched metadata, adding coupling between the incremental layer and the download layer.

**Alternatives considered**:
- Pre-fetch and pass metadata: Couples IncrementalResolver to Downloader internals. Both need to know about schema metadata structure.
- Return schema body from version check: Defeats purpose of lightweight check for subjects that will be skipped.
