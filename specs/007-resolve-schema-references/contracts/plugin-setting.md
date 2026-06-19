# Contract: `schemaRegistryResolveReferences` setting + download-task wiring

## New setting (public API — additive)

In `SchemaDownloaderPlugin.autoImport`, beside the existing keys:

```scala
val schemaRegistryResolveReferences =
  settingKey[Boolean]("Auto-download schemas referenced by downloaded schemas (transitive)")
```

Default in `defaultSettings`:

```scala
schemaRegistryResolveReferences := true,
```

| Property | Value |
|----------|-------|
| Type | `Boolean` |
| Default | `true` |
| Scope | build (per-project) |
| Backward compatibility | additive key only; no rename/removal; no major bump |

## Download-task wiring contract

`Compile / schemaRegistryDownload` gains one stage. Order (research.md Decision 2):

```
1. SubjectResolver.resolve(client, specs)         -> roots: List[RegistrySubject]   (unchanged)
2. if (resolveReferences)                          -> expanded                       (NEW)
     ReferenceResolver.resolve(roots, fetch)
   else Right(roots)                               (identity pass-through)
3. IncrementalResolver.plan(manifest, expanded, …) -> decisions                      (unchanged)
4. ParallelDownloader.downloadAll(toDownload)      -> files                          (unchanged)
5. manifest update                                                                   (unchanged)
```

`fetch` closure (built by `Downloader.referenceFetch(client)`; shares the existing `client`. Note
it uses `getSchemaMetadata`, a different call than the download's `getByVersion`, so it adds ~1
metadata round-trip per schema rather than warming the body fetch):

```scala
val fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema] =
  (subject, version) =>
    Try {
      val meta = version.fold(client.getLatestSchemaMetadata(subject))(
        v => client.getByVersion(subject, v, false)
      )
      val refs = Option(meta.getReferences).map(_.asScala.toList).getOrElse(Nil)
        .map(r => SchemaReference(r.getName, r.getSubject, r.getVersion.intValue))
      ResolvedSchema(subject, meta.getVersion, refs)
    }.toEither.left.map(DownloadError.SchemaFetchFailed(subject, _))
```

> Note: confirm the exact body/version accessors against `Downloader.fetchSchema` at
> implementation time; reuse its proven calls (`getByVersion(name, v, false)` /
> `getLatestSchemaMetadata(name)`). `getReferences` may be null; `getVersion` is boxed `Integer`.

## Behavioral contract

| ID | Given | When | Then |
|----|-------|------|------|
| PS-1 | `resolveReferences := true` (default), subject with a reference | download | both root and referenced files on disk |
| PS-2 | `resolveReferences := false`, subject with a reference | download | only the root file; output identical to pre-feature (SC-004) |
| PS-3 | `resolveReferences := true`, subject with no references | download | only that file (acceptance 1.3) |
| PS-4 | `resolveReferences := true`, a referenced schema fetch fails | download | task fails with `SchemaFetchFailed` naming the subject; no silent partial (FR-008) |
| PS-5 | resolution + incremental both on, referenced file already current | download | referenced subject skipped by incremental (composes, FR-009) |
| PS-6 | resolution + parallelism > 1 | download | expanded list downloaded via bounded-concurrency `ParallelDownloader` (FR-009) |

## Test obligations

- **Scripted** `src/sbt-test/schema-registry/resolve-references/`: register a base schema +
  a dependent schema with a `SchemaReference` (follow existing `register-references` fixture),
  run `Compile / schemaRegistryDownload`, assert with `$ exists` / `$ must-mirror` that **both**
  `<base>-1.<ext>` and `<dependent>-1.<ext>` land on disk (PS-1). Add a second test (or a
  `>set` line) with `schemaRegistryResolveReferences := false` asserting the referenced file is
  **absent** (PS-2).
- **Integration** `it/...ReferenceResolutionIntegrationSpec`: Testcontainers Kafka + Schema
  Registry; register base + dependent-with-reference; drive the resolve+download path; assert
  both files exist with correct content (PS-1, PS-4 for the failure path).
