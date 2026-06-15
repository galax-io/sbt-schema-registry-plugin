# Quickstart Validation Guide: Incremental Schema Download

**Date**: 2026-06-15 | **Spec**: [spec.md](spec.md) | **Data Model**: [data-model.md](data-model.md)

## Prerequisites

- Docker running (for integration and scripted tests)
- Java 17+
- sbt 1.12.x

## Validation Scenarios

### V1: Unit Tests — Pure Planning Logic

Validates FR-012 (pure function), FR-003–FR-007 (version comparison logic).

```bash
sbt test
```

**Expected**: All tests pass, including new `IncrementalResolverSpec` and `VersionManifestSpec`.

**Key test cases to verify**:
- Pinned subject with matching manifest version → Skip
- Pinned subject with different manifest version → Download
- Latest subject with matching registry version → Skip
- Latest subject with newer registry version → Download
- Subject not in manifest → Download (reason: "new")
- Version check failure → Download (reason: "version check failed")
- Empty/corrupt manifest → treat as empty, all subjects download
- Manifest JSON round-trip serialization

### V2: Integration Tests — Real Registry Behavior

Validates end-to-end incremental behavior with real Schema Registry.

```bash
sbt it/test
```

**Expected**: New `IncrementalDownloadIntegrationSpec` passes — download, re-run, verify skip, register new version, verify download.

**Key integration scenarios**:
1. Download all subjects → manifest created
2. Re-run same subjects → all skipped (zero schema fetches)
3. Register new schema version → re-run → only changed subject downloaded
4. Delete manifest → re-run → all subjects downloaded fresh

### V3: Scripted Tests — sbt Plugin Behavior

Validates sbt setting wiring, logging output, and `sbt clean` behavior.

```bash
sbt scripted
```

**Expected**: New scripted test(s) pass, existing scripted tests unaffected.

**Key scripted scenarios**:
- `download-incremental/`: download → re-run → verify second run skips (check log output)
- Verify `schemaRegistryIncremental := false` forces full download
- Verify `sbt clean` removes manifest

### V4: Format & Compile Check

```bash
sbt scalafmtAll scalafmtSbt
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

**Expected**: No format violations, no compile warnings (fatal warnings enabled).

## Manual Smoke Test

For quick local validation without full test suite:

1. Start a local Schema Registry (or use Testcontainers fixture)
2. Configure `build.sbt` with a subject
3. Run `sbt schemaRegistryDownload` — observe "Downloading" log for all subjects
4. Run `sbt schemaRegistryDownload` again — observe "up to date" log for all subjects
5. Check `target/streams/compile/schemaRegistryDownload/` for `.schema-versions.json`
6. Run `sbt clean schemaRegistryDownload` — observe all subjects re-downloaded
