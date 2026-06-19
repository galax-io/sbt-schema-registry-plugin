# Phase 0 Research: Auto-Download Schema References

All findings grounded in the actual codebase (`org.galaxio.avro`, Scala 2.12.21,
`kafka-schema-registry-client` 8.2.1) and verified against the pinned 8.2.1 jar via `javap`.

## Decision 1 — Confluent references API

**Decision**: Read references off `SchemaMetadata.getReferences()`, obtained from the methods
the codebase already uses:

```java
SchemaMetadata getSchemaMetadata(String subject, int version)   // pinned ref
SchemaMetadata getLatestSchemaMetadata(String subject)          // latest root
// on SchemaMetadata:
java.util.List<io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference> getReferences()
// on rest.entities.SchemaReference:
String getName();  String getSubject();  java.lang.Integer getVersion();
```

**Rationale**: `Downloader.scala` already calls `getLatestSchemaMetadata` /
`client.getByVersion(name, version, false)`; references currently arrive on those results and
are simply dropped. Mapping to the repo's own `SchemaReference(name, subject, version: Int)`
needs only the three accessors.

**Critical correction (verified via `javap` on 8.2.1)**: `SchemaReference.getVersion()`
returns **boxed `java.lang.Integer`, not `int`**. Unbox explicitly to avoid NPE and
`-Xfatal-warnings` issues:
`Option(r.getVersion).map(_.intValue).getOrElse(...)` or, since refs are always pinned,
`r.getVersion.intValue`. Also treat `getReferences()` as **possibly null** —
`Option(meta.getReferences).map(_.asScala.toList).getOrElse(Nil)`.

**Conversion**: Scala 2.12 → use `import scala.collection.JavaConverters._` then `.asScala.toList`
(NOT `scala.jdk.CollectionConverters`, which is 2.13+). This matches the existing convention in
`SubjectResolver.scala`, `CompatibilityChecker.scala`, `VersionManifest.scala`.

**Alternatives considered**: `scala.jdk.CollectionConverters` (rejected — won't compile on
2.12); `getSchemaMetadata(subject, version, lookupDeleted)` 3-arg overload (unnecessary);
folding bodies into resolution to avoid a second fetch (rejected — see Decision 4).

**Verify at implementation time** (cannot run build here):
```bash
JAR=~/Library/Caches/Coursier/v1/https/packages.confluent.io/maven/io/confluent/kafka-schema-registry-client/8.2.1/kafka-schema-registry-client-8.2.1.jar
javap -cp "$JAR" io.confluent.kafka.schemaregistry.client.SchemaMetadata
javap -cp "$JAR" io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference
```

## Decision 2 — Pipeline placement

**Decision**: Resolution is a **new second stage**, between wildcard-expand and incremental-skip.
Final order: **wildcard-expand → resolve-references → incremental-skip → parallel-fetch+write →
manifest-update**.

**Rationale**:
- *After wildcard-expand*: references can't be discovered until patterns become concrete roots.
- *Before incremental-skip*: skip drops subject+versions already on disk. If skip ran first, a
  cached root would never be fetched and its references never discovered → silently lost
  transitive deps. "Discover full closure, then decide what's stale" is the only complete +
  incremental order.
- *Before parallel-fetch*: the content-writing stage must see the complete expanded list.

**Double-fetch tension**: resolution fetches metadata to read references; download fetches the
body. Both share the **same `CachedSchemaRegistryClient` instance** (threaded through
`withRegistryClient`, default cache 200). `getByVersion(subject, version, …)` is cached by
(subject, version), so resolution warms the cache and the download fetch is a cache hit. No new
caching layer — requirement is only "resolution and download share one client," which current
wiring already satisfies.

**Insertion point**: `SchemaDownloaderPlugin.scala`, between `SubjectResolver.resolve` (~line 127)
and `loadManifest` / `IncrementalResolver.plan` (~line 129–139).

