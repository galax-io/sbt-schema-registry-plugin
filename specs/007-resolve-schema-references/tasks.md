---
description: "Task list for feature 007 — auto-download schema references"
---

# Tasks: Auto-Download Schema References

**Input**: Design documents from `specs/007-resolve-schema-references/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: INCLUDED — Constitution III (Test-First) mandates tests before implementation for this project.

**Organization**: Grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (maps to spec.md user stories)
- Every task lists an exact file path

## Path Conventions

Single-project sbt plugin. Production: `src/main/scala/org/galaxio/avro/`. Unit tests:
`src/test/scala/org/galaxio/avro/`. Integration: `it/src/test/scala/org/galaxio/avro/`.
Scripted e2e: `src/sbt-test/schema-registry/`.

---

## Phase 1: Setup

**Purpose**: Branch and baseline.

- [x] T001 Create feature branch `007-resolve-schema-references` off `main` and verify baseline is green: `sbt scalafmtCheckAll scalafmtSbtCheck compile test`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared value type every story depends on.

**⚠️ CRITICAL**: Must complete before any user story.

- [x] T002 Add `ResolvedSchema(subject: String, version: Int, references: List[SchemaReference])` case class in new file `src/main/scala/org/galaxio/avro/ReferenceResolver.scala` (reuses existing `SchemaReference`; see [data-model.md](data-model.md))

**Checkpoint**: `ResolvedSchema` compiles — story work can begin.

---

## Phase 3: User Story 1 - Transitive reference download (Priority: P1) 🎯 MVP

**Goal**: Downloading a root schema auto-downloads its referenced schemas transitively, at pinned versions, in one task run.

**Independent Test**: Register a root schema referencing a second schema; run `Compile / schemaRegistryDownload` for the root only; verify both files land on disk (quickstart scenario 2/3).

### Tests (write first)

- [x] T003 [P] [US1] Create `src/test/scala/org/galaxio/avro/ReferenceResolverSpec.scala` (`AnyFlatSpec with Matchers`, Map-based stub `fetch`) covering RR-1 (no refs → single entry), RR-2 (transitive `A→B→C`, BFS roots-first order), RR-8 (latest root also referenced at its resolved version → emitted once), **RR-3 (cycle `A↔B` terminates, each once, no SOE — guards MVP against hangs)**, and **RR-6 (fail-fast: first `Left` short-circuits)**. RR-3/RR-6 are pulled forward into US1 because cycle-safety and fail-fast are intrinsic to MVP correctness and must gate the T004 impl (Constitution III). See [contracts/reference-resolver.md](contracts/reference-resolver.md)

### Implementation

- [x] T004 [US1] Implement pure `ReferenceResolver.resolve(roots, fetch): Either[DownloadError, List[RegistrySubject]]` in `src/main/scala/org/galaxio/avro/ReferenceResolver.scala` — `@tailrec` BFS over immutable `Queue`, two-level dedup (enqueue key `(subject, Option[Int])`, visited key resolved `(subject, Int)`), roots-first output of `RegistrySubject.Pinned`, fail-fast on first `Left` (reuse `DownloadError.SchemaFetchFailed`). Make T003 pass
- [x] T005 [US1] Declare `schemaRegistryResolveReferences = settingKey[Boolean]("Auto-download schemas referenced by downloaded schemas (transitive)")` in `autoImport` and default `:= true` in `defaultSettings`, in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` (see [contracts/plugin-setting.md](contracts/plugin-setting.md))
- [x] T006 [US1] In `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` `Compile / schemaRegistryDownload`: build the `fetch` closure over the shared `client` (`getLatestSchemaMetadata` for `None` / `getByVersion(name, v, false)` for `Some`; `Option(meta.getReferences).map(_.asScala.toList).getOrElse(Nil)`; unbox `getVersion.intValue`), and insert the gated stage `val expanded = if (resolveReferences) ReferenceResolver.resolve(roots, fetch) else Right(roots)` **between** `SubjectResolver.resolve` and `IncrementalResolver.plan`; thread `expanded` into the existing incremental + parallel stages

### Validation tests

- [x] T007 [P] [US1] Create `it/src/test/scala/org/galaxio/avro/ReferenceResolutionIntegrationSpec.scala` (`AnyFlatSpec` + Testcontainers Kafka + Schema Registry): register base + dependent-with-`SchemaReference`, run resolve+download for the root, assert **both** `<base>-1.avsc` and `<dependent>-1.avsc` exist with correct content (PS-1); add a missing-reference case asserting `SchemaFetchFailed` naming the subject (PS-4); add a **composition** case proving FR-009 — referenced subject already current in the manifest is skipped by incremental (PS-5), and the expanded list downloads under parallelism > 1 (PS-6)
- [x] T008 [P] [US1] Create scripted test `src/sbt-test/schema-registry/resolve-references/` (`build.sbt`, `test`, `project/plugins.sbt`) following the `register-references` fixture: register base + dependent-with-reference, run `Compile / schemaRegistryDownload`, assert both files via `$ exists` / `$ must-mirror` (PS-1). Select the dependent root via `schemaRegistrySubjectPatterns` (a regex), not an exact subject, so the test also proves resolution composes with **wildcard expansion** (FR-009)

