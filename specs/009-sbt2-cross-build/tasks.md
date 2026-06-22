---
description: "Task list for Cross-build for sbt 2.x"
---

# Tasks: Cross-build for sbt 2.x (add a Scala 3 axis)

**Input**: Design documents from `specs/009-sbt2-cross-build/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: This is a build/release feature. "Tests" are the existing three-tier suites re-run on the new axis plus one new scripted fixture for the sbt-2 cache guard (Constitution III). Validation maps to quickstart V1–V6.

**Organization**: Grouped by user story. US1 = sbt 2.x works (P1, the new capability). US2 = sbt 1.x unaffected (P1, the regression guard). US3 = dual build/test/publish from one tree (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish carry no story label)

## Path Conventions

Single cross-built sbt plugin at repo root: `build.sbt`, `project/`, `src/main/scala/`, `src/main/scala-2.12/`, `src/main/scala-3/`, `src/test/scala/`, `src/sbt-test/`, `it/src/test/scala/`, `.github/workflows/`.

---

## Phase 1: Setup (prerequisites & boundary sign-off)

**Purpose**: Lock decisions and clear the two owner-approval boundary items before touching the build.

- [X] T001 Confirm build-side sbt ≥ 1.10.2 (currently 1.12.12 in `project/build.properties`) and fix the exact Scala 3 pin (3.8.x, e.g. `3.8.4`) per research [D1, D2]; record the chosen version as the source of truth for `build.sbt`.
- [X] T002 [P] Obtain owner approval for the two "ask first" boundary items: adding `scala-collection-compat` (research D5) and editing `.github/workflows/release.yml` to cross-publish (research D12). Block T005/T024 until approved.

**Checkpoint**: target versions fixed; boundary items approved.

---

## Phase 2: Foundational (blocking prerequisites for ALL stories)

**Purpose**: Make one shared source tree cross-compile on Scala 2.12 (sbt 1.x) and Scala 3.8.x (sbt 2.x). Blocks US1, US2, and US3. These are the cross-compatible source rewrites + the `PluginCompat` seam + the cross config.

- [X] T003 [P] Replace `import scala.collection.JavaConverters._` → `import scala.jdk.CollectionConverters._` in the 5 main files: `src/main/scala/org/galaxio/avro/Downloader.scala`, `SubjectExplorer.scala`, `SubjectResolver.scala`, `CompatibilityChecker.scala`, `VersionManifest.scala` [D5].
- [X] T004 [P] Replace `import scala.collection.JavaConverters._` → `import scala.jdk.CollectionConverters._` in `it/src/test/scala/org/galaxio/avro/ReferenceResolutionIntegrationSpec.scala` [D5].
- [X] T005 Add `org.scala-lang.modules %% scala-collection-compat` (backports `scala.jdk` to 2.12) in `project/Dependencies.scala` and wire it into `commonSettings` `libraryDependencies` in `build.sbt` [D5]. (depends on T002, T001; makes T003/T004 compile on the 2.12 axis)
- [X] T006 [P] Replace all 56 `Either` `.right.get`/`.left.get` projection sites with scalatest `EitherValues` (`.value`/`.left.value`) and mix in `EitherValues` across the affected specs under `src/test/scala/org/galaxio/avro/` and `it/src/test/scala/org/galaxio/avro/` [D6].
- [X] T007 [P] Create `src/main/scala-2.12/org/galaxio/avro/PluginCompat.scala` exposing `private[avro] def uncached[A](a: => A): A = a` (identity, sbt-1 seam) [D4, D7].
- [X] T008 [P] Create `src/main/scala-3/org/galaxio/avro/PluginCompat.scala` exposing `private[avro] def uncached[A](a: => A): A = Def.uncached(a)` (sbt-2 seam) [D4, D7].
- [X] T009 Wrap each of the 4 task bodies (`schemaRegistryDownload`, `schemaRegistryRegister`, `schemaRegistryTestCompatibility`, `schemaRegistryListSubjects`) in `PluginCompat.uncached { … }` in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` [D7]. (depends on T007, T008)
- [X] T010 Decouple and repin `org.scala-sbt %% util-logging` per axis in `build.sbt`/`project/Dependencies.scala` — sbt-1 axis → 1.12.x line, sbt-2 axis → `2.0.0` — selected by `scalaBinaryVersion`, separate from the plugin's `pluginCrossBuild / sbtVersion` literal [D8].
- [X] T011 Add `crossScalaVersions := Seq("2.12.21","3.8.x")` and the `pluginCrossBuild / sbtVersion` mapping keyed on `scalaBinaryVersion` (2.12 → 1.12.12, else → current sbt 2.x release) in `build.sbt`; add transient `-Wconf` source-version scoping to relax `-Xfatal-warnings` during migration [D1, D13]. (depends on T005, T010)

