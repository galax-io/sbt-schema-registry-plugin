# Research: List Subjects Task

**Feature**: 008-list-subjects-task | **Date**: 2026-06-20

Phase 0 decisions resolving every unknown in the Technical Context. Findings are grounded in a full read of the existing plugin source (`SchemaDownloaderPlugin`, `Downloader`, `SubjectResolver`, `ParallelDownloader`, `DownloadError`, `RegistryError`, `SchemaRegistryAuth`, build files, and the unit/integration/scripted test harnesses).

---

## D1 — Error model: reuse `DownloadError`, do not introduce `RegistryError.FetchFailed`

**Decision**: Model listing failures with the existing `DownloadError` sealed trait. Reuse `SubjectListFailed(error)` for a failed `getAllSubjects` call. Add one new case `SubjectVersionsFetchFailed(subject: String, error: Throwable)` for a per-subject version-fetch failure. Compatibility-lookup failures are **not** modeled as errors (see D3).

**Rationale**:
- Issue #32 references `RegistryError.FetchFailed(subject, cause)`, but `RegistryError` has no such case (its cases: `FileNotFound`, `FileReadFailed`, `RegistrationFailed`, `CompatibilityCheckFailed`, `UnsupportedSchemaType`) — those model *registration* and *file* failures.
- `DownloadError` already models registry *read* failures and even has `SubjectListFailed("Failed to fetch subject list: …")` — an exact fit for the `getAllSubjects` failure path. Listing is a read operation in the download domain.
- Adding one precise case keeps the project's granular error style (`DownloadError` already has 9 cases) and produces an accurate message ("Failed to fetch versions for {subject}: …") rather than overloading `SchemaFetchFailed` ("Failed to fetch schema {subject}").
- `DownloadError` is an internal sealed trait used only inside the plugin; adding a case is backward-compatible (no public match sites break — the plugin owns all matches).

**Alternatives considered**:
- *Add `RegistryError.FetchFailed`* (as the issue sketched) — rejected: wrong ADT (`RegistryError` is the registration/file domain), and would duplicate `SubjectListFailed`.
- *Reuse `SchemaFetchFailed` for version fetch* — rejected: imprecise message; the granular-error convention favors a dedicated case.

---

## D2 — No cats: plain Scala only

**Decision**: Implement the core with the Scala standard library. No new dependency.

**Rationale**: The project has **no cats dependency** (confirmed in `project/Dependencies.scala`) and compiles with `-Xfatal-warnings`. The constitution requires approval for new deps. Issue #32's snippet used cats syntax (`Either.catchNonFatal`, `.leftMap`, `.traverse`); these translate directly:

| Issue #32 (cats) | Plan (stdlib) |
|---|---|
| `Either.catchNonFatal(expr).leftMap(f)` | `Try(expr).toEither.left.map(f)` |
| `names.traverse(fetchInfo)` | a `foldLeft` over `names` short-circuiting on the first `Left` (or a tail-recursive helper), returning `Either[DownloadError, List[SubjectInfo]]` |
| `Option(client.getCompatibility(s)).recover { case _ => None }` | `Try(Option(client.getCompatibility(s))).toOption.flatten` |

**Alternatives considered**: *Add cats-core* — rejected: needs approval, disproportionate for one traverse, and the existing codebase (`SubjectResolver`, `Downloader`) already does `Either` for-comprehensions and manual accumulation in plain Scala.

---

## D3 — Compatibility is best-effort

**Decision**: `getCompatibility(subject)` is wrapped so that *any* failure or absence yields `None`, never an error. The presentation layer renders `None` as `(default)`.

**Rationale**: Confluent's `getCompatibility(subject)` throws `RestClientException` (HTTP 404, code 40401) when no **subject-level** compatibility override exists — the common case where the global default applies. Treating that as a failure would make most listings fail. FR-012 mandates best-effort. Only `getAllSubjects` (D1) and `getAllVersions` (D1) are hard-fail paths.

---

## D4 — Concurrency: parallel per-subject fetch, reusing `schemaRegistryParallelism`

**Decision**: Fetch each subject's versions + compatibility concurrently on a bounded thread pool sized by the existing `schemaRegistryParallelism` setting (`1` = sequential, max `32`). Mirror `ParallelDownloader`'s pattern: `Executors.newFixedThreadPool(parallelism)` + `Future.sequence` + `Await`, pool shut down in `finally`. Fail-fast preserved: the first `Left` (in subject order) wins.

**Rationale**: A registry with hundreds of subjects means `2N` HTTP calls (`getAllVersions` + `getCompatibility` per subject). The plugin already ships a parallel downloader; doing listing sequentially while downloads are parallel is inconsistent and needlessly slow. Reusing `schemaRegistryParallelism` (already validated 1–32) keeps one knob for the user. `SchemaRegistryClient` (`CachedSchemaRegistryClient`) is thread-safe and already used concurrently by `ParallelDownloader`. `parallelism == 1` keeps the simple sequential, short-circuiting `foldLeft` path.

**Note**: This supersedes the initial "sequential v1" decision (the deferred `/speckit-clarify` perf item) — implemented directly at the maintainer's request rather than deferred.

**Alternatives considered**:
- *Sequential only* — rejected: inconsistent with the parallel download task; slow on large registries.
- *Separate `schemaRegistryListParallelism` setting* — rejected: an extra knob for no benefit; the download budget is the right reuse.

---

## D5 — Filter: new `schemaRegistrySubjectFilter` setting, case-insensitive substring

**Decision**: Add `schemaRegistrySubjectFilter: settingKey[Option[String]]`, default `None`. When `Some(text)`, keep subjects whose name contains `text` ignoring case (`name.toLowerCase.contains(text.toLowerCase)`). Empty string ⇒ matches all.

