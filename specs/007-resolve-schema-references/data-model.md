# Phase 1 Data Model: Auto-Download Schema References

Maps the spec's Key Entities onto concrete Scala types — reusing existing ones wherever they
fit, adding exactly one new value type.

## New types

### `ResolvedSchema` (NEW)

A schema fetched during resolution, carrying just enough to continue the graph walk. Lives in
`ReferenceResolver.scala` (companion or top-level).

```scala
final case class ResolvedSchema(
    subject: String,
    version: Int,                       // concrete, post-fetch (roots' latest is resolved here)
    references: List[SchemaReference],  // REUSES existing org.galaxio.avro.SchemaReference
)
```

- **Maps to spec entity**: "Resolved Schema".
- **Why no `content` field**: the resolver only needs the reference graph; bodies are written by
  the existing `Downloader` in the parallel stage (Decision 4 in research.md). Adding a body
  here would duplicate `Downloader.writeSchema`. Kept minimal deliberately.
- **Validation**: `version` is the registry-resolved integer; for referenced schemas it equals
  the reference's pinned version; for roots requested at latest it is the resolved latest.

## Reused types (no changes)

### `SchemaReference` (REUSE as-is)

```scala
final case class SchemaReference(name: String, subject: String, version: Int)
```

Already exactly the spec's "Schema Reference" entity (logical name, target subject, pinned
version). `version: Int` matches "references always carry a pinned version". **Do not** add the
issue's proposed `SchemaRef` — it would collide/duplicate.

### `RegistrySubject` (REUSE)

```scala
sealed trait RegistrySubject { def name: String }
object RegistrySubject {
  final case class Pinned(name: String, version: Int) extends RegistrySubject
  final case class Latest(name: String)               extends RegistrySubject
}
```

Resolver **output** is `List[RegistrySubject.Pinned]` (every resolved node pinned to its concrete
version). When resolution is disabled, the original roots (possibly `Latest`) pass through
unchanged.

### `DownloadError` (REUSE — no new case)

Fail-fast uses the existing case:

```scala
final case class SchemaFetchFailed(subject: String, error: Throwable) extends DownloadError
```

The injected `fetch` returns `Left(SchemaFetchFailed(subject, cause))`; the resolver propagates
it unchanged (FR-008). No new error case is introduced.

## Identity & dedup keys (the crux)

Two **distinct** keys (research.md Decision 3) — conflating them is the canonical bug:

| Key | Type | Recorded | Role |
|-----|------|----------|------|
| Enqueue key | `(String, Option[Int])` — *requested* identity (`None` = latest) | before fetch | prevents re-enqueue; cycle safety; keeps divergent versions (`B@1` vs `B@2` distinct) |
| Visited key | `(String, Int)` — *resolved* identity | after fetch | result dedup (shared deps); equals filename stem `subject-version` |

- **Spec identity rule** (clarification 2026-06-19): a resolved schema is identified by
  **subject + version**. The visited key is that identity.
- **Divergent diamond** (`A→B@1`, `A→C→B@2`): distinct enqueue keys → both fetched → distinct
  visited keys → both in output → `B-1.ext` and `B-2.ext` both written.
- **Cycle** (`A↔B`): `B`'s back-edge to `A` is dropped at enqueue (A already enqueued) → loop
  terminates, `A` fetched once.
- **Shared dep** (`A→B`, `A→C`, `B→D`, `C→D`): second `D` skipped at enqueue/visited → `D`
  written once.

## State / flow

```
roots: List[RegistrySubject]            (from SubjectResolver — Exact + pattern-matched)
        │
        ▼  ReferenceResolver.resolve(roots, fetch)   [pure, @tailrec BFS]
        │     queue: Queue[(subject, Option[Int])]
        │     enqueued: Set[(String, Option[Int])]   (pre-fetch dedup)
        │     visited:  Set[(String, Int)]           (post-fetch dedup)
        │     acc:      Vector[RegistrySubject.Pinned]  (roots-first)
        ▼
expanded: List[RegistrySubject.Pinned]  (roots first, then transitive refs, deduped)
        │
        ▼  IncrementalResolver.plan(manifest, expanded, registryVersions)  [unchanged]
        ▼  ParallelDownloader.downloadAll(toDownload)                       [unchanged]
        ▼  files: <subject>-<version>.<ext>   +   manifest update
```

`fetch` signature (injected at the plugin call site, closure over the shared cached client):

```scala
fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema]
// version = None  -> client.getLatestSchemaMetadata(subject)
// version = Some(v) -> client.getByVersion(subject, v, false)
// references via Option(meta.getReferences).map(_.asScala.toList).getOrElse(Nil)
//                  .map(r => SchemaReference(r.getName, r.getSubject, r.getVersion.intValue))
```

## Relationships

- `ResolvedSchema.references : List[SchemaReference]` — children to enqueue.
- `SchemaReference.subject + version` — becomes the next `(subject, Some(version))` enqueue key
  and, post-fetch, a `RegistrySubject.Pinned` output entry.
- Output `RegistrySubject.Pinned.name + version` — becomes the download filename stem and the
  incremental-manifest comparison key (subject-name only on the manifest side — see known
  limitation in research.md Decision 4).
