# Feature Specification: Cross-build for sbt 2.x (add a Scala 3 axis)

**Feature Branch**: `009-sbt2-cross-build`

**Created**: 2026-06-22

**Status**: Draft

**Input**: User request (RU): "настроить кроссбилд получится ли? нужно ли на 3 скалу переходить?" — Can we set up a cross-build, and do we need to migrate to Scala 3? Plus two sbt 2.x migration docs: [migrating-from-sbt-1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html), [sbt-2.0-change-summary](https://www.scala-sbt.org/2.x/docs/en/changes/sbt-2.0-change-summary.html).

## Feasibility Answer (Summary)

Captured here because the request is a feasibility question, not only a feature ask:

- **Cross-build is feasible.** sbt 2.x officially supports it. The migration doc states verbatim: *"if you cross build an sbt plugin with Scala 3.x and 2.12.x, it will automatically cross build against sbt 1.x and sbt 2.x."* This plugin's build is already migration-clean (slash syntax throughout, no legacy `<<=`/`in(Config)` syntax, none of the sbt keys whose types changed).
- **Scala 3 is required, but as an ADDITIVE second axis — not a migration away from 2.12.** sbt 2.x runs all plugins on Scala 3 (currently **3.8.4**, not 3.3 LTS). The sbt 1.x consumer base keeps its existing Scala 2.12 artifact; we add a Scala 3 artifact for sbt 2.x. The same source tree serves both. "Additive" is at the build-configuration level — the Scala 3 axis still needs a small set of source fixes (see Risks).

## Clarifications

### Session 2026-06-22

- Q: Scope — true dual cross-build (keep sbt 1.x **and** add sbt 2.x) or sbt 2.x only? → A: Dual cross-build. sbt 2.x is still pre/early-GA and dropping 2.12 would strand the entire current user base. Both artifacts published from one source tree.
- Q: Which Scala 3 version for the sbt 2.x axis? → A: The version sbt 2.x mandates (Scala 3.8.x, currently 3.8.4) — pinned to an exact release, never read from a `<latest>`/snapshot tag.
- Q: When to publish the `_sbt2_3` artifact — ship now against current sbt 2.x, or hold for GA? → A: Ship now against the latest sbt 2.x **GA** (sbt 2.0.0, the current release), pinned exactly. (sbt 2.0.0 reached final GA, so the original "ship even against an RC" hedge is moot — target GA.)
- Q: Release gate for scripted e2e on the sbt-2 axis, given external fixture plugins may lack sbt-2 builds? → A: Gate scripted per-fixture by axis. Unit + integration must be green on both axes; scripted on sbt-2 is required only for fixtures with no sbt-1-only external plugins. Fixtures depending on sbt-1-only external plugins (e.g. sbt-avrohugger) stay sbt-1-gated until upstream ships sbt-2 builds — the sbt-2 release is never blocked by a third-party plugin's timeline.
- Q: How are both artifacts published — one tag or a separate step? → A: One tag cross-publishes both. Extend the existing `v*`-tag-triggered sbt-ci-release to cross-publish so a single version number emits both `_2.12_1.0` and `_sbt2_3`. Preserves the one-tag-per-version model and avoids divergent version numbers across axes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - sbt 2.x project can apply the plugin and run every task (Priority: P1)

A developer whose build has moved to sbt 2.x adds this plugin to their build. The plugin resolves (a Scala 3 artifact exists), applies cleanly, and all published tasks — download, register, test-compatibility, and list-subjects — run and produce the same observable results they do on sbt 1.x, including the actual side effects (files written to disk, schemas pushed to the registry).

**Why this priority**: This is the entire point of the request. sbt 2.x users cannot use the plugin at all today, because no Scala 3 artifact is published. Without it there is no feature.

**Independent Test**: From a scratch project pinned to sbt 2.x, add the plugin, configure a registry URL and one subject, run `schemaRegistryDownload`, and verify the schema file is written to the target folder (not silently skipped by task caching).

**Acceptance Scenarios**:

1. **Given** an sbt 2.x build that adds the plugin, **When** the build loads, **Then** the plugin's Scala 3 artifact resolves and the plugin applies with all setting keys available at their documented defaults.
2. **Given** an sbt 2.x build with `schemaRegistrySubjects` configured, **When** the user runs `schemaRegistryDownload` twice in a row, **Then** both runs perform the real download work (the second run is not silently skipped by sbt 2.x task caching) and the target folder contains the expected schema files.
3. **Given** an sbt 2.x build with `schemaRegistryRegistrations` configured, **When** the user runs `schemaRegistryRegister` and `schemaRegistryTestCompatibility`, **Then** schemas are pushed and compatibility is reported exactly as on sbt 1.x.
4. **Given** an sbt 2.x build, **When** the user runs `schemaRegistryListSubjects`, **Then** the registry's subjects are listed with version ranges and compatibility, identical in behavior to the sbt 1.x output.

---

### User Story 2 - Existing sbt 1.x users are completely unaffected (Priority: P1)

A developer already using the plugin on sbt 1.x upgrades to the new cross-built release. Their build continues to resolve the same Scala 2.12 artifact, every public setting/task key keeps its name, type, and semantics, and nothing in their build needs to change.

**Why this priority**: Backward compatibility is a hard constraint for a published plugin (per the project's compatibility rules). Adding the Scala 3 axis must not regress or relocate the existing 2.12 / sbt 1.x artifact.

**Independent Test**: Take an existing sbt 1.x consumer build, bump only the plugin version to the cross-built release, and verify it resolves the `_2.12_1.0` artifact and runs all tasks unchanged.

**Acceptance Scenarios**:

1. **Given** an sbt 1.x build pinned to the previous plugin version, **When** the user bumps to the cross-built version, **Then** the build resolves the Scala 2.12 / sbt 1.x artifact at the expected coordinates and loads without changes.
2. **Given** the cross-built release, **When** the published artifact list is inspected, **Then** both the sbt 1.x (`_2.12_1.0`) and sbt 2.x (`_sbt2_3`) coordinates are present on the release repository.
3. **Given** the public key surface, **When** comparing before and after, **Then** no public setting or task key was renamed, retyped, or removed.

---

### User Story 3 - Maintainer builds, tests, and publishes both axes from one source tree (Priority: P2)

A maintainer can compile, run the unit, integration, and scripted test suites, and publish for **both** sbt axes using a single command flow, without forking the source. CI verifies both axes so a regression on either is caught before release.

**Why this priority**: Sustains the feature over time. A cross-build that only one person can reproduce locally, or that CI does not guard, will silently rot back to single-axis.

**Independent Test**: Run the cross aware build/test commands locally and confirm both the 2.12/sbt-1.x and 3.x/sbt-2.x variants compile and pass their suites from the same checkout.

**Acceptance Scenarios**:

1. **Given** the cross-build configuration, **When** the maintainer runs the cross-aware compile, **Then** the plugin compiles for both Scala 2.12 (sbt 1.x) and Scala 3 (sbt 2.x) from the same sources.
2. **Given** the cross-build configuration, **When** the maintainer runs the test suites, **Then** unit and integration tests pass on both axes and scripted tests run under both sbt versions (fixtures that pull external sbt-1-only plugins are gated to the axis where those plugins exist).
3. **Given** CI, **When** a change is pushed, **Then** CI compiles and tests both axes and fails the build if either axis breaks.

---

### Edge Cases

- **Task caching silently skipping side effects (sbt 2.x):** sbt 2.x caches all tasks by default. The plugin's tasks are side-effecting and return `Unit`, the exact case the docs warn about — a cached run must not skip the real download/register/list work.
- **The `it` integration module is sbt-version-coupled:** it depends on an sbt-internal Scala library (`util-logging`) pinned to the current sbt version literal; that exact version has no Scala 3 artifact, so it must track the sbt-2.x version on the Scala 3 axis. It cannot pin an axis independently of the plugin.
- **`-Xfatal-warnings` promotes Scala-3 warnings to hard errors:** the existing strict-warnings flag turns Scala-3-only deprecations (e.g. legacy Java-collection converters, `Either` projections) into compile failures.
- **External scripted-fixture plugins may lack an sbt 2.x build:** some scripted fixtures pull external sbt plugins (e.g. sbt-avrohugger) that may not yet publish for sbt 2.x; the scripted suite — not the plugin itself — becomes the scheduling constraint for those fixtures.
- **Test mocking on Scala 3:** the mock library resolves for Scala 3, but its Scala 3 `mock[T]` macro has stricter (inline) requirements; some specs may need to be reshaped or fall back to a different mocking approach.
- **Snapshot `<latest>` tags:** several upstream artifacts expose a snapshot as `<latest>`; the build must pin exact stable/GA versions, never resolve `<latest>`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The plugin MUST publish an artifact usable by sbt 2.x builds (a Scala 3 artifact) in addition to the existing sbt 1.x (Scala 2.12) artifact.
- **FR-002**: The plugin MUST continue to publish the existing sbt 1.x / Scala 2.12 artifact at its current coordinates, with no change to its consumption.
- **FR-003**: All public setting and task keys MUST keep their names, types, and observable semantics on both axes (no renames, retypes, or removals).
- **FR-004**: On sbt 2.x, every task (`schemaRegistryDownload`, `schemaRegistryRegister`, `schemaRegistryTestCompatibility`, `schemaRegistryListSubjects`) MUST perform its real side effects on every invocation and MUST NOT be silently skipped by sbt 2.x's default task-result caching.
- **FR-005**: Both axes MUST be buildable, testable, and publishable from a single shared source tree (no source fork).
- **FR-006**: The integration (`it`) module MUST build and run its tests on whichever axis is exercised, with its sbt-internal dependency versions decoupled from the plugin's cross-build target so a version bump on one does not unintentionally move the other.
- **FR-007**: The unit and integration test suites MUST pass on both the Scala 2.12 and Scala 3 axes. The scripted (sbt-plugin e2e) suite MUST run on the sbt-2 axis for every fixture that has no sbt-1-only external-plugin dependency; fixtures that depend on sbt-1-only external plugins (e.g. sbt-avrohugger) MUST stay gated to the sbt-1 axis until those plugins publish sbt-2 builds. The sbt-2 release MUST NOT be blocked by an external plugin's lack of an sbt-2 build.
- **FR-008**: CI MUST compile and test both axes and fail if either axis regresses.
- **FR-009**: Strict-warning enforcement (`-Xfatal-warnings`) MUST be in effect on both axes for released builds; it MAY be temporarily relaxed or warning-scoped during migration, then re-enabled once each axis is warning-clean.
- **FR-010**: The Scala 3 axis MUST pin exact released versions of Scala, sbt 2.x, and all cross-published dependencies (target the latest sbt 2.x GA — currently sbt 2.0.0; never resolve a `<latest>`/snapshot tag).
- **FR-011**: A single `v*` version tag MUST cross-publish both artifacts (`_2.12_1.0` and `_sbt2_3`) under one version number via the existing tag-triggered release flow — no separate sbt-2 publish step and no per-axis version divergence. The release process and documentation (AGENTS.md stack/commands/release) MUST be updated to describe the dual-axis build, test, and single-tag cross-publish flow.

### Key Entities *(build/release artifacts — no application data)*

- **sbt 1.x artifact**: the existing plugin build, Scala 2.12, sbt-binary 1.0; the artifact today's users consume. Must remain unchanged.
- **sbt 2.x artifact**: the new plugin build, Scala 3, sbt-binary 2; the artifact sbt-2.x users consume. Published alongside the sbt 1.x artifact.
- **Cross axis**: the (Scala version → sbt version) mapping that selects which artifact a build resolves (2.12 → sbt 1.x, Scala 3 → sbt 2.x).
- **`it` module**: integration-test project coupled to the plugin and to an sbt-internal library whose version must track the active sbt axis.
- **Scripted fixtures**: the sbt-plugin e2e test projects; some pull external sbt plugins whose availability differs per sbt axis.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A from-scratch sbt 2.x project can add the plugin and successfully run all four tasks, producing the same observable results (files written, schemas registered, subjects listed) as the same project on sbt 1.x.
- **SC-002**: An existing sbt 1.x consumer build upgrades to the cross-built release by changing only the plugin version, with zero other build changes required, and all tasks behave identically.
- **SC-003**: A single version tag produces both the sbt 1.x and sbt 2.x artifacts under one version number, both resolvable from the release repository.
- **SC-004**: 100% of the existing public setting/task keys are unchanged in name, type, and semantics after the change.
- **SC-005**: The test matrix passes and CI enforces it on every change: unit + integration green on both axes; scripted green on sbt-1 for all fixtures and on sbt-2 for every fixture without an sbt-1-only external-plugin dependency (external-plugin fixtures explicitly axis-gated, not silently skipped).
- **SC-006**: On sbt 2.x, running a side-effecting task twice performs the work both times (no silent cache skip), verified by a scripted or integration check.

## Assumptions

- **Dual cross-build, not sbt-2-only.** Scope keeps the sbt 1.x / Scala 2.12 axis and adds the sbt 2.x / Scala 3 axis. Dropping 2.12 is explicitly out of scope; it would strand current users while sbt 2.x is still stabilizing.
- **Mechanism (implementation detail, recorded for planning):** sbt 1.x-side `crossScalaVersions` + a `pluginCrossBuild / sbtVersion` mapping keyed on the Scala binary version (sbt ≥ 1.10.2 required; the project is on 1.12.12). `projectMatrix` is an alternative if a shared-source API shim is needed. Source stays shared; version-specific source dirs (`scala-2.12` / `scala-3`) are added only if an API difference forces it.
- **Dependency availability is confirmed for the Scala 3 axis:** scalatest (3.2.20), mockito-scala-scalatest (2.2.1, the sole `_3` release), and sbt's `util-logging` (sbt-2.x line) all have Scala 3 artifacts. Confluent and Testcontainers deps are plain Java and unaffected.
- **Source-level fixes required by the Scala 3 axis (mechanical):** replace legacy `scala.collection.JavaConverters` with `scala.jdk.CollectionConverters`; replace `Either` `.right.get`/`.left.get` projections in tests with `EitherValues`; wrap side-effecting task bodies so sbt 2.x caching cannot skip them; possibly reshape mock-based specs for the Scala 3 macro.
- **sbt 2.x target version:** sbt 2.0.0 GA (the latest sbt 2.x release) and the Scala 3.8.x it ships (3.8.4), both pinned exactly.
- **Staged rollout is acceptable:** forward-compatible source fixes can land first on the current 2.12 build before the sbt-2 axis is switched on; external scripted-fixture plugin availability (e.g. sbt-avrohugger for sbt 2.x) is verified before that axis is required to be green.

## Out of Scope

- Dropping or deprecating the sbt 1.x / Scala 2.12 axis.
- Migrating consumer projects to sbt 2.x (this is about the plugin's publishable artifacts, not users' builds).
- Adding new plugin features or changing task behavior beyond what cross-build correctness requires.
- Upgrading unrelated dependencies except where a Scala 3 artifact requires a version change.

## Dependencies

- sbt ≥ 1.10.2 on the build side for the `pluginCrossBuild` cross mapping (satisfied: 1.12.12).
- Availability of Scala 3 artifacts for every `%%` dependency (confirmed) and of sbt-2.x builds for external scripted-fixture plugins (must be verified before requiring the sbt-2 scripted axis to be green).
- sbt 2.0.0 GA to target and a pinned Scala 3.8.4 release.
