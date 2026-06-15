# Data Model: Incremental Schema Download

**Date**: 2026-06-15 | **Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

## Entities

### VersionManifest

Immutable record of previously downloaded schema versions.

| Field | Type | Description |
|-------|------|-------------|
| versions | Map[String, Int] | Subject name → last downloaded version number |

**Identity**: Singleton per task scope (one manifest file per `Compile/Test` scope).

**Lifecycle**:
- Created: first successful `schemaRegistryDownload` run
- Updated: after each successful download run (atomically overwritten)
- Deleted: `sbt clean` (resides in `cacheDirectory`)

**Operations**:
- `versionOf(subject: String): Option[Int]` — lookup cached version
- `updated(subject: String, version: Int): VersionManifest` — return copy with one entry updated
- `updatedAll(entries: List[(String, Int)]): VersionManifest` — batch update
- `toJson: String` — serialize to JSON
- `fromJson(json: String): Either[DownloadError, VersionManifest]` — deserialize, fail gracefully

**Serialization format** (JSON):
```json
{
  "user-events": 3,
  "order-events": 7,
  "payment-value": 1
}
```

Flat key-value: subject name (String) → version (Int). No envelope, no metadata. Simplest format that supports the use case.

**Validation rules**:
- Subject names must be non-empty strings
- Version numbers must be positive integers
- Duplicate subject names: last wins (Map semantics)
- Unknown keys in JSON: ignored (forward compatibility)
- Missing file or unparseable JSON: treated as `VersionManifest.empty`

### DownloadDecision

Sealed ADT representing the incremental resolver's output for a single subject.

| Variant | Fields | Description |
|---------|--------|-------------|
| Download | subject: RegistrySubject, reason: String | Subject should be fetched from registry |
| Skip | name: String, localVersion: Int | Subject is up to date, no fetch needed |

**State transitions**: N/A — immutable decision value. Created by `IncrementalResolver.plan()`, consumed by plugin task orchestration.

**Relationship to existing entities**:
- `Download.subject` references a `RegistrySubject` (Pinned or Latest)
- `Skip.name` is the subject name string (no need for full `RegistrySubject` since no download occurs)

## Entity Relationships

```
SubjectResolver.resolve()
        │
        ▼
  DownloadPlan(subjects: List[RegistrySubject])
        │
        ▼
IncrementalResolver.plan(manifest, subjects, versionLookup)
        │
        ▼
  List[DownloadDecision]
   ┌────┴────┐
   │         │
 Download   Skip
   │
   ▼
 Downloader.schemaSubjectToFile()
   │
   ▼
 VersionManifest.updatedAll(downloaded)
```

## Existing Entities (unchanged)

- **RegistrySubject**: Sealed ADT (Pinned | Latest). No changes needed.
- **DownloadPlan**: `case class DownloadPlan(subjects: List[RegistrySubject])`. No changes — incremental resolver consumes its output.
- **DownloadError**: May gain one new variant: `ManifestParseError(file: Path, error: Throwable)` for corrupted manifest warning. Alternatively, manifest parse failures can log a warning and return `VersionManifest.empty` without a dedicated error type.
- **Downloader**: Unchanged. Continues to fetch and write individual schemas.
