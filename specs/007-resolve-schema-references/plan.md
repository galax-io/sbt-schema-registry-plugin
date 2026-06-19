# Implementation Plan: Auto-Download Schema References

**Branch**: `007-resolve-schema-references` | **Date**: 2026-06-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-resolve-schema-references/spec.md`

## Summary

Add transitive resolution of schema references to the download task. When a downloaded
schema declares references (Confluent `SchemaMetadata.getReferences`), the plugin walks the
reference graph and downloads every dependency, pinned to its exact version. Resolution is a
**new pipeline stage** inserted between wildcard-expansion and incremental-skip:
`wildcard-expand → resolve-references → incremental-skip → parallel-fetch+write`.

The core is a **pure, `@tailrec`, stack-safe BFS resolver** (`ReferenceResolver`) that takes
an injected `fetch` function and returns `Either[DownloadError, List[RegistrySubject]]`
(roots first, then transitive refs, all `Pinned`). Identity is **subject+version** (per spec
clarification 2026-06-19): a two-level dedup keeps the resolver both cycle-safe and able to
keep divergent versions of the same subject. A new boolean setting
`schemaRegistryResolveReferences` (default `true`) gates the stage; when `false` it is an
identity pass-through, preserving today's behavior byte-for-byte.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt plugin Scala)

**Primary Dependencies**: Confluent `kafka-schema-registry-client` 8.2.1 (already pinned in
`project/Dependencies.scala`); no cats dependency (project uses right-biased `scala.Either`)

**Storage**: Filesystem — schema files written as `<subject>-<version>.<ext>` to
`schemaRegistryTargetFolder`; incremental manifest `.schema-versions.json`

**Testing**: ScalaTest `AnyFlatSpec` + Matchers + mockito-scala (unit); Testcontainers
(Confluent Kafka + Schema Registry) for `it/` integration; sbt `scripted` for plugin e2e

**Target Platform**: JVM (Java 17+), consumed as an sbt 1.12.x autoplugin

**Project Type**: Single-project sbt plugin (library/build-tool)

**Performance Goals**: Reference graph walk is sequential metadata fetches; bulk body download
stays parallel via the existing `ParallelDownloader`. Resolution adds roughly one metadata REST
round-trip per schema (its `getSchemaMetadata` call is not served from the download fetch's
id-keyed cache) — acceptable because graphs are small and resolution runs once. No new
performance target.

**Constraints**: Compile with `-Xfatal-warnings` (no warnings); `scalafmtAll` +
`scalafmtSbt` must pass; backward-compatible public API (additive keys only); resolver must
be pure and stack-safe for deep chains; fail-fast on first fetch error.

**Scale/Scope**: One new pure class (`ReferenceResolver`) + one new value type
(`ResolvedSchema`) + one new setting; ~3 edited files (`SchemaDownloaderPlugin`, plus reuse
of `Downloader`/`SchemaReference`/`DownloadError`). Reference graphs in practice are small
(tens of nodes); design is correct for arbitrary depth and cycles.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment | Status |
|-----------|-----------|--------|
| I. Backward Compatibility | Only additive public surface: one new `settingKey` (`schemaRegistryResolveReferences`, default `true`). No task/key renamed or removed. Opt-out (`:= false`) restores prior behavior exactly (SC-004). New default changes *output* (extra files appear) but not *API*; treated as additive enhancement per spec Assumptions. No major bump required. | ✅ PASS |
| II. Single Responsibility | `ReferenceResolver` owns graph traversal only (no IO, no client, no `Files`). `ResolvedSchema` models fetched-schema data. Existing `SchemaReference`, `DownloadError.SchemaFetchFailed`, `RegistrySubject.Pinned`, `Downloader`, `ParallelDownloader`, `IncrementalResolver` are reused, not duplicated. The `fetch` effect is **injected** (function arg), never constructed internally — mirrors `IncrementalResolver`'s `registryVersions` seam. | ✅ PASS |
| III. Test-First | Tests precede implementation: `ReferenceResolverSpec` (pure, Map-stub fetch: transitive, cycle, shared-dep, divergent diamond, fail-fast, fetch-count proof); `it/` integration registers a schema with a reference then downloads root and asserts both files on disk; sbt `scripted` `resolve-references` test for plugin wiring. No mocking of Schema Registry HTTP where a real path exists (integration uses Testcontainers). | ✅ PASS |
| IV. Trunk-Based Release | Work proceeds on a feature branch off `main` (`007-resolve-schema-references`); no version reuse, no direct `main` commits, rebase-only. | ✅ PASS |
| V. Format & Verify | `scalafmtAll`/`scalafmtSbt` before commit; new code compiles under `-Xfatal-warnings`. Confluent `getVersion(): java.lang.Integer` unboxed explicitly to avoid warnings/NPE. | ✅ PASS |

**Result**: No violations. Complexity Tracking table left empty.

### Post-Design Re-check (after Phase 1)

Design introduces exactly one new class, one new value type, one new setting; all other
behavior reuses existing components. The one acknowledged compromise — the subject-keyed
`VersionManifest` cannot track two versions of one subject, so a divergent-version schema
may be redundantly re-written on later runs (never incorrect) — is documented as a known
limitation and a follow-up, **not** smuggled into this feature as a manifest-format change.
This *preserves* Single Responsibility (no manifest redesign here) rather than violating it.
Constitution Check still **PASS**; no entries needed in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/007-resolve-schema-references/
├── spec.md              # Feature specification (input)
├── plan.md              # This file (/speckit-plan output)
├── research.md          # Phase 0 output — decisions on API, ordering, resolver pattern
├── data-model.md        # Phase 1 output — ResolvedSchema, identity keys, reuse map
├── quickstart.md        # Phase 1 output — runnable validation scenarios
├── contracts/
│   ├── reference-resolver.md   # Pure resolver API contract + dedup semantics
│   └── plugin-setting.md       # schemaRegistryResolveReferences setting contract
└── checklists/
    └── requirements.md  # Spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
src/main/scala/org/galaxio/avro/
├── ReferenceResolver.scala      # NEW — pure @tailrec BFS resolver + ResolvedSchema
├── SchemaDownloaderPlugin.scala # EDIT — new setting + insert resolve stage in download task
├── SchemaReference.scala        # REUSE as-is — (name, subject, version: Int) reference entity
├── Downloader.scala             # REUSE — fetchSchema is the model for the injected fetch;
│                                #         filename scheme already subject+version
├── DownloadError.scala          # REUSE — SchemaFetchFailed covers fetch failures (fail-fast)
├── RegistrySubject.scala        # REUSE — emit refs as RegistrySubject.Pinned
├── IncrementalResolver.scala    # REUSE unchanged — operates on the expanded list
└── ParallelDownloader.scala     # REUSE unchanged — downloads the expanded list in parallel

src/test/scala/org/galaxio/avro/
└── ReferenceResolverSpec.scala  # NEW — pure unit tests with Map-based stub fetch

it/src/test/scala/org/galaxio/avro/
└── ReferenceResolutionIntegrationSpec.scala  # NEW — Testcontainers: register ref → download → verify files

src/sbt-test/schema-registry/
└── resolve-references/          # NEW — scripted e2e: build.sbt + test + project/plugins.sbt
    ├── build.sbt
    ├── test
    └── project/plugins.sbt
```

**Structure Decision**: Single-project sbt plugin (matches existing `org.galaxio.avro`
layout). The only new production file is `ReferenceResolver.scala`; everything else either
reuses an existing type or edits the single wiring point (`SchemaDownloaderPlugin`). Tests
follow the established three-tier convention (unit / `it` Testcontainers / `scripted`).

## Complexity Tracking

> No Constitution violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
