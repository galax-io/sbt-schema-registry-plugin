# Tasks: Parallel Schema Downloads

**Input**: Design documents from `specs/006-parallel-schema-downloads/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/sbt-keys.md

**Tests**: Included — constitution mandates test-first development (Principle III).

**Organization**: Tasks grouped by user story. US1 (Faster Downloads) and US3 (Partial Failure) are combined into one phase — they share the same core component (`ParallelDownloader`), and parallel execution with error collection is inseparable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story (US1, US2, US3, US4)

---

## Phase 1: Setup

**Purpose**: Add new error types and sbt setting declarations needed by all stories

- [x] T001 [P] Add `InvalidParallelism(value: Int)` and `InvalidRetryConfig(value: Int)` cases to sealed trait in `src/main/scala/org/galaxio/avro/DownloadError.scala`
- [x] T002 [P] Add `schemaRegistryParallelism` (default 4) and `schemaRegistryRetries` (default 3) setting keys with defaults in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` autoImport object and defaultSettings

**Checkpoint**: New error types and sbt settings available. `sbt compile` passes.

---

## Phase 2: Foundational — RetryPolicy

**Purpose**: Retry logic needed by all user stories. MUST complete before any story implementation.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for RetryPolicy

- [x] T003 Create `RetryPolicySpec` unit tests in `src/test/scala/org/galaxio/avro/RetryPolicySpec.scala` — cover: successful on first attempt, retry on `SchemaFetchFailed` up to max, no retry on permanent errors (`InvalidSubjectName`, `UnsupportedSchemaType`, `WriteError`), retries exhausted returns last error, `maxRetries=0` disables retry, verify exponential delay progression

### Implementation

- [x] T004 Implement `RetryPolicy` in `src/main/scala/org/galaxio/avro/RetryPolicy.scala` — fields: `maxRetries: Int`, `initialDelayMs: Long = 100`, `backoffMultiplier: Double = 2.0`. Method `execute[A](op: => Either[DownloadError, A], subjectName: String, logger: Logger): Either[DownloadError, A]`. Retry only `SchemaFetchFailed`, log each retry at warn level `"Retry {n}/{max} for {subject}: {error}"`, delay via `Thread.sleep(initialDelayMs * backoffMultiplier^attempt)`

**Checkpoint**: `RetryPolicy` tested and implemented. `sbt test` passes with RetryPolicySpec green.

---

## Phase 3: US1 + US3 — Parallel Downloads with Error Collection (Priority: P1) 🎯 MVP

**Goal**: Concurrent schema downloads with bounded parallelism. All subjects attempted regardless of individual failures — errors collected, not fail-fast.

**Independent Test**: Configure 10+ subjects (some invalid), run with `schemaRegistryParallelism := 4`. All valid schemas download, all errors reported with subject names and reasons, progress logged as each completes.

### Tests for US1 + US3

- [x] T005 [P] [US1] Create `ParallelDownloaderSpec` unit tests in `src/test/scala/org/galaxio/avro/ParallelDownloaderSpec.scala` — cover: downloads N subjects concurrently with mocked Downloader, respects parallelism bound, all subjects attempted even when some fail, returns `List[(RegistrySubject, Either[DownloadError, Path])]` with all results, progress counter increments correctly (AtomicInteger), thread pool created and shut down (verify via mock/spy)
- [x] T006 [P] [US3] Add partial failure test cases to `src/test/scala/org/galaxio/avro/ParallelDownloaderSpec.scala` — cover: mix of successes and failures all collected, `SchemaFetchFailed` retried per RetryPolicy before final failure, permanent errors (`InvalidSubjectName`) not retried, error results contain subject name and failure reason

### Implementation

