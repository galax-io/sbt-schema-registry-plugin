# Implementation Plan: Cross-build for sbt 2.x (add a Scala 3 axis)

**Branch**: `009-sbt2-cross-build` | **Date**: 2026-06-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-sbt2-cross-build/spec.md`

## Summary

Make the published plugin usable from **sbt 2.x** builds while leaving today's **sbt 1.x** users untouched. sbt 2.x runs all plugins on Scala 3 (3.8.x), so this is an **additive second build axis**, not a migration off Scala 2.12: one shared source tree cross-builds to a `_2.12_1.0` artifact (sbt 1.x) and a `_sbt2_3` artifact (sbt 2.x). The mechanism is sbt's `crossScalaVersions` + a `pluginCrossBuild / sbtVersion` mapping keyed on `scalaBinaryVersion` (D1; needs sbt ≥ 1.10.2, satisfied at 1.12.12). The plugin's sbt API surface is unchanged by sbt 2.x, so the work is dominated by (a) a few cross-compatible source fixes the strict `-Xfatal-warnings` flag forces (`JavaConverters`→`scala.jdk`, `Either` projections→`EitherValues`), (b) wrapping the four side-effecting tasks so sbt 2.x's default task caching can't silently skip their I/O (`PluginCompat.uncached` seam), (c) repinning the `it` module's `util-logging` per axis (the one hard resolution blocker), and (d) a CI matrix + single-tag cross-publish. Per clarification: publish `_sbt2_3` now against the latest sbt 2.x GA (2.0.0); gate scripted per-fixture by axis; one `v*` tag emits both coordinates.

## Technical Context

**Language/Version**: Scala 2.12.21 (sbt-1 axis) **and** Scala 3.8.x — e.g. 3.8.4, pinned exactly (sbt-2 axis)

**Primary Dependencies**: Confluent `kafka-schema-registry-client` 8.2.2 + protobuf/json providers (Java, no Scala axis); `org.scala-sbt %% util-logging` (axis-tracked: 1.12.x line on sbt-1, `2.0.0` on sbt-2 — decoupled from plugin `sbtV`, D8); test: ScalaTest 3.2.20 (`_3` ✓), mockito-scala-scalatest 2.2.1 (`_3` ✓, inline-macro caveat D9); new build dep: `scala-collection-compat` (backports `scala.jdk` to 2.12, D5 — owner approval per "ask first: new deps")

**Build tool**: build-side sbt 1.12.12; cross-targets sbt 1.x (1.12.12) and sbt 2.x (2.0.0, latest GA)

**Storage**: N/A (build/release tooling; reads a remote Schema Registry at task runtime, no local persistence)

**Testing**: unit (`+test`, both axes), integration (`+it/test`, Testcontainers, both axes), scripted (`scripted`, per-fixture axis-gated, D10)

**Target Platform**: JVM (Java 17+); the plugin runs inside both sbt 1.x and sbt 2.x builds

**Project Type**: Single published sbt plugin, cross-built (one project + `it/` integration subproject)

**Performance Goals**: N/A (build-time tooling; no latency/throughput target)

**Constraints**: `-Xfatal-warnings` on released builds, both axes (Constitution V; transiently relaxed via `-Wconf` only during migration, D13); backward compatibility of the `_2.12_1.0` artifact and all public keys (Constitution I); exact version pins, no `<latest>` (FR-010); single `v*` tag emits both coordinates under one version (Constitution IV)

**Scale/Scope**: ~6 source-fix files (JavaConverters), ~56 test sites (`Either`), 4 task-body wraps, 1 `PluginCompat` seam (2 tiny per-axis files), `build.sbt` + `project/Dependencies.scala` cross config, CI workflow matrix, `release.yml` cross-publish, scripted-fixture axis gating. No public API change.

## Constitution Check

*GATE: must pass before Phase 0 and re-checked after Phase 1.*

| Principle | Assessment | Status |
|---|---|---|
| I. Backward Compatibility | Purely additive: `_2.12_1.0` artifact, all key names/types/defaults/semantics unchanged (contracts/plugin-api.md). New axis only adds the `_sbt2_3` artifact. No major bump needed (sbt-1 users unaffected). | ✅ PASS |
| II. Single Responsibility | No responsibility changes. New `PluginCompat` seam owns exactly the sbt-version API differences (uncached, optional FileRef); dependencies still injected. | ✅ PASS |
| III. Test-First | Same three tiers, now matrixed over both axes; tests/fixtures authored/updated before the cross config flips green. mockito specs mock pure-logic collaborators only — no Schema Registry HTTP mocking (D9). | ✅ PASS |
| IV. Trunk-Based Release | Single `v*` tag cross-publishes both coordinates under one version (no reuse, no divergence); tags on `release/*`/`main`. `release.yml` cross-publish change requires owner approval ("ask first"). | ✅ PASS (with owner sign-off on release.yml) |
| V. Format & Verify | Released builds keep `-Xfatal-warnings` on both axes; the migration-time `-Wconf` relaxation is a transient scaffold removed before merge — end state has zero tolerated warnings; CI gate stays strict. | ✅ PASS |

**Result**: PASS — no unjustified violations. Complexity Tracking not required. Two boundary call-outs (not violations): the new `scala-collection-compat` dependency (D5) and the `release.yml` cross-publish edit (D12) both touch "ask first" boundaries → require owner approval during implementation.

*Post-Phase-1 re-check*: Design adds no new project, no cross-class responsibility bleed, no public-API break, no runtime dependency on the sbt-1 axis. The `PluginCompat` seam is the minimal construct needed for sbt-version divergence. Still PASS.

## Project Structure

### Documentation (this feature)

```text
specs/009-sbt2-cross-build/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1–D14
├── data-model.md        # Phase 1 — build/release entities
├── quickstart.md        # Phase 1 — validation scenarios V1–V6
├── contracts/
│   ├── plugin-api.md     # Phase 1 — frozen public sbt key surface (backward-compat contract)
│   └── cross-build.md    # Phase 1 — axis→artifact map, build commands, publish contract
├── checklists/
│   └── requirements.md   # spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT here)
```

### Source Code (repository root)

```text
build.sbt                           # MODIFY: crossScalaVersions := Seq("2.12.21","3.8.x");
                                    #         pluginCrossBuild/sbtVersion mapping by scalaBinaryVersion;
                                    #         decouple it-module util-logging version per axis (D8);
                                    #         add scala-collection-compat (D5); -Wconf migration scoping (D13)
project/
├── build.properties               # unchanged (build-side sbt 1.12.12)
└── Dependencies.scala             # MODIFY: util-logging axis-version helper; scala-collection-compat;
                                    #         keep confluent/testcontainers (Java) as-is

src/main/scala/org/galaxio/avro/
├── *.scala (shared)               # MODIFY 5 files: JavaConverters → scala.jdk.CollectionConverters (D5)
│                                  #   (Downloader, SubjectExplorer, SubjectResolver,
│                                  #    CompatibilityChecker, VersionManifest)
└── SchemaDownloaderPlugin.scala   # MODIFY: wrap 4 task bodies in PluginCompat.uncached{} (D7)

src/main/scala-2.12/org/galaxio/avro/
└── PluginCompat.scala             # NEW: uncached = identity (sbt-1 axis seam, D4/D7)

src/main/scala-3/org/galaxio/avro/
└── PluginCompat.scala             # NEW: uncached = Def.uncached (sbt-2 axis seam, D4/D7)

src/test/scala/org/galaxio/avro/
└── *Spec.scala                    # MODIFY: 56 Either projection sites → EitherValues (D6);
                                    #   if mock[T] inline error on Scala 3, refactor 8 mock specs (D9)

it/src/test/scala/org/galaxio/avro/
└── ReferenceResolutionIntegrationSpec.scala  # MODIFY: JavaConverters → scala.jdk (D5)

src/sbt-test/                       # MODIFY: per-fixture axis gating (D10); fixtures pulling
                                    #   sbt-1-only externals (sbt-avrohugger) stay sbt-1

.github/workflows/
├── ci.yml                         # MODIFY: Scala-axis matrix (2.12, 3.8.x): compile+test+it; scripted gated (D11)
└── release.yml                    # MODIFY (owner approval): single v* tag → cross +publishSigned both coords (D12)

AGENTS.md                          # MODIFY: stack/commands/release describe dual-axis flow (FR-011)
```

**Structure Decision**: Single cross-built plugin. ~All source stays in the shared `src/main/scala/`; the only per-axis source is the two-file `PluginCompat` seam under `scala-2.12/` and `scala-3/`, selected automatically by sbt per cross step (D4). The `it` module and scripted fixtures need no source split — their axis differences live in build config (util-logging version) and fixture gating respectively.

## Complexity Tracking

> No Constitution violations requiring justification. The `scala-collection-compat` dependency and `release.yml` edit are owner-approval boundary items, not complexity violations — tracked in tasks, not here.

## Phase notes

- **Phase 0 (research.md)**: complete — D1–D14 resolve mechanism, versions, source layout, the three spec-deferred items (source-dir D4, mockito D9, backward-compat verification D14), and the one hard blocker (util-logging D8). No `NEEDS CLARIFICATION` remain.
- **Phase 1 (this run)**: data-model.md (build/release entities), contracts/ (frozen key surface + cross-build/publish contract), quickstart.md (V1–V6 + migration smoke-order). Agent context (`CLAUDE.md` SPECKIT markers) updated to point here.
- **Phase 2**: `/speckit-tasks` will derive the dependency-ordered task list. Suggested spine: forward-compatible source fixes first (D5, D6 — land on current 2.12 build), then the `PluginCompat` seam + task wraps (D7), then util-logging decoupling (D8), then the cross config (D1), then CI matrix (D11) and cross-publish (D12), with `-Xfatal-warnings` re-enabled before merge (D13).
