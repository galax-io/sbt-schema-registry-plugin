# Phase 0 Research: Cross-build for sbt 2.x

**Feature**: [spec.md](spec.md) · **Date**: 2026-06-22

All decisions below were grounded in the two authoritative sbt 2.x migration docs
([migrating-from-sbt-1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html),
[sbt-2.0-change-summary](https://www.scala-sbt.org/2.x/docs/en/changes/sbt-2.0-change-summary.html))
and verified against Maven Central artifact metadata. Each central claim survived an adversarial verification pass.

---

## D1 — Cross-build mechanism

**Decision**: sbt 1.x-side `crossScalaVersions` + a `pluginCrossBuild / sbtVersion` mapping keyed on `scalaBinaryVersion` (2.12 → sbt 1.x, Scala 3 → sbt 2.x). Build-side sbt stays 1.12.12.

**Rationale**: Requires sbt ≥ 1.10.2; the project is on 1.12.12, so it's available. Lighter than `projectMatrix` for a single-plugin repo and does **not** force relocating `src/` into a `plugin/` subdirectory (which `projectMatrix`'s automatic cross-build needs to keep the synthetic root from also picking up sources). The migration doc states verbatim: *"if you cross build an sbt plugin with Scala 3.x and 2.12.x, it will automatically cross build against sbt 1.x and sbt 2.x."*

**Alternatives considered**: `projectMatrix` with `.jvmPlatform(scalaVersions = Seq("3.8.4","2.12.21"))` — rejected: forces `plugin/` subdir relocation and is heavier than needed here. Real-world precedent: sbt-assembly 2.3.x cross-builds via this exact mechanism; 60+ plugins already ported.

## D2 — Scala 3 version for the sbt-2 axis

**Decision**: Pin an exact Scala **3.8.x** (currently **3.8.4**) — the version sbt 2.x mandates.

**Rationale**: The sbt 2.0 change summary: *"build.sbt DSL … is based on Scala 3.x (currently 3.8.4)."* `<latest>` on Maven Central is a snapshot — never resolve it.

**Alternatives considered**: Scala 3.3 LTS — **rejected, factually wrong**: 3.3 LTS was only an early proposal (sbt/sbt #7194), never the version sbt 2.x targets.

## D3 — sbt 2.x target & publish timing  *(spec clarification Q1=A)*

**Decision**: Target the latest sbt 2.x **GA** — sbt 2.0.0 — and publish `_sbt2_3` now. Pin the exact GA version (`sbt2 = 2.0.0`, `scala3 = 3.8.4`). sbt 2.0.0 reached final GA, so the earlier "a published RC is acceptable" hedge no longer applies; target GA.

**Rationale**: sbt-assembly already publishes `_sbt2_3` against RCs; early adopters get a usable artifact. Pinning exactly keeps builds reproducible.

**Alternatives considered**: Hold publish until sbt 2.0.0 final GA — rejected by clarification.

## D4 — Source-directory layout

**Decision**: Keep ~100% of code in shared `src/main/scala/`. Prefer cross-compatible **rewrites** (D5, D6) over version splits. Add `src/main/scala-2.12/` and `src/main/scala-3/` **only** as a thin `PluginCompat` seam for genuinely sbt-2-only API calls (realistically just `Def.uncached`, see D7). The `it` module and scripted fixtures need no source split.

**Rationale**: sbt auto-appends `scala-<scalaBinaryVersion>` to the base `scala` dir per cross step, so the seam is selected automatically. The plugin's touched sbt API surface (`AutoPlugin`, `autoImport`, `taskKey`/`settingKey`, `streams.value`, slash syntax, `%` DSL) is unchanged by sbt 2.x — it uses none of the keys whose types changed (`URL→URI`, `licenses→Seq[License]`, `Classpath→HashedVirtualFileRef`), no `IntegrationTest`, no `useCoursier`. So a shim is likely unnecessary beyond task-caching.

**Alternatives considered**: Split all six JavaConverters files into per-version dirs — rejected (dup, noise) in favor of the compat-lib rewrite (D5).

## D5 — `scala.collection.JavaConverters` removal  *(blocker, 6 files)*

**Decision**: Replace `import scala.collection.JavaConverters._` with `import scala.jdk.CollectionConverters._` in all 6 files (`VersionManifest`, `SubjectExplorer`, `Downloader`, `SubjectResolver`, `CompatibilityChecker` in main; `ReferenceResolutionIntegrationSpec` in `it`), and add `org.scala-lang.modules %% scala-collection-compat` so `scala.jdk.CollectionConverters` resolves on the **2.12** axis too.

**Rationale**: `scala.collection.JavaConverters` is deprecated in Scala 3; under `-Xfatal-warnings` that's a hard compile error. `scala.jdk.CollectionConverters` is the canonical replacement but only ships in 2.13+/3 — `scala-collection-compat` backports it to 2.12, making the single import cross-compile. New dependency: standard, tiny, ubiquitous — but per the project's "ask first: new deps" boundary, confirm with the owner before adding.

**Alternatives considered**: Per-version source dirs holding the old import on 2.12 and the new one on 3 — rejected (splits 6 files vs adding one well-known compat module).

## D6 — `Either` `.right.get`/`.left.get` projections  *(blocker, 56 test sites)*

**Decision**: Replace all 56 `.right.get`/`.left.get` projection sites in tests with scalatest `EitherValues` (`.value` / `.left.value`).

**Rationale**: Scala 3's right-biased `Either` plus `-Xfatal-warnings` turns the deprecated projections into errors. `EitherValues` is version-agnostic, removes projection use entirely, and reads better. Main code uses `.left.map(...)` (LeftProjection, retained) — no change needed there.

**Alternatives considered**: `either.toOption.get` / `either.swap.toOption.get` — rejected (noisier; loses scalatest's clear failure messages).

## D7 — sbt 2.x task-result caching  *(behavioral blocker, 4 tasks)*

**Decision**: Route the four side-effecting task bodies (`schemaRegistryDownload`, `schemaRegistryRegister`, `schemaRegistryTestCompatibility`, `schemaRegistryListSubjects`) through a `PluginCompat.uncached { … }` seam — `identity` on the sbt-1/2.12 axis, `Def.uncached(…)` on the sbt-2/Scala-3 axis. Verify with a double-run check (V3 in quickstart).

**Rationale**: sbt 2.x caches all tasks by default and *silently skips side effects on a cache hit* — exactly this plugin's danger case (network I/O, file writes, registry pushes, returning `Unit`). `Def.uncached` is sbt-2-only, so it lives behind the `PluginCompat` seam (the one place D4's split is actually used). Keeps a single shared task body.

**Alternatives considered**: Duplicate the four task defs into per-version dirs — rejected (dup). Making tasks return non-`Unit` cache keys — rejected (changes public behavior).

## D8 — `util-logging` version coupling  *(the one hard resolution blocker)*

**Decision**: In the `it` module, decouple `util-logging`'s version from the plugin's `sbtV` literal. Introduce a separate value selected by `scalaBinaryVersion`: sbt-1 axis → the 1.12.x line; sbt-2 axis → the sbt-2 line (e.g. `2.0.0`). The plugin's `pluginCrossBuild / sbtVersion` mapping stays independent.

**Rationale**: `build.sbt:49` pins `org.scala-sbt %% util-logging % sbtV` with `sbtV = "1.12.12"`. There is **no `util-logging_3` at 1.12.x** — `util-logging_3` exists only at `2.0.0`. On the Scala 3 axis this requests a nonexistent artifact → hard unresolved-dependency error. The literal `sbtV` currently drives *both* the plugin's cross-build target and util-logging's version, so a naive bump moves the plugin's sbt target unintentionally — they must be decoupled.

**Alternatives considered**: Drop `util-logging` from `it` — rejected (used by integration tests). Pin a single version — impossible (no version satisfies both axes).

## D9 — mockito-scala on Scala 3  *(manageable caveat, not a blocker)*

**Decision**: Keep `mockito-scala-scalatest 2.2.1` (its `_3` artifact resolves). First attempt: compile the 8 mock specs on Scala 3 unchanged. If the `mock[T]` macro fails (it requires inline call chains on Scala 3), refactor those call sites to be inline, or fall back to mockito-core directly for the affected specs. Budget this as a contingency, not a baseline.

**Rationale**: Verified — `mockito-scala-scalatest_3 2.2.1` is published and cross-resolves cleanly; it is the sole `_3` version (no older fallback). The only Scala-3 concern is the inline-macro requirement. Affected specs (8): `RegistrarSpec`, `SubjectExplorerSpec`, `RetryPolicySpec`, `DownloadOrchestratorSpec`, `DownloaderSpec`, `CompatibilityCheckerSpec`, `SubjectResolverSpec`, `ParallelDownloaderSpec`. Constitution III (no HTTP mocking) is respected — these mock pure-logic collaborators, not Schema Registry HTTP.

**Alternatives considered**: Swap the whole suite to mockito-core up front — rejected (premature; 2.2.1 may compile as-is).

## D10 — Scripted per-fixture axis gating  *(spec clarification Q2=A)*

**Decision**: `pluginCrossBuild` declares both sbt axes. Fixtures with **no** sbt-1-only external plugin run scripted on both axes; fixtures pulling sbt-1-only external plugins (e.g. sbt-avrohugger 2.17.0 in `download-success/`) stay sbt-1-gated until upstream ships sbt-2 builds. Verify upstream sbt-2 availability before requiring sbt-2 scripted on those. The sbt-2 release is never blocked by a third-party plugin.

**Rationale**: 28 `plugins.sbt` fixtures already use migration-safe `addSbtPlugin(... % v)` + slash syntax. The only blockers are *external* plugins the repo doesn't control. Gating keeps the plugin's own e2e coverage on sbt-2 (via no-external fixtures) without hostage to upstream timelines.

**Alternatives considered**: Drop scripted from the sbt-2 gate entirely (spec Q2 option C) — rejected by clarification. Hard-require full scripted on sbt-2 (option B) — rejected (blocks on sbt-avrohugger).

## D11 — CI matrix

**Decision**: GitHub Actions matrix over the Scala axis (2.12, 3.8.x). Each leg runs `compile`, `test`, and `it/test`; scripted runs per D10 gating. CI fails if either axis regresses (FR-008, SC-005). Keep the existing `scalafmtCheckAll scalafmtSbtCheck` gate.

**Rationale**: Direct realization of FR-008/SC-005. Matrix legs map cleanly to `++ <scalaVersion>` cross steps.

**Alternatives considered**: Single leg running `+test` (cross-aggregate) — workable but loses per-axis failure clarity and parallelism; matrix preferred.

## D12 — Release / dual-artifact publish  *(spec clarification Q3=A)*

**Decision**: A single `v*` tag cross-publishes both `_2.12_1.0` and `_sbt2_3` under one version number, via the existing tag-triggered `sbt-ci-release` flow run in its cross form (`+publishSigned` aggregate). Update `release.yml` to cross-publish.

**Rationale**: sbt-ci-release honors `crossScalaVersions`, so one tag emits all cross artifacts. Preserves the one-tag-per-version model and avoids divergent version numbers across axes (Sonatype rejects duplicate versions permanently — Constitution IV). `release.yml` changes touch the "ask first: release workflow changes" boundary → require owner approval.

**Alternatives considered**: Separate sbt-2 publish job (Q3 B) / manual publish (Q3 C) — rejected by clarification.

## D13 — `-Xfatal-warnings` during migration

**Decision**: Released builds keep `-Xfatal-warnings` on **both** axes (Constitution V, FR-009). During migration only, relax locally via `-Wconf` source-version scoping to iterate; remove the relaxation before merge. CI gate stays strict.

**Rationale**: Scala 3 emits a different/larger warning set than 2.12, so the strict flag fails the build before you can iterate. The relaxation is a transient dev scaffold, not the end state — Constitution V's "no warnings tolerated" holds for merged/released code.

**Alternatives considered**: Permanently relax `-Werror` on the Scala 3 axis — rejected (violates Constitution V).

## D14 — Backward-compat verification for the 2.12 artifact

**Decision**: Verify the sbt-1 artifact is unaffected via three checks, no new tooling: (a) the `_2.12_1.0` coordinates and version scheme are unchanged; (b) the full scripted suite on sbt-1 still passes; (c) a public-key-surface review confirms no key renamed/retyped/removed (SC-004). Defer MiMa.

**Rationale**: This is a plugin (sbt-key surface), not a typical binary-compat library; scripted e2e + key-surface diff already cover the compatibility contract. MiMa adds setup cost for little marginal signal here.

**Alternatives considered**: Add MiMa binary-compat checks — deferred (can add later if the public Scala API grows).

---

## Resolved unknowns

No `NEEDS CLARIFICATION` markers remain. The three spec-deferred items are resolved: source-dir strategy (D4), mockito handling (D9), backward-compat verification (D14). Dependency Scala-3 availability is confirmed: scalatest 3.2.20 ✓, mockito-scala-scalatest 2.2.1 ✓ (sole `_3`), util-logging (axis-tracked) ✓; confluent + testcontainers are Java-only ✓.
