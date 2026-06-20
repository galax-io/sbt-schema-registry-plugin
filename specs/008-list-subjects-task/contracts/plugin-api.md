# Contract: List Subjects Task — Public sbt Surface & Core API

**Feature**: 008-list-subjects-task | **Date**: 2026-06-20

This is a published sbt plugin. The "contract" is (a) the sbt keys users configure and invoke, and (b) the internal core API the task delegates to. All additions are backward-compatible (Constitution I): no existing key or behavior changes.

---

## 1. sbt task (user-facing command)

```
schemaRegistryListSubjects   # taskKey[Unit]
```

- Invoked as `sbt schemaRegistryListSubjects`.
- Connects using the build's existing registry settings (no new connection config).
- Prints a sorted, human-readable listing to the sbt log at `info` level.
- **Success contract**: exits successfully; logs the total count then one line per subject.
- **Failure contract**: fails the task (non-zero) with a single actionable message when the subject list cannot be fetched, or when a listed subject's versions cannot be fetched (fail-fast).

### Output contract (info log)

```
Found <N> subject(s):
  <name padded>  (versions: <range>, compat: <LEVEL|(default)>)
  ...
```

- `<N>` = count after filtering.
- `<range>` = `none` | `<v>` | `<first>..<last>`.
- `compat` = subject-level level, or `(default)` when none/unreadable.
- Zero subjects ⇒ `Found 0 subjects:` and no rows (still success).

Exact column widths/format are presentation detail (not contract-frozen); the **fields shown** (name, version range, compatibility) are the contract.

---

## 2. sbt setting (user-facing config)

```
schemaRegistrySubjectFilter   # settingKey[Option[String]], default None
```

- `None` (default) ⇒ list all subjects.
- `Some(text)` ⇒ list only subjects whose name **contains** `text`, case-insensitive.
- `Some("")` ⇒ behaves as `None` (matches all).

Usage:

```scala
// list all
sbt schemaRegistryListSubjects

// filter
sbt 'set schemaRegistrySubjectFilter := Some("order")' schemaRegistryListSubjects
```

### Reused settings (read, never modified)

`schemaRegistryUrl`, `schemaRegistryCacheSize`, `schemaRegistryAuth`, `schemaRegistryProperties` — same client construction as the download/register/compatibility tasks.

---

## 3. Core API (internal, `org.galaxio.avro`)

```scala
final case class SubjectInfo(
  name: String,
  versions: List[Int],
  compatibility: Option[String],
) {
  def latestVersion: Option[Int]
  def versionRange: String     // "none" | "<v>" | "<first>..<last>"
}

final case class SubjectListing(subjects: List[SubjectInfo]) {
  def size: Int
  def matching(filter: String): SubjectListing   // case-insensitive substring on name
}

object SubjectExplorer {
  def listAll(
    client: SchemaRegistryClient,
    filter: Option[String],
    parallelism: Int = 1,   // concurrent per-subject fetches; reuses schemaRegistryParallelism
  ): Either[DownloadError, SubjectListing]
}
```

### `SubjectExplorer.listAll` contract

| Aspect | Guarantee |
|---|---|
| Purity | No logging, no `sys.error`, no file I/O; only reads via the injected `client` |
| Ordering | Result subjects sorted by `name` ascending; each `versions` ascending |
| Subject-list failure | `Left(DownloadError.SubjectListFailed(cause))` |
| Per-subject versions failure | `Left(DownloadError.SubjectVersionsFetchFailed(subject, cause))` — fail-fast, stops at first |
| Compatibility absent/failed | `compatibility = None` (never an error) |
| Filtering | `None` ⇒ all; `Some(t)` ⇒ `listing.matching(t)` |

### Error additions (`DownloadError`)

```scala
final case class SubjectVersionsFetchFailed(subject: String, error: Throwable) extends DownloadError {
  val message = s"Failed to fetch versions for $subject: ${error.getMessage}"
  override def cause = Some(error)
}
```

`SubjectListFailed` is reused as-is.

---

## 4. Backward-compatibility assertions

- No rename/removal/retype of any existing key.
- No change to download/register/compatibility task behavior.
- New keys are additive with safe defaults (`schemaRegistrySubjectFilter := None`).
- Builds that never call `schemaRegistryListSubjects` are byte-for-byte unaffected.
