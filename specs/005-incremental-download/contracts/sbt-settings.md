# Contract: sbt Settings & Task Behavior

**Date**: 2026-06-15 | **Feature**: Incremental Schema Download

## New Settings

### `schemaRegistryIncremental`

| Property | Value |
|----------|-------|
| Type | `SettingKey[Boolean]` |
| Default | `true` |
| Scope | Global (applies to all configurations) |
| Description | Enable incremental download — skip schemas whose registry version matches the local manifest |

**Behavior when `true`** (default):
- Before downloading, load version manifest from cache directory
- For each subject, compare manifest version against expected version
- Skip subjects with matching versions; download only changed/new subjects
- Update manifest after successful downloads
- Log each decision (skip/download) with reason

**Behavior when `false`**:
- Ignore manifest entirely
- Download all configured subjects unconditionally
- Do not read or update manifest
- Equivalent to pre-feature behavior

## Modified Task Behavior

### `schemaRegistryDownload`

**Backward compatibility**: Fully backward compatible. Existing users see no behavior change on first run (all subjects download, manifest created). Subsequent runs benefit from caching automatically.

**New log output format**:

Per-subject decisions:
```
[info] user-events v3 → up to date
[info] order-events: local v5 → registry v7
[info] payment-value: new, registry v1
[info] broken-subject: version check failed, re-downloading
```

Summary line:
```
[info] 2 downloaded, 1 skipped
```

**Force re-download**:
- `sbt clean schemaRegistryDownload` — deletes manifest + all cached state
- `set schemaRegistryIncremental := false` — bypasses caching for current session

## Manifest File

| Property | Value |
|----------|-------|
| Location | `streams.value.cacheDirectory / ".schema-versions.json"` |
| Format | JSON: `{"subject-name": versionInt, ...}` |
| Lifecycle | Created on first download, updated each run, deleted by `sbt clean` |
| Scope | Per-task (Compile and Test have separate manifests) |