**Checkpoint**: `sbt +compile` builds both axes from one tree (warnings scoped, not yet strict). US1/US2/US3 can now proceed.

---

## Phase 3: User Story 1 — sbt 2.x project can apply the plugin and run every task (P1)

**Goal**: A fresh sbt 2.x build resolves `_sbt2_3`, applies the plugin, and every task runs with identical observable results — including real side effects on every run (no cache skip).

**Independent test**: From a scratch sbt-2 project, add the plugin, run `schemaRegistryDownload` twice → file written both times; run all four tasks → results match sbt-1.

- [X] T012 [US1] Add a scripted fixture under `src/sbt-test/schema-registry/sbt2-uncached-download/` that runs `schemaRegistryDownload` twice and asserts the schema file is (re)written on the **second** run — proves sbt-2 caching does not skip the side effect [SC-006, V3, D7].
- [X] T013 [P] [US1] Verify `sbt ++3.8.x compile` is clean on the Scala 3 axis [V4].
- [X] T014 [P] [US1] Run `sbt ++3.8.x test`; if the `mock[T]` macro fails (Scala 3 inline requirement), refactor the 8 mock specs (`RegistrarSpec`, `SubjectExplorerSpec`, `RetryPolicySpec`, `DownloadOrchestratorSpec`, `DownloaderSpec`, `CompatibilityCheckerSpec`, `SubjectResolverSpec`, `ParallelDownloaderSpec`) to inline call sites or mockito-core [D9].
- [X] T015 [P] [US1] Run `sbt ++3.8.x it/test` (Docker) green on the Scala 3 axis [SC-005].
- [X] T016 [US1] Run `sbt ++3.8.x scripted` over the sbt-2-eligible fixtures (incl. T012); external-plugin fixtures stay gated to sbt-1 [D10]. (depends on T012)
- [X] T017 [US1] Manual V2: in a scratch sbt-2 project, add the plugin, configure URL + one subject, run all four tasks; confirm `_sbt2_3` resolves and files/registry/listing match the sbt-1 result [SC-001].

**Checkpoint**: sbt 2.x consumers fully functional; cache-skip guard proven.

---

## Phase 4: User Story 2 — existing sbt 1.x users completely unaffected (P1)

**Goal**: The `_2.12_1.0` artifact, all public keys, and every behavior are unchanged by the cross-build.

**Independent test**: Bump only the plugin version in an sbt-1 consumer build → resolves `_2.12_1.0`, all tasks behave as before, zero other changes.

- [X] T018 [P] [US2] Verify `sbt ++2.12.21 compile test` green (regression on the sbt-1 axis) [US2].
- [X] T019 [P] [US2] Verify `sbt ++2.12.21 it/test` green (Docker) [US2].
- [X] T020 [P] [US2] Verify the **full** scripted suite green on sbt-1 (`sbt ++2.12.21 scripted`), all fixtures, unchanged [D14, US2].
- [X] T021 [US2] Public-key-surface review against `contracts/plugin-api.md` — confirm no key renamed/retyped/removed and all defaults unchanged [SC-004, D14].
- [X] T022 [US2] Confirm the `_2.12_1.0` coordinate and version scheme are unchanged (V1, FR-002).

**Checkpoint**: backward compatibility proven; sbt-1 users see no change.

---

## Phase 5: User Story 3 — build, test, publish both axes from one tree (P2)

