# Data Model: List Subjects Task

**Feature**: 008-list-subjects-task | **Date**: 2026-06-20

All types are immutable Scala values in package `org.galaxio.avro`. New types are additive; no existing type changes shape.

---

## SubjectInfo (new)

One discovered subject and its registry metadata.

| Field | Type | Notes |
|---|---|---|
| `name` | `String` | subject name, e.g. `order-value` |
| `versions` | `List[Int]` | registered versions, ascending; never empty for a listed subject (a subject that returns no versions is treated as a fetch failure — see fail-fast) |
| `compatibility` | `Option[String]` | subject-level compatibility level (e.g. `BACKWARD`); `None` when no override or lookup failed (best-effort) |

**Derived (pure)**:
- `latestVersion: Option[Int]` = `versions.lastOption`
- `versionRange: String` = `none` if empty; the single value if one; `"<first>..<last>"` if many (e.g. `1..5`)

**Validation / invariants**:
- `versions` is sorted ascending (the explorer sorts on read).
- No formatting/logging logic lives here (FR-009).

---

## SubjectListing (new)

The result of a list operation — a wrapper over many `SubjectInfo`.

| Field | Type | Notes |
|---|---|---|
| `subjects` | `List[SubjectInfo]` | sorted by `name` ascending |

**Pure transformation**:
- `matching(filter: String): SubjectListing` — returns a copy keeping subjects whose `name` **contains** `filter`, case-insensitive (D5). Empty `filter` ⇒ unchanged (all match).

**Derived**:
- `size: Int` = `subjects.size` (used by presentation for the reported count, FR-006).

---

## SubjectExplorer (new) — pure core, no I/O side effects beyond the injected client

```
object SubjectExplorer {
  def listAll(
    client: SchemaRegistryClient,
    filter: Option[String],
  ): Either[DownloadError, SubjectListing]
}
```

Behavior:
1. `getAllSubjects()` → sorted `List[String]`; on failure ⇒ `Left(SubjectListFailed(e))`.
2. For each subject (concurrently on a bounded pool sized by `schemaRegistryParallelism`, D4; `1` = sequential): `getAllVersions(subject)` → `List[Int]`; on failure ⇒ `Left(SubjectVersionsFetchFailed(subject, e))`, first error (in subject order) wins (fail-fast, clarified).
3. `getCompatibility(subject)` → `Option[String]`, best-effort (`None` on absence/failure, D3).
4. Build `SubjectListing(infos)`, then apply `filter.fold(listing)(listing.matching)`.

The client is **injected** (Constitution II). No logging, no `sys.error` (FR-010).

---

## DownloadError (existing — one additive case)

`DownloadError` is the existing sealed trait (`def message: String`, `def cause: Option[Throwable]`). Reused cases plus one new case:

| Case | Status | Message |
|---|---|---|
| `SubjectListFailed(error: Throwable)` | reuse | `Failed to fetch subject list: {error.getMessage}` |
| `SubjectVersionsFetchFailed(subject: String, error: Throwable)` | **new** | `Failed to fetch versions for {subject}: {error.getMessage}` (overrides `cause = Some(error)`) |

Adding a case to the internal sealed trait is backward-compatible (the plugin owns all match sites).

---

## Setting / Task keys (in `SchemaDownloaderPlugin.autoImport`)

| Key | Kind | Type | Default | Status |
|---|---|---|---|---|
| `schemaRegistryListSubjects` | `taskKey` | `Unit` | — | **new** |
| `schemaRegistrySubjectFilter` | `settingKey` | `Option[String]` | `None` | **new** |

Reused (read by the task, unchanged): `schemaRegistryUrl: String`, `schemaRegistryCacheSize: Int`, `schemaRegistryAuth: Option[SchemaRegistryAuth]`, `schemaRegistryProperties: Map[String, String]`.

---

## Relationships

```
schemaRegistryListSubjects (task, presentation/logging)
        │ reads settings: Url, CacheSize, Auth, Properties, SubjectFilter
        │ withRegistryClient(...) ──▶ SchemaRegistryClient (injected)
        ▼
SubjectExplorer.listAll(client, filter) : Either[DownloadError, SubjectListing]
        │                                   ▲ Left = SubjectListFailed | SubjectVersionsFetchFailed
        ▼
SubjectListing(List[SubjectInfo])  ──matching(filter)──▶ SubjectListing
        │
        ▼
SubjectInfo(name, versions, compatibility) ──▶ versionRange / latestVersion
```

Not reused: `RegistrySubject` (`Pinned`/`Latest`) models a download *selection*, not a discovered subject (D7).
