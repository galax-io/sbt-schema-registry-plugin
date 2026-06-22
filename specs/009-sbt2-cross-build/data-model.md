# Phase 1 Data Model: Cross-build for sbt 2.x

**Feature**: [spec.md](spec.md) · **Date**: 2026-06-22

This feature has **no application data model** — it changes how the plugin is built, tested, and published, not what it computes. The "entities" below are build/release artifacts and configuration constructs. The plugin's runtime domain types (`RegistrySubject`, `DownloadError`, `SubjectInfo`, …) are unchanged and must compile identically on both axes.

## Build/Release Entities

### CrossAxis
The (Scala version → sbt version) pairing that selects which artifact a consumer resolves and which API the plugin compiles against.

| Field | sbt-1 axis | sbt-2 axis |
|-------|-----------|-----------|
| `scalaVersion` | `2.12.21` | `3.8.x` (e.g. `3.8.4`, pinned) |
| `scalaBinaryVersion` | `2.12` | `3` |
| `pluginCrossBuild / sbtVersion` | `1.12.12` | `2.0.0` (latest GA) |
| compiled-against sbt API | sbt 1.x | sbt 2.x |
| `PluginCompat` seam dir | `src/main/scala-2.12/` | `src/main/scala-3/` |

**Rules**: `crossScalaVersions := Seq("2.12.21", "3.8.x")`; the sbtVersion mapping is keyed on `scalaBinaryVersion` (D1). The two axes are independent build steps over one shared source tree.

### PublishedArtifact
A coordinate emitted by a release. One version tag emits both (D12).

| Field | sbt-1 artifact | sbt-2 artifact |
|-------|---------------|----------------|
| coordinate suffix | `_2.12_1.0` | `_sbt2_3` |
| consumer | sbt 1.x builds | sbt 2.x builds |
| version number | shared (one `v*` tag) | shared (same tag) |
| compatibility | unchanged from today | new |

**Rules**: same version number across both coordinates (Constitution IV — no version reuse, no divergent per-axis numbers). Both must resolve from the release repository (SC-003).

### PluginCompat seam
A per-axis shim object unifying the handful of sbt-2-only APIs behind a stable signature.

| Member | sbt-1 (`scala-2.12/`) | sbt-2 (`scala-3/`) |
|--------|----------------------|--------------------|
| `uncached[A](a: => A): A` | `a` (identity) | `Def.uncached(a)` |

**Rules**: shared code references only `PluginCompat.*`; never calls `Def.uncached` directly. Added only because `Def.uncached` is sbt-2-only (D7). Extend with a `FileRef` bridge only if the `File`↔`HashedVirtualFileRef` difference ever bites (D4 — not expected).

### DependencyVersionMap
Per-axis version selection for Scala-versioned dependencies that differ across axes.

| Dependency | sbt-1 axis | sbt-2 axis | Notes |
|-----------|-----------|-----------|-------|
| `org.scala-sbt %% util-logging` (it) | 1.12.x line | `2.0.0` line | **decoupled** from plugin `sbtV` (D8) |
| `scala-collection-compat` | added (backports `scala.jdk`) | present/no-op | enables shared `scala.jdk` import (D5) |
| `scalatest` | `3.2.20` | `3.2.20` (`_3`) | same version, both axes |
| `mockito-scala-scalatest` | `2.2.1` | `2.2.1` (`_3`) | inline-macro caveat (D9) |
| confluent `*`, testcontainers | unchanged | unchanged | Java-only, no Scala axis |

**Rules**: `util-logging`'s version MUST NOT reuse the plugin's `pluginCrossBuild / sbtVersion` literal (D8). Pin exact versions; never resolve `<latest>` (FR-010).

### ScriptedFixture
An e2e test project under `src/sbt-test/`. Gains an axis-eligibility attribute (D10).

| Field | Value |
|-------|-------|
| `externalPluginDeps` | e.g. sbt-avrohugger, testcontainers, or none |
| `eligibleAxes` | `{sbt-1, sbt-2}` if no sbt-1-only external dep; `{sbt-1}` otherwise |

**Rules**: a fixture runs scripted on an axis only if all its external plugins have a build for that axis. External-plugin fixtures are explicitly gated, never silently skipped (SC-005).

## State / lifecycle

Single transition for the repository: **single-axis (sbt-1 only) → dual-axis (sbt-1 + sbt-2)**, additive. The sbt-1 artifact never changes coordinates or behavior across the transition (FR-002, US2). No per-request or runtime state is introduced.