**Goal**: CI guards both axes; a single `v*` tag cross-publishes both coordinates; docs describe the dual-axis flow.

**Independent test**: Push a change → CI compiles+tests both axes and fails if either breaks; a tag dry-run emits both `_2.12_1.0` and `_sbt2_3`.

- [X] T023 [US3] Add a Scala-axis matrix (`2.12.21`, `3.8.x`) to `.github/workflows/ci.yml`: each leg runs compile + test + it/test; scripted runs per-fixture axis-gated; keep the `scalafmtCheckAll scalafmtSbtCheck` gate [D11, FR-008].
- [X] T024 [US3] Update `.github/workflows/release.yml` so a single `v*` tag runs `sbt-ci-release` in cross form (`+publishSigned` aggregate) emitting both coordinates under one version [D12, FR-011]. (requires T002 approval)
- [X] T025 [P] [US3] Update `AGENTS.md` (Stack, Commands, Release Process) to describe the dual-axis build/test/single-tag cross-publish flow [FR-011].
- [X] T026 [US3] Validate V5 (full matrix green in CI) and V6 (tag dry-run / inspection emits both `_2.12_1.0` and `_sbt2_3` under one version) [SC-003, SC-005].

**Checkpoint**: dual-axis CI + release operational and documented.

---

## Phase 6: Polish & cross-cutting

**Purpose**: Restore the strict gate and final formatting.

- [X] T027 Remove the transient `-Wconf` migration scoping and re-enable `-Xfatal-warnings` on **both** axes; confirm `sbt +compile` is warning-clean (Constitution V, D13).
- [X] T028 [P] Run `sbt scalafmtAll scalafmtSbt`, then the full gate `sbt scalafmtCheckAll scalafmtSbtCheck +compile +test` green.
- [X] T029 [P] If T014 refactored mock specs, confirm their assertions produce identical results on both axes (no behavior drift) [D9].

---

## Dependencies

```text
Setup (T001, T002)
   └─> Foundational (T003–T011)            # T005 needs T002+T001; T009 needs T007+T008; T011 needs T005+T010
          ├─> US1 (T012–T017)              # P1 — new capability
          ├─> US2 (T018–T022)              # P1 — regression guard
          └─> US3 (T023–T026)              # P2 — needs US1+US2 green; T024 needs T002
                 └─> Polish (T027–T029)
```

- **Foundational blocks everything** — nothing cross-compiles until T003–T011 land.
- **US1 and US2 are independent** of each other (different axes, different validation) and can run in parallel once Foundational is done.
- **US3** should follow US1+US2 being green (CI/release codify what those prove). **T024** also gated on T002 approval.
- **Polish** last: re-enabling `-Xfatal-warnings` (T027) must come after both axes compile clean.

## Parallel execution examples

- **Foundational**: T003, T004, T006, T007, T008 are all `[P]` (distinct files) — run together; then T005 → T009 → T010 → T011 in order.
- **After Foundational**: launch US1 (T013, T014, T015 in parallel) and US2 (T018, T019, T020 in parallel) concurrently — two axes, no shared files.
- **US3 docs**: T025 `[P]` can be written while T023/T024 workflows are wired.

## Implementation strategy

1. **Land the forward-compatible fixes first (T003, T004, T006 + T005).** These improve the *current* sbt-1 build with zero downside and de-risk everything later — can merge before the axis is switched on.
2. **Add the seam + cross config (T007–T011).** Build now cross-compiles.
3. **MVP = US1 (T012–T017):** sbt 2.x consumers can use the plugin — the actual value of this feature.
4. **Guard with US2 (T018–T022)** in parallel — proves no sbt-1 regression.
5. **Codify in US3 (T023–T026)** so CI/release sustain both axes.
6. **Polish (T027–T029):** restore the strict warning gate, format, verify.

**MVP scope**: Phases 1–2 + Phase 3 (US1), with Phase 4 (US2) run alongside as the safety net — together they deliver "sbt 2.x works, sbt 1.x unbroken." US3 and Polish make it durable and releasable.
