<!--
Sync Impact Report
===================
Version change: 0.0.0 → 1.0.0 (initial ratification)
Modified principles: N/A (initial creation)
Added sections:
  - Core Principles (5 principles)
  - Technical Constraints
  - Development Workflow
  - Governance
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md — ✅ compatible (Constitution Check section aligns)
  - .specify/templates/spec-template.md — ✅ compatible (requirements/scenarios structure aligns)
  - .specify/templates/tasks-template.md — ✅ compatible (phase structure supports test-first + parallel)
Follow-up TODOs: none
-->

# sbt-schema-registry-plugin Constitution

## Core Principles

### I. Backward Compatibility

All published sbt keys, task names, and public API surface MUST remain
backward-compatible. Breaking changes require a major version bump,
migration documentation, and explicit user approval before implementation.

Rationale: This is a published sbt plugin consumed by external users.
Unexpected breakage erodes trust and blocks downstream builds.

### II. Single Responsibility Architecture

Each class MUST own exactly one responsibility. `SchemaDownloaderPlugin`
wires sbt tasks, `Downloader` fetches schemas, `RegistrySubject` models
subjects as a sealed ADT, `DownloadError` models failures as `Either`,
`SchemaRegistryAuth` handles credentials. Dependencies MUST be injected,
never constructed internally.

Rationale: Clear ownership makes the codebase navigable and testable.
Injected dependencies enable unit testing without real infrastructure.

### III. Test-First Development

Tests MUST be written before implementation. Unit tests (`sbt test`) run
without external services. Integration tests (`sbt it/test`) use
Testcontainers with real Schema Registry + Kafka. Plugin end-to-end tests
(`sbt scripted`) validate sbt task behavior. NEVER mock Schema Registry
HTTP where a real integration path exists.

Rationale: Real-service tests caught regressions that mocks would miss.
The three-tier test strategy covers unit logic, protocol correctness,
and plugin wiring independently.

### IV. Trunk-Based Release Discipline

`main` is trunk. Release branches (`release/X.Y.0`) are cut from `main`
for stabilization. Tags (`vX.Y.Z`) on `release/*` or `main` trigger
automated publishing via `sbt-ci-release`. Version numbers MUST NOT be
reused. Release tags MUST NOT be deleted after deployment starts. Patch
releases cherry-pick fixes from `main` onto release branches.

Rationale: Tag-driven releases with CI automation eliminate manual
publishing errors. Sonatype Central permanently rejects duplicate
versions, so reuse causes irrecoverable failures.

### V. Format and Verify Before Commit

`scalafmtAll` and `scalafmtSbt` MUST pass before every commit. CI runs
`scalafmtCheckAll scalafmtSbtCheck compile test` on every PR. Code MUST
compile with `-Xfatal-warnings` — no warnings tolerated.

Rationale: Consistent formatting eliminates review noise. Fatal warnings
prevent gradual quality erosion.

## Technical Constraints

- **Language**: Scala 2.12 (sbt's Scala version — not project Scala)
- **Build tool**: sbt 1.12.x with autoplugin API
- **Runtime dependency**: Confluent `kafka-schema-registry-client`
- **Test stack**: ScalaTest + mockito-scala (unit), Testcontainers (integration), sbt scripted (e2e)
- **Java**: 17+
- **License**: Apache 2.0
- **Publishing**: Maven Central via sbt-ci-release + Sonatype
- **New dependencies or major upgrades**: MUST be approved before adding

## Development Workflow

1. Branch from `main` for all work
2. Format: `sbt scalafmtAll scalafmtSbt`
3. Verify: `sbt compile test` (unit), `sbt it/test` (integration, requires Docker), `sbt scripted` (e2e)
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

**Version**: 1.0.0 | **Ratified**: 2026-06-14 | **Last Amended**: 2026-06-14