- [x] T007 [US1] Implement `ParallelDownloader` in `src/main/scala/org/galaxio/avro/ParallelDownloader.scala` — constructor params: `downloader: Downloader`, `parallelism: Int`, `retryPolicy: RetryPolicy`, `logger: Logger`. Method `downloadAll(subjects: List[RegistrySubject]): List[(RegistrySubject, Either[DownloadError, Path])]`. When `parallelism > 1`: create `Executors.newFixedThreadPool(parallelism)`, wrap each subject in `Future { retryPolicy.execute(downloader.schemaSubjectToFile(subject), ...) }`, collect via `Await.result(Future.sequence(...))`, shutdown pool in `finally`. Use `AtomicInteger` for progress counter, log `"Downloaded {subject} ({n}/{total})"` on each completion.
- [x] T008 [US1] Add parallelism validation to `ParallelDownloader` or plugin task — reject values < 1 or > 32 with `DownloadError.InvalidParallelism`, reject retries < 0 or > 10 with `DownloadError.InvalidRetryConfig`

**Checkpoint**: Parallel download with retry and error collection works. Unit tests green. `sbt test` passes.

---

## Phase 4: US2 — Sequential Fallback (Priority: P2)

**Goal**: `parallelism=1` preserves exact sequential behavior — no thread pool, no concurrency overhead.

**Independent Test**: Set `schemaRegistryParallelism := 1`, verify schemas download one-at-a-time in declaration order, no extra threads spawned.

### Tests for US2

- [x] T009 [US2] Add sequential fallback tests to `src/test/scala/org/galaxio/avro/ParallelDownloaderSpec.scala` — cover: `parallelism=1` calls `subjects.map(...)` directly without creating thread pool, downloads execute in declaration order, retry still works in sequential mode

### Implementation

- [x] T010 [US2] Add sequential path to `ParallelDownloader.downloadAll` in `src/main/scala/org/galaxio/avro/ParallelDownloader.scala` — when `parallelism == 1`: use `subjects.map(s => s -> retryPolicy.execute(downloader.schemaSubjectToFile(s), ...))` directly, still log progress counter, no `Executors` or `Future` involved

**Checkpoint**: Sequential fallback works. Both parallel and sequential paths tested. `sbt test` passes.

---

## Phase 5: US4 — Incremental + Parallel Composition (Priority: P2)

**Goal**: Parallel downloads compose correctly with incremental caching — only changed schemas participate in concurrent fetching.

**Independent Test**: Run download twice. Second run skips unchanged, only re-fetches changed subjects concurrently.

### Tests for US4

- [x] T011 [P] [US4] Create `ParallelDownloaderIntegrationSpec` in `src/it/test/scala/org/galaxio/avro/ParallelDownloaderIntegrationSpec.scala` — Testcontainers with real Schema Registry. Cover: parallel download of 5+ subjects, incremental second run skips unchanged subjects, parallel + incremental composition (only changed subjects fetched concurrently), partial failure with real registry errors
- [x] T012 [P] [US4] Add settings validation test to `src/test/scala/org/galaxio/avro/ParallelDownloaderSpec.scala` — cover: invalid parallelism (0, -1, 33) rejected, invalid retries (-1, 11) rejected, boundary values (1, 32, 0 retries, 10 retries) accepted

### Implementation

- [x] T013 [US4] Wire `ParallelDownloader` into `SchemaDownloaderPlugin.schemaRegistryDownload` task in `src/main/scala/org/galaxio/avro/SchemaDownloaderPlugin.scala` — read `schemaRegistryParallelism` and `schemaRegistryRetries` settings, validate bounds, construct `RetryPolicy` and `ParallelDownloader`, replace sequential `downloads.collect { case d @ DownloadDecision.Download(...) => d -> downloader.schemaSubjectToFile(...) }` with `parallelDownloader.downloadAll(downloads.collect { case d: DownloadDecision.Download => d.subject })`, preserve incremental filter (sequential) before parallel fetch, update manifest with successful downloads, log final summary `"{downloaded} downloaded, {skipped} skipped, {failed} failed"`

**Checkpoint**: Full integration working — incremental filtering feeds into parallel downloads. `sbt test` and `sbt it/test` pass.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: E2E validation, formatting, final cleanup

