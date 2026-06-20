# Quickstart / Validation: List Subjects Task

**Feature**: 008-list-subjects-task | **Date**: 2026-06-20

How to prove the feature works end to end. References [contracts/plugin-api.md](contracts/plugin-api.md) and [data-model.md](data-model.md) for shapes; no implementation code here.

## Prerequisites

- Java 17+, sbt 1.12.x (`sbt` Scala 2.12.21).
- Docker running (for `it/test` and `scripted` Testcontainers). Local runs may need a `DOCKER_HOST` + Docker socket override for Testcontainers to reach the daemon.

## Build / verify commands

```bash
sbt scalafmtAll scalafmtSbt                          # format (run before commit)
sbt scalafmtCheckAll scalafmtSbtCheck compile test   # CI gate (unit)
sbt it/test                                          # integration (Docker)
sbt scripted                                         # plugin e2e (Docker)
```

## Validation scenarios

### V1 — Core listing (unit, `SubjectExplorerSpec`)
- **Setup**: mock `SchemaRegistryClient`; stub `getAllSubjects` → `["user-value","order-value","payment-value"]`, `getAllVersions` per subject, `getCompatibility` (one `BACKWARD`, one throwing).
- **Run**: `SubjectExplorer.listAll(client, None)`.
- **Expect**: `Right(SubjectListing(...))`, subjects sorted by name; each `versionRange` correct (single vs `1..5`); the throwing-compat subject ⇒ `compatibility = None`. (Covers US1, US3, FR-003/004/005/012.)

### V2 — Filter (unit, `SubjectListingSpec` + `SubjectExplorerSpec`)
- **Run**: `listing.matching("ORDER")` and `SubjectExplorer.listAll(client, Some("order"))`.
- **Expect**: only `order-value`; case-insensitive; empty filter ⇒ all. (Covers US2, FR-007/008.)

### V3 — Fail-fast on version error (unit)
- **Setup**: `getAllVersions("order-value")` throws.
- **Run**: `SubjectExplorer.listAll(client, None)`.
- **Expect**: `Left(DownloadError.SubjectVersionsFetchFailed("order-value", _))`; no partial listing. (Covers fail-fast clarification, FR-010.)

### V4 — Subject-list failure (unit)
- **Setup**: `getAllSubjects` throws.
- **Expect**: `Left(DownloadError.SubjectListFailed(_))`. (Covers edge "registry unreachable", FR-011.)

### V5 — Real registry (integration, `it/`)
- **Setup**: Testcontainers Schema Registry; register 2+ subjects, one with multiple versions and a subject-level compatibility override.
- **Run**: `SubjectExplorer.listAll(realClient, None)` and with a filter.
- **Expect**: real version ranges and the override level surface correctly; filter narrows results. No HTTP mocking (Constitution III). (Covers US1/US2/US3 against real protocol.)

### V6 — sbt wiring (scripted, `src/sbt-test/schema-registry/list-subjects/`)
- **Setup**: mirror `download-wildcard`; `docker.sbt` fixture + `RegistryFixture`; `schemaRegistryUrl := RegistryFixture.url`.
- **`test` script**:
  - `> schemaRegistryListSubjects` — must **succeed** (all subjects).
  - `> set schemaRegistrySubjectFilter := Some("Order")` then `> schemaRegistryListSubjects` — must succeed (filtered).
  - Negative project (bad URL): `-> schemaRegistryListSubjects` — must **fail**.
- **Note**: scripted asserts task success/failure (no stdout-grep statement exists); listed-content correctness is proven by V5. (Covers FR-001/002/013.)

## Done / acceptance mapping

| Spec item | Validated by |
|---|---|
| US1 list all + count | V1, V5, V6 |
| US2 filter | V2, V5, V6 |
| US3 versions + compatibility | V1, V5 |
| Fail-fast / unreachable | V3, V4, V6(neg) |
| Backward compatibility (FR-013) | existing `download-*` scripted tests stay green |
| Format + `-Xfatal-warnings` (Constitution V) | `scalafmtCheckAll … compile` gate |
