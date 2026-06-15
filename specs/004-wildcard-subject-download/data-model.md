# Data Model: Wildcard Subject Download

**Feature**: 004-wildcard-subject-download | **Date**: 2026-06-15

## Entities

### SubjectSpec (NEW)

Sealed ADT representing how a subject is specified for download.

| Variant | Fields | Description |
|---------|--------|-------------|
| `Exact(subject: RegistrySubject)` | `subject` — existing `RegistrySubject` (Pinned or Latest) | Direct subject reference from `schemaRegistrySubjects` |
| `Pattern(regex: String)` | `regex` — raw regex string | Regex pattern to match against all subjects in registry |

**Relationships**: `Exact` wraps existing `RegistrySubject`. `Pattern` produces `RegistrySubject.Latest` instances after resolution.

**Validation**: `Pattern.regex` must be a valid Java regex (validated at construction or resolution time via `scala.util.matching.Regex`).

### DownloadPlan (NEW)

Resolved, deduplicated list of concrete subjects ready for download.

| Field | Type | Description |
|-------|------|-------------|
| `subjects` | `List[RegistrySubject]` | Ordered list, exact subjects first, then pattern-matched. Deduplicated by `name`. |

**Identity rule**: Two entries are duplicates if their `RegistrySubject.name` values are equal (case-sensitive string comparison).

**Deduplication invariant**: First occurrence wins. Since exact specs are prepended before pattern specs, explicit subjects always take precedence.

### DownloadError — New Variants

| Variant | Fields | Description |
|---------|--------|-------------|
| `InvalidPattern(pattern: String, error: Throwable)` | pattern string, compilation error | Regex failed to compile |
| `SubjectListFailed(error: Throwable)` | underlying exception | `getAllSubjects()` call failed (network, auth, etc.) |

### Existing Entities (unchanged)

- **RegistrySubject**: `Pinned(name, version)` | `Latest(name)` — no changes
- **Downloader**: Processes individual `RegistrySubject` → file. No changes needed.
- **SchemaDownloaderPlugin**: Modified to wire `SubjectResolver` before `Downloader`

## State Transitions

```
SubjectSpec.Pattern(regex)
  → [validate regex] → Either[InvalidPattern, compiled Regex]
  → [fetch getAllSubjects] → Either[SubjectListFailed, List[String]]
  → [full-match filter] → List[String] (matched names)
  → [map to RegistrySubject.Latest] → List[RegistrySubject]

SubjectSpec.Exact(subject)
  → List(subject)  (passthrough, no resolution needed)

Combined List[SubjectSpec]
  → exact specs ++ pattern specs
  → deduplicate by name (first wins)
  → DownloadPlan(subjects)
```

## Data Flow in Download Task

```
schemaRegistrySubjects.value     → List[RegistrySubject]  → map to SubjectSpec.Exact
schemaRegistrySubjectPatterns.value → List[String]         → map to SubjectSpec.Pattern
                                                          ↓
                                               SubjectResolver.resolve(client, specs)
                                                          ↓
                                               Either[DownloadError, DownloadPlan]
                                                          ↓
                                               plan.subjects.map(downloader.schemaSubjectToFile)
```