**Rationale**: Matches issue #32's `schemaRegistrySubjectFilter := Some("order")` and the `/speckit-clarify` decision (case-insensitive substring). Deliberately distinct from the existing `schemaRegistrySubjectPatterns: Seq[String]` (regex, full-match, used by *download*) — different semantics and different task; overloading that key would conflate download-selection with list-display filtering. Additive new key ⇒ backward compatible.

---

## D6 — Client API surface (Confluent `kafka-schema-registry-client` 8.2.1)

**Decision**: Use these `SchemaRegistryClient` methods, converting Java collections via `scala.collection.JavaConverters` (Scala 2.12):

| Method | Returns | Use |
|---|---|---|
| `getAllSubjects()` | `java.util.Collection[String]` | all subject names → `.asScala.toList.sorted` (already used by `SubjectResolver`) |
| `getAllVersions(subject)` | `java.util.List[Integer]` | versions → `.asScala.map(_.intValue).toList` |
| `getCompatibility(subject)` | `String` | subject-level compatibility; throws when unset → best-effort `None` (D3) |

**Rationale**: `getAllSubjects` is already in use, confirming the conversion idiom. `getAllVersions`/`getCompatibility` are standard `SchemaRegistryClient` interface methods present in 8.2.1. No new client capability required (FR-002: reuse existing connection).

---

## D7 — Value types: new `SubjectInfo` + `SubjectListing`

**Decision**: Introduce two immutable value types (one file each, per the single-responsibility file convention):
- `SubjectInfo(name: String, versions: List[Int], compatibility: Option[String])` with derived `latestVersion: Option[Int]` and `versionRange: String`.
- `SubjectListing(subjects: List[SubjectInfo])` with a pure `matching(filter: String): SubjectListing`.

Do **not** reuse `RegistrySubject` (sealed `Pinned`/`Latest`) — it models a download selection (name + optional pinned version), not a discovered subject's full version list + compatibility.

**Rationale**: Directly testable value types as issue #32 specifies; keep presentation (formatting/logging) out of them (FR-009).

---

## D8 — Task shape and wiring

**Decision**:
- Add `schemaRegistryListSubjects = taskKey[Unit]("List subjects in the schema registry")`.
- Body mirrors existing tasks exactly: `val logger = streams.value.log`; read settings via `.value`; `withRegistryClient(url, cacheSize, auth, properties) { client => SubjectExplorer.listAll(client, filter) match { case Left(e) => sys.error(e.message); case Right(listing) => /* log */ } }`.
- Core: `object SubjectExplorer { def listAll(client: SchemaRegistryClient, filter: Option[String]): Either[DownloadError, SubjectListing] }`.

**Rationale**: `taskKey[Unit]` matches all three existing tasks (`Download`/`Register`/`TestCompatibility`) and the "presentation at the edge" design. The core returns `Either[DownloadError, SubjectListing]` (no logging, no `sys.error`) per FR-009/FR-010. Insertion points (from the source map): new `taskKey` after the last key (~line 34), new `settingKey` near the others, default `None` in `defaultSettings` (~line 49), root→`Compile`-scoped delegation + body appended to `projectSettings` (after ~line 263). All additive ⇒ FR-013 backward compatibility holds.

---

## D9 — Testing strategy (three tiers, test-first)

**Decision**:
- **Unit** (`src/test`, mockito-scala): `SubjectExplorerSpec` stubs `getAllSubjects`/`getAllVersions`/`getCompatibility` and verifies data extraction, sorting, filtering, fail-fast on version-fetch error, and best-effort compatibility. `SubjectInfoSpec` / `SubjectListingSpec` for pure derived values (`versionRange`, `latestVersion`, `matching`).
- **Integration** (`it/`, Testcontainers): register subjects in a real Schema Registry, call `SubjectExplorer.listAll`, assert real version ranges and compatibility — no HTTP mocking (Constitution III).
- **Scripted e2e** (`src/sbt-test/schema-registry/list-subjects/`): mirror `download-wildcard`, use the `docker.sbt` + `RegistryFixture` fixture, run `schemaRegistryListSubjects` and assert the task **succeeds**; a negative case asserts failure on an unreachable registry (`-> schemaRegistryListSubjects`).

**Rationale / limitation**: sbt `scripted` asserts on file side-effects and task success/failure, **not** on logged stdout (no `must-contain` statement exists). Content correctness (versions, compatibility, filter results) is therefore proven by the `it/` integration test against a real registry; the scripted test proves the sbt wiring (task runs, settings honored, failure surfaces). If log-content assertion is later required, the scripted build can add a tiny check task that writes `SubjectExplorer` output to a file and assert with `$ must-mirror`.

---

## Resolved unknowns summary

| Unknown | Resolution |
|---|---|
| Which error ADT? | `DownloadError` (+ one new `SubjectVersionsFetchFailed` case) — D1 |
| cats available? | No → plain stdlib — D2 |
| Compatibility-absent handling | Best-effort `None` → `(default)` — D3 |
| Sequential vs parallel fetch | Sequential v1; parallel deferred — D4 |
| Filter key & semantics | New `schemaRegistrySubjectFilter: Option[String]`, case-insensitive substring — D5 |
| Client methods | `getAllSubjects` / `getAllVersions` / `getCompatibility` (8.2.1) — D6 |
| Reuse `RegistrySubject`? | No; new `SubjectInfo`/`SubjectListing` — D7 |
| Task return type | `Unit` (logs); core returns `Either[DownloadError, SubjectListing]` — D8 |
| Log-content e2e assertion | Covered by `it/`; scripted asserts success/failure — D9 |

No `NEEDS CLARIFICATION` remain.