- [x] T014 [P] Create sbt scripted e2e test in `src/sbt-test/schema-registry/download-parallel/` — `build.sbt` with 3+ subjects, `test` script verifying schemas downloaded. Test default parallelism behavior.
- [x] T015 [P] Create sbt scripted e2e test in `src/sbt-test/schema-registry/download-sequential/` — `build.sbt` with `schemaRegistryParallelism := 1`, `test` script verifying sequential download.
- [x] T016 [P] Create sbt scripted e2e test in `src/sbt-test/schema-registry/invalid-parallelism/` — `build.sbt` with `schemaRegistryParallelism := 0`, `test` script verifying error message.
- [x] T017 Run `sbt scalafmtAll scalafmtSbt` and fix any formatting issues
- [x] T018 Run `sbt compile test it/test` full validation per quickstart.md (scripted skipped — Docker unavailable in worktree)

**Checkpoint**: All tests pass, formatting clean, feature complete.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 (needs new error types)
- **Phase 3 (US1+US3)**: Depends on Phase 2 (needs RetryPolicy)
- **Phase 4 (US2)**: Depends on Phase 3 (extends ParallelDownloader)
- **Phase 5 (US4)**: Depends on Phase 3 (needs working ParallelDownloader to wire into plugin)
- **Phase 6 (Polish)**: Depends on Phase 5 (needs full integration)

### User Story Dependencies

- **US1+US3 (P1)**: After Foundational — no dependencies on other stories
- **US2 (P2)**: After US1+US3 — adds sequential branch to existing ParallelDownloader
- **US4 (P2)**: After US1+US3 — wires ParallelDownloader into plugin with incremental resolver

### Within Each Phase

- Tests written FIRST, verified to FAIL before implementation (constitution Principle III)
- Implementation follows test structure
- `sbt test` after each phase checkpoint

### Parallel Opportunities

**Phase 1**: T001 and T002 can run in parallel (different files)

**Phase 3**: T005 and T006 can run in parallel (test files, same file but independent test classes/groups)

**Phase 4 + Phase 5**: US2 (T009-T010) and US4 tests (T011-T012) can run in parallel after Phase 3 completes. US4 implementation (T013) depends on US2 being done (both modify the same execution path).

**Phase 6**: T014, T015, T016 can all run in parallel (separate scripted test directories)

---

## Parallel Example: Phase 1

```
Task: "Add InvalidParallelism and InvalidRetryConfig to DownloadError.scala"  [T001]
Task: "Add schemaRegistryParallelism and schemaRegistryRetries to SchemaDownloaderPlugin.scala"  [T002]
```

## Parallel Example: Phase 3 (Tests)

```
Task: "ParallelDownloaderSpec — parallel execution tests"  [T005]
Task: "ParallelDownloaderSpec — partial failure tests"  [T006]
```

## Parallel Example: Phase 6 (E2E)

```
Task: "Scripted test — default parallelism"  [T014]
Task: "Scripted test — sequential fallback"  [T015]
Task: "Scripted test — invalid config"  [T016]
```

---

## Implementation Strategy

### MVP First (US1 + US3 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 2: RetryPolicy (T003–T004)
3. Complete Phase 3: ParallelDownloader (T005–T008)
4. **STOP and VALIDATE**: Run `sbt test` — parallel downloads work with retry and error collection
5. This alone delivers the core value: faster downloads with robust error handling

### Incremental Delivery

1. Setup + Foundational → RetryPolicy ready
2. US1+US3 → Parallel downloads with error collection (MVP!)
3. US2 → Sequential fallback guarantees backward compatibility
4. US4 → Full plugin integration with incremental caching
5. Polish → E2E tests, formatting, final validation

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps task to user story for traceability
- US1+US3 combined — parallel execution and error collection are inseparable in `ParallelDownloader`
- US4 is the integration story — wires everything into the actual sbt plugin task
- Constitution Principle III: tests MUST fail before implementation, no mocking where real path exists
- Constitution Principle V: `scalafmtAll` before every commit
