# Contract: Cross-build & publish

**Feature**: [../spec.md](../spec.md) · **Date**: 2026-06-22

The build/release contract for the dual-axis plugin. Realizes FR-001, FR-002, FR-005, FR-006, FR-008, FR-010, FR-011.

## Axis → artifact mapping

| Consumer | Scala | sbt API | pluginCrossBuild sbtVersion | Published coordinate |
|----------|-------|---------|----------------------------|----------------------|
| sbt 1.x build | 2.12.21 | 1.x | `1.12.12` | `sbt-schema-registry-plugin_2.12_1.0` |
| sbt 2.x build | 3.8.4 | 2.x | sbt 2.0.0 (latest GA) | `sbt-schema-registry-plugin_sbt2_3` |

`crossScalaVersions := Seq("2.12.21", "3.8.x")`; `pluginCrossBuild / sbtVersion` selects the sbt target by `scalaBinaryVersion`. Both built from one shared source tree (FR-005).

## Build commands (contract)

| Intent | Command (cross-aware) |
|--------|----------------------|
| compile both axes | `sbt +compile` |
| unit-test both axes | `sbt +test` |
| integration both axes | `sbt +it/test` (Docker required) |
| scripted, sbt-1 | `sbt ++2.12.21 scripted` |
| scripted, sbt-2 (gated fixtures) | `sbt ++3.8.x scripted` |
| format gate | `sbt scalafmtCheckAll scalafmtSbtCheck` |
| single-axis compile (debug) | `sbt ++3.8.x compile` |

## Publish contract (D12)

- A single `v*` tag triggers `sbt-ci-release` in cross form (`+publishSigned` aggregate) → emits **both** coordinates under one version number.
- Same version number across both coordinates; no per-axis divergence (Constitution IV).
- `release.yml` change to cross-publish requires owner approval ("ask first: release workflow changes").
- Tags only on `release/*` or `main`; never reuse a version; never delete a deployed tag (Constitution IV, AGENTS.md).

## Invariants

1. The sbt-1 / `_2.12_1.0` artifact's coordinates, version scheme, keys, and behavior are unchanged by this feature (FR-002, US2).
2. All pinned versions are exact; no `<latest>`/snapshot resolution (FR-010).
3. `util-logging`'s version is decoupled from the plugin's `pluginCrossBuild / sbtVersion` literal (FR-006, D8).
4. CI compiles + tests both axes and fails if either regresses (FR-008, SC-005).
5. Scripted on sbt-2 covers every fixture without an sbt-1-only external plugin; external-plugin fixtures are explicitly axis-gated (FR-007, D10).
