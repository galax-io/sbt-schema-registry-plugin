# Contract: Public sbt key surface (frozen — both axes)

**Feature**: [../spec.md](../spec.md) · **Date**: 2026-06-22

This is the backward-compatibility contract (Constitution I, FR-003, SC-004). Every key below MUST keep its **exact name, type, and observable semantics on both the sbt-1 (Scala 2.12) and sbt-2 (Scala 3) axes**. Cross-build is purely additive — nothing here may be renamed, retyped, removed, or change default/behavior.

## Task keys — `taskKey[Unit]`

| Key | Semantics (identical on both axes) |
|-----|-----------------------------------|
| `schemaRegistryDownload` | download schemas from registry → writes files to target folder |
| `schemaRegistryRegister` | register (push) schemas to registry |
| `schemaRegistryTestCompatibility` | check schema compatibility against registry |
| `schemaRegistryListSubjects` | list registry subjects with versions + compatibility |

**Side-effect guarantee (new, sbt-2 axis)**: each task performs its real side effect on **every** invocation; sbt 2.x task-result caching MUST NOT skip it (FR-004, via the `PluginCompat.uncached` seam, D7). Observable behavior matches the sbt-1 axis.

## Setting keys — `settingKey[T]`

| Key | Type | Default | Frozen |
|-----|------|---------|--------|
| `schemaRegistryUrl` | `String` | `"http://localhost:8081"` | ✓ |
| `schemaRegistryTargetFolder` | `File` | `sourceDirectory/main/avro` | ✓ |
| `schemaRegistrySubjects` | `Seq[RegistrySubject]` | `Seq()` | ✓ |
| `schemaRegistryRegistrations` | `Seq[RegistryRegistration]` | `Seq()` | ✓ |
| `schemaRegistryCacheSize` | `Int` | `Downloader.defaultCacheSize` | ✓ |
| `schemaRegistryAuth` | `Option[SchemaRegistryAuth]` | `None` | ✓ |
| `schemaRegistryProperties` | `Map[String, String]` | `Map.empty` | ✓ |
| `schemaRegistrySubjectPatterns` | `Seq[String]` | `Seq()` | ✓ |
| `schemaRegistryIncremental` | `Boolean` | `true` | ✓ |
| `schemaRegistryParallelism` | `Int` | `4` | ✓ |
| `schemaRegistryRetries` | `Int` | `3` | ✓ |
| `schemaRegistryResolveReferences` | `Boolean` | `true` | ✓ |
| `schemaRegistrySubjectFilter` | `Option[String]` | `None` | ✓ |

**Note on `schemaRegistryTargetFolder: File`**: stays `sbt.File` on both axes. If sbt 2.x ever forces the `File`→`xsbti.HashedVirtualFileRef` change for this setting, it is absorbed behind the `PluginCompat` seam (D4) so the *public type the user sets* remains `File`. Not expected to be needed.

## Public domain types (referenced by keys — unchanged)

`RegistrySubject` (sealed ADT: Pinned/Latest), `RegistryRegistration`, `SchemaRegistryAuth`. These compile identically on Scala 2.12 and Scala 3 (sealed-trait ADTs need no `enum` conversion). No signature changes.

## Verification

- Public-key-surface review (manual diff) confirms zero renames/retypes/removals (D14, SC-004).
- Scripted e2e on the sbt-1 axis stays green unchanged (D14, US2).
- A fresh sbt-2 project resolves these keys at their documented defaults and runs all four tasks with identical observable results (US1, SC-001).