**Alternatives considered**: resolve inside `Downloader.schemaSubjectToFile` (rejected — runs
in parallel fan-out, explodes registry load, can't expand the work-list); resolve inside
`SubjectResolver` (rejected — `SubjectResolver` needs the client only for `getAllSubjects`;
mixing reference traversal there violates single responsibility).

## Decision 3 — Pure resolver pattern (two-level dedup)

**Decision**: A pure `@tailrec` BFS over an immutable FIFO `Queue`, with **two distinct dedup
keys** — the single most important correctness point:

| Key | Type | Recorded | Purpose |
|-----|------|----------|---------|
| **Enqueue key** | `(subject, Option[Int])` requested identity | *before* fetch, at enqueue | stop re-queuing same work; cycle-safe before resolved versions are known |
| **Visited key** | `(subject, Int)` resolved | *after* fetch | result identity; dedup shared deps; matches filename/spec identity |

**Rationale**:
- A root requested at *latest* (`None`) resolves to a concrete version only **after** `fetch`.
  Keying the visited set on the request would make `None` and `Some(7)` look like different
  nodes → double-fetch/loop. So visited must key on the **resolved** `(subject, Int)`.
- Cycles (`A↔B`) must terminate **before** versions are known → the **enqueue** key (checked
  pre-fetch) is what guarantees cycle safety.
- Per spec clarification (identity = subject+version, divergent diamond keeps **both** B@1 and
  B@2), the enqueue key is the **requested identity `(subject, Option[Int])`**, not bare
  subject. This is the "divergent-version policy" — `B@1` and `B@2` are distinct enqueue keys,
  both fetched, both kept. (Bare-subject enqueue dedup would collapse them — rejected, would
  violate the clarified identity rule.)
- `@tailrec` with `(queue, enqueued, visited, acc)` compiles to a `while` loop → stack-safe for
  deep chains (FR-002, deep-chain edge case). `acc` is a `Vector` (O(1) append), `.toList` once
  at the end.
- FIFO `Queue` ⇒ BFS ⇒ roots appear before transitive refs (FR-010).
- Fail-fast: first `Left` from `fetch` short-circuits the loop (FR-008), reusing
  `DownloadError.SchemaFetchFailed(subject, cause)` — **no new error type needed**.

**Error model & effects**: stdlib right-biased `Either` (no cats in repo). The only effect is
the injected `fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema]`; the
resolver imports no client and touches no filesystem. This mirrors `IncrementalResolver`'s
`registryVersions: String => Either[DownloadError, Int]` seam → unit-testable with a Map stub.

**Alternatives considered**: DFS via List/stack (rejected — breaks roots-first ordering, deeper
recursion); single visited key (rejected — the classic bug, either loops on cycles or
double-fetches latest-vs-pinned); `Validated`/error accumulation (rejected — spec wants
fail-fast); `EitherT`/`F[_]` (rejected — `fetch` is synchronous, monad transformers add noise).

## Decision 4 — Output shape & dedup mapping

**Decision**: Resolver returns `Either[DownloadError, List[RegistrySubject]]` where every entry
is `RegistrySubject.Pinned(subject, resolvedVersion)` (refs always pinned; roots pinned to their
resolved version), roots first. Identity `(subject, version)` maps **directly** onto:
- **Filename** `s"$subject-$version.$ext"` (already subject+version) → "write each subject+version
  once" = "write each filename once". Divergent diamond writes `customer-value-1.avsc` and
  `customer-value-2.avsc` as distinct files, no collision, no special handling.
- **Incremental-skip**: the expanded `Pinned` list flows through `IncrementalResolver.plan`
  unchanged.

**Known limitation (flagged, follow-up — NOT in 007)**: `VersionManifest` keys by
**subject-name only** (one version per subject). When two versions of the same subject are
resolved, the manifest can record only the last-written one, so the other may be **redundantly
re-downloaded on a subsequent run** (a wasteful write of identical bytes — never incorrect).
Widening the manifest key to subject+version is a manifest-format change and belongs in its own
feature. Resolution still composes with incremental (FR-009); only the multi-version-of-one-
subject corner is sub-optimal.

**Alternative considered**: have `ResolvedSchema` also carry the body and bypass the download
fetch (rejected — would duplicate `Downloader.writeSchema`, and either serialize writes (lose
parallelism) or re-plumb parallelism into the resolver (lose purity); the shared cache already
removes the network cost of the second fetch).

## Decision 5 — Setting & backward compatibility

**Decision**: Add `schemaRegistryResolveReferences = settingKey[Boolean]("...")`, default
`true`, beside the existing toggles in `defaultSettings`. When `false`, the stage is an identity
pass-through (`roots => Right(roots)`) → behavior byte-for-byte identical to today (SC-004).

**Rationale / additive proof**: With `true` (new default), a subject **with** references now
*additionally* yields the referenced files; existing files are unchanged (same name, content,
location). A subject with **no** references produces identical output to today (acceptance 1.3).
Opt-out restores prior behavior exactly (FR-007, User Story 2). Matches existing capability
toggles (`schemaRegistryIncremental`, `schemaRegistryParallelism`, `schemaRegistryRetries`) in
style and default-on philosophy. Backward-compatible per Constitution I (additive key, no major
bump).

## Resolved unknowns

| Unknown | Resolution |
|---------|-----------|
| Reference API surface | `SchemaMetadata.getReferences()` → `rest.entities.SchemaReference` (Decision 1) |
| `getVersion` type | boxed `Integer` in 8.2.1 — unbox explicitly (Decision 1) |
| Where resolution runs | new stage between expand and skip (Decision 2) |
| Avoiding double network fetch | shared `CachedSchemaRegistryClient` (Decision 2) |
| Cycle + divergent-version correctness | two-level dedup: enqueue `(subj,Option[Int])` / visited `(subj,Int)` (Decision 3) |
| New error type? | No — reuse `DownloadError.SchemaFetchFailed` (Decision 3) |
| Output type | `List[RegistrySubject.Pinned]`, roots-first (Decision 4) |
| Incremental interaction | composes; subject-keyed manifest divergent-version corner is a flagged follow-up (Decision 4) |
| Setting name/default | `schemaRegistryResolveReferences`, default `true` (Decision 5) |

No `[NEEDS CLARIFICATION]` markers remain.
