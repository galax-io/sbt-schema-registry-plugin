# Quickstart / Validation Guide: Cross-build for sbt 2.x

**Feature**: [spec.md](spec.md) · **Date**: 2026-06-22

Runnable scenarios proving the dual-axis build works end-to-end. Maps to Success Criteria. See [contracts/cross-build.md](contracts/cross-build.md) for commands and [contracts/plugin-api.md](contracts/plugin-api.md) for the frozen key surface.

## Prerequisites

- Build-side sbt 1.12.12 (≥ 1.10.2 required for the cross mapping).
- JDK 17+.
- Docker running (integration scenarios only).
- sbt 2.0.0 GA pinned in the build (D3).

## V1 — sbt 1.x consumer unaffected  *(SC-002, SC-004, US2)*

1. Take an existing sbt 1.x consumer build pinned to the previous plugin version.
2. Bump **only** the plugin version to the cross-built release.
3. Reload and run each task.

**Expected**: build resolves `…_2.12_1.0`; all four tasks behave exactly as before; no other build change required.

## V2 — sbt 2.x consumer, fresh project  *(SC-001, US1)*

1. Create a scratch project pinned to sbt 2.x.
2. Add the plugin; set `schemaRegistryUrl` and one `schemaRegistrySubjects` entry.
3. Run `schemaRegistryDownload`.

**Expected**: plugin's `…_sbt2_3` artifact resolves and applies; the schema file is written to the target folder; output matches the sbt-1 result.

## V3 — Side effects not skipped by sbt-2 caching  *(SC-006, FR-004, D7)*

1. On the sbt-2 project from V2, run `schemaRegistryDownload` twice in a row (no input change).

**Expected**: **both** runs perform the real download (file written/refreshed both times); the second run is NOT silently skipped by task-result caching. Implemented via the `PluginCompat.uncached` seam.

## V4 — Cross-compile both axes  *(FR-005)*

```
sbt +compile
```

**Expected**: compiles for Scala 2.12 (sbt 1.x) and Scala 3.8.x (sbt 2.x) from one checkout, with `-Xfatal-warnings` clean on both (D5, D6, D13).

## V5 — Test matrix both axes  *(SC-005, FR-007, FR-008)*

```
sbt +test            # unit, both axes
sbt +it/test         # integration, both axes (Docker)
sbt ++2.12.21 scripted     # scripted, sbt-1: all fixtures
sbt ++3.8.x   scripted     # scripted, sbt-2: fixtures without sbt-1-only externals
```

**Expected**: unit + integration green on both axes; scripted green on sbt-1 for all fixtures and on sbt-2 for non-external fixtures; external-plugin fixtures (e.g. sbt-avrohugger) explicitly gated to sbt-1, not silently skipped (D10).

## V6 — Single-tag cross-publish  *(SC-003, FR-011, D12)*

1. Dry-run the release path (or inspect a real `v*` tag run) with cross-publish enabled.

**Expected**: one version tag produces **both** `…_2.12_1.0` and `…_sbt2_3` under one version number; both resolve from the release repository.

## Migration smoke-order (local, during implementation)

Recommended order to keep the build iterable (mirrors research D5→D8):

1. `scala.collection.JavaConverters` → `scala.jdk.CollectionConverters` + add `scala-collection-compat` (D5).
2. Test `Either` projections → `EitherValues` (D6).
3. Add the `PluginCompat` seam + wrap the four tasks in `PluginCompat.uncached` (D7).
4. Decouple + repin `util-logging` per axis (D8).
5. Add `crossScalaVersions` + `pluginCrossBuild / sbtVersion` mapping (D1); relax `-Xfatal-warnings` via `-Wconf` while iterating, then re-enable (D13).
6. Wire CI matrix (D11) and the cross-publish release step (D12).

Steps 1–2 are forward-compatible and can land on the current 2.12 build first with zero downside.