**Checkpoint**: MVP — transitive download works end-to-end (default on).

---

## Phase 4: User Story 2 - Opt-out of reference resolution (Priority: P2)

**Goal**: Setting `schemaRegistryResolveReferences := false` downloads only requested subjects; behavior identical to pre-feature.

**Independent Test**: With the setting off, download a subject that has references; verify only the requested file appears (quickstart scenario 4 / SC-004).

> The gate is implemented in T006. This story verifies the off-path and backward compatibility.

- [x] T009 [US2] Add opt-out assertion to `src/sbt-test/schema-registry/resolve-references/test`: a step with `> set schemaRegistryResolveReferences := false`, re-run download, assert the referenced file is **absent** and the root file present (PS-2)
- [x] T010 [P] [US2] Verify backward compatibility: confirm existing scripted `src/sbt-test/schema-registry/download-success/` stays green and a no-references subject yields byte-identical output to pre-feature (SC-004 / acceptance 1.3); add a `must-mirror` golden-file check if not already present

**Checkpoint**: Opt-out and backward compatibility proven.

---

## Phase 5: User Story 3 - Safe handling of cyclic and shared references (Priority: P3)

**Goal**: Cyclic, shared-dependency, and divergent-version reference graphs resolve without hangs or duplicate/dropped schemas.

**Independent Test**: Pure-logic graphs (cycle, shared dep, divergent diamond) resolve to the expected deduped, terminating result — best tested as units against `ReferenceResolver` (quickstart scenario 1).

- [x] T011 [US3] Extend `src/test/scala/org/galaxio/avro/ReferenceResolverSpec.scala` with RR-4 (shared dep `D` once), RR-5 (divergent diamond `A→B@1` + `A→C→B@2` keeps **both** `Pinned(B,1)` and `Pinned(B,2)`), RR-7 (deep chain depth ≥10k no `StackOverflow`), RR-9 (counter-wrapped stub proves cycle/shared dedup prevents refetch). (RR-3 cycle and RR-6 fail-fast moved to T003 in US1.) If any reveals an impl gap, fix `ReferenceResolver.resolve` (T004)

**Checkpoint**: Robustness guarantees (FR-004/FR-005/SC-003) verified.

---

## Phase 6: Polish & Cross-Cutting

- [x] T012 [P] Document `schemaRegistryResolveReferences` (purpose, default `true`, opt-out) and transitive/pinned/cycle behavior in `README.md` and `AGENTS.md`; note the known limitation — subject-keyed `VersionManifest` may redundantly re-write a divergent-version schema on later runs (flagged follow-up, never incorrect; see [research.md](research.md) Decision 4)
- [x] T013 [P] Run `sbt scalafmtAll scalafmtSbt` and confirm clean compile under `-Xfatal-warnings` (Constitution V); explicitly unbox Confluent `getVersion` to avoid warnings
- [x] T014 Run full gate from [quickstart.md](quickstart.md): `sbt scalafmtCheckAll scalafmtSbtCheck compile test`, then `sbt it/test` and `sbt scripted` (Docker)

---

## Dependencies & Execution Order

```
Setup (T001)
   └─> Foundational (T002)
          └─> US1 / P1  (T003 → T004 → T005 → T006 → {T007, T008})   ← MVP
                 ├─> US2 / P2  (T009 → T010)        depends on T006 gate
                 └─> US3 / P3  (T011)               depends on T004 resolver
                        └─> Polish (T012, T013 → T014)
```

- **US2 and US3 are independent of each other** — both depend only on US1, can proceed in either order or concurrently (different files).
- **Within US1**: T003 (test) before T004 (impl); T004 before T005/T006; T007 and T008 after T006.
- **Same-file constraints**: T002 and T004 edit `ReferenceResolver.scala` (sequential); T005 and T006 edit `SchemaDownloaderPlugin.scala` (sequential); T003 and T011 edit `ReferenceResolverSpec.scala` (T011 after T003).

## Parallel Execution Examples

- **US1 validation**: after T006, run T007 (integration spec) and T008 (scripted test) in parallel — different files.
- **Polish**: T012 (docs) and T013 (format) in parallel — different files; T014 (full gate) after both.
- **Across stories**: once US1 lands, a second contributor can take US2 (scripted/regression) while another takes US3 (unit safety suite).

## Implementation Strategy

- **MVP = US1 (Phase 1–3)**: delivers the entire feature value — transitive, pinned, default-on download. Cycle safety is intrinsic to the T004 resolver (two-level dedup), so the MVP is already correct on cyclic graphs; US3 *proves* it.
- **Incremental**: ship US1, then US2 (opt-out/back-compat verification), then US3 (robustness test suite), then polish.
- **Test-first throughout**: T003 before T004; T011 may drive fixes back into T004.
