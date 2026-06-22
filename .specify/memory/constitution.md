<!--
Sync Impact Report
===================
Version change: 1.0.0 → 1.1.0 (MINOR — materially expanded guidance; no principle removed or redefined)
Modified principles:
  - I. Backward Compatibility — added the additive-build-axis invariant (cross-build must not disturb existing artifacts)
  - V. Format and Verify Before Commit — made fatal-warnings axis-aware (-Xfatal-warnings on 2.12, -Werror on 3) and
       sanctioned narrowly-scoped -Wconf suppressions for cross-version-unavoidable deprecations
Modified sections:
  - Technical Constraints — now dual-axis (Scala 2.12 / sbt 1.x and Scala 3.x / sbt 2.x); records dual published artifacts
  - Development Workflow — verify commands cross both axes
Added sections: N/A
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md — ✅ compatible (Constitution Check section is generic; no mandatory section change)
  - .specify/templates/spec-template.md — ✅ compatible (requirements/scenarios structure unaffected)
  - .specify/templates/tasks-template.md — ✅ compatible (phase structure still supports test-first + parallel)
  - AGENTS.md — ✅ already reflects the dual-axis build/test/release flow (updated in feature 009-sbt2-cross-build)
Follow-up TODOs: none
-->

# sbt-schema-registry-plugin Constitution

## Core Principles

### I. Backward Compatibility

All published sbt keys, task names, and public API surface MUST remain
backward-compatible. Breaking changes require a major version bump,
migration documentation, and explicit user approval before implementation.

Adding a new build or runtime target (e.g. a second sbt/Scala axis) MUST be
additive: every existing published artifact keeps its coordinates, keys,
types, and observable behavior unchanged. A new axis only adds a new artifact.

Rationale: This is a published sbt plugin consumed by external users.
Unexpected breakage erodes trust and blocks downstream builds.

### II. Single Responsibility Architecture

Each class MUST own exactly one responsibility. `SchemaDownloaderPlugin`
wires sbt tasks, `Downloader` fetches schemas, `RegistrySubject` models
subjects as a sealed ADT, `DownloadError` models failures as `Either`,
`SchemaRegistryAuth` handles credentials. Dependencies MUST be injected,
never constructed internally.

sbt-version-only API differences (e.g. `Def.uncached`) MUST be isolated
behind a single compatibility seam (`PluginCompat`, in version-specific
source directories), keeping the shared source tree free of axis branching.

Rationale: Clear ownership makes the codebase navigable and testable.
Injected dependencies enable unit testing without real infrastructure.

### III. Test-First Development

Tests MUST be written before implementation. Unit tests (`sbt test`) run
without external services. Integration tests (`sbt it/test`) use
Testcontainers with real Schema Registry + Kafka. Plugin end-to-end tests
(`sbt scripted`) validate sbt task behavior. NEVER mock Schema Registry
HTTP where a real integration path exists.

When the project is cross-built, the unit and integration suites MUST pass
on every supported axis, and the e2e (scripted) suite MUST run on each axis
for every fixture whose dependencies resolve there.

Rationale: Real-service tests caught regressions that mocks would miss.
The three-tier test strategy covers unit logic, protocol correctness,
and plugin wiring independently.

### IV. Trunk-Based Release Discipline

`main` is trunk. Release branches (`release/X.Y.0`) are cut from `main`
for stabilization. Tags (`vX.Y.Z`) on `release/*` or `main` trigger
automated publishing via `sbt-ci-release`. Version numbers MUST NOT be
reused. Release tags MUST NOT be deleted after deployment starts. Patch
releases cherry-pick fixes from `main` onto release branches.

A single version tag MUST cross-publish all axes under one version number
(no per-axis version divergence).

Rationale: Tag-driven releases with CI automation eliminate manual
publishing errors. Sonatype Central permanently rejects duplicate
versions, so reuse causes irrecoverable failures.

### V. Format and Verify Before Commit

`scalafmtAll` and `scalafmtSbt` MUST pass before every commit. CI runs
`scalafmtCheckAll scalafmtSbtCheck +compile +test` on every PR, across all
supported axes. Code MUST compile with fatal warnings enabled on every axis
(`-Xfatal-warnings` on Scala 2.12, `-Werror` on Scala 3) — no warnings
tolerated.

The only permitted warning suppressions are narrowly-scoped `-Wconf` rules
for deprecations that have no single source form accepted by all supported
Scala versions (e.g. `_` type wildcards, `: _*` vararg splices). Each such
rule MUST be message-scoped (not category- or file-wide) and carry a comment
explaining why a shared-source fix is impossible.

Rationale: Consistent formatting eliminates review noise. Fatal warnings
prevent gradual quality erosion; scoping the unavoidable cross-version
deprecations keeps the gate strict without forking source per axis.

## Technical Constraints

- **Language**: Cross-built from one source tree — Scala 2.12 (sbt 1.x axis)
  and Scala 3.x (sbt 2.x axis). These are sbt's Scala versions, not a
  consumer's project Scala.
- **Build tool**: sbt 1.x (1.12.x) and sbt 2.x (2.0.0), via `crossScalaVersions`
  + `pluginCrossBuild / sbtVersion` keyed on the Scala binary version. The
  autoplugin API is used on both axes.
- **Published artifacts**: `_2.12_1.0` (sbt 1.x) and `_sbt2_3` (sbt 2.x),
  cross-published from a single tag under one version.
- **Runtime dependency**: Confluent `kafka-schema-registry-client`
- **Test stack**: ScalaTest + mockito-scala (unit), Testcontainers (integration), sbt scripted (e2e)
- **Java**: 17+
- **License**: Apache 2.0
- **Publishing**: Maven Central via sbt-ci-release + Sonatype
- **New dependencies or major upgrades**: MUST be approved before adding
- **Axis versions** (`scala3`, `sbt2`) are bumped manually and pinned exactly
  (never `<latest>`/snapshot); `scala3` MUST match the Scala version the
  targeted sbt 2.x ships.

## Development Workflow

1. Branch from `main` for all work
2. Format: `sbt scalafmtAll scalafmtSbt`
3. Verify both axes: `sbt +compile +test` (unit), `sbt +it/test` (integration,
   requires Docker), and scripted e2e per axis — `sbt ++2.12.21 scripted` plus
   `sbt ++3.8.4 "scripted <fixtures>"` (the sbt-2 axis skips fixtures that pull
   sbt-1-only external plugins until those plugins publish sbt-2 builds)
4. Rebase-only merge strategy — no merge commits in PR branches
5. Never force-push or commit directly to `main`
6. Never commit broken code or do opportunistic refactors outside scope
7. Commit messages follow Conventional Commits (used by git-cliff for release notes)

## Governance

This constitution supersedes all other development practices for this
project. Amendments require:

1. Documentation of proposed change with rationale
2. Version bump following semantic versioning:
   - MAJOR: principle removal or backward-incompatible redefinition
   - MINOR: new principle or materially expanded guidance
   - PATCH: clarification, wording, or non-semantic refinement
3. Update of all dependent templates and guidance files
4. Migration plan if amendment changes existing workflows

All pull requests and reviews MUST verify compliance with these
principles. Complexity beyond single-responsibility MUST be justified
with a documented rationale.

Runtime development guidance lives in `AGENTS.md` at repository root.

**Version**: 1.1.0 | **Ratified**: 2026-06-14 | **Last Amended**: 2026-06-22
