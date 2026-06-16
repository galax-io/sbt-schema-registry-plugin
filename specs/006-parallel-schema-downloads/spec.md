# Feature Specification: Parallel Schema Downloads

**Feature Branch**: `feat/006-parallel-schema-downloads`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "Parallel schema downloads — concurrent fetching to reduce wall-clock time for projects with many schemas"

## Clarifications

### Session 2026-06-16

- Q: Should transiently failed downloads be retried automatically? → A: Yes — configurable retry with sensible default (e.g., up to 3 retries with backoff).
- Q: What progress feedback should users see during parallel downloads? → A: Per-subject completion logging — log each subject as it finishes with running count (e.g., "Downloaded subject-name (5/20)").
- Q: Should there be an upper bound on parallelism? → A: Yes — enforce a maximum cap (e.g., 32). Values above are rejected as configuration errors.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Faster Downloads for Large Projects (Priority: P1)

A plugin user has a project with 20+ schema subjects configured. When they run the schema download task, they want all schemas fetched concurrently (up to a configurable limit) so the total download time is significantly shorter than sequential fetching.

**Why this priority**: This is the core value proposition. Sequential downloads create a linear bottleneck — 20 subjects × ~1 second per HTTP round-trip = 20+ seconds. Parallel downloads reduce this to a fraction of the wall-clock time, directly improving developer experience and CI pipeline speed.

**Independent Test**: Can be fully tested by configuring multiple subjects and running the download task with parallelism > 1. Delivers measurable wall-clock improvement vs sequential baseline.

**Acceptance Scenarios**:

1. **Given** a project with 20 configured subjects and `schemaRegistryParallelism := 4`, **When** the user runs the schema download task, **Then** schemas are fetched concurrently with at most 4 simultaneous requests and all 20 schemas are written to the output directory.
2. **Given** a project with 5 configured subjects and default parallelism, **When** the user runs the download task, **Then** schemas download concurrently using the default parallelism setting and complete faster than sequential execution.
3. **Given** a project with 1 configured subject, **When** the user runs the download task with any parallelism setting, **Then** the single schema downloads normally with no overhead from concurrency machinery.

---

### User Story 2 - Sequential Fallback (Priority: P2)

A plugin user wants to disable parallel downloads — perhaps for debugging, rate-limited registries, or deterministic ordering. Setting parallelism to 1 preserves the existing sequential behavior with no thread pool created.

**Why this priority**: Backward compatibility and debugging escape hatch. Users must be able to opt out of concurrency without losing existing functionality.

**Independent Test**: Can be tested by setting `schemaRegistryParallelism := 1` and verifying schemas download one at a time in order, with no thread pool created.

**Acceptance Scenarios**:

1. **Given** `schemaRegistryParallelism := 1`, **When** the user runs the download task, **Then** schemas are fetched sequentially in declaration order with no additional threads spawned.
2. **Given** parallelism is not explicitly configured, **When** the user runs the download task, **Then** the default parallelism value is used (not 1 — the default enables concurrency).

---

### User Story 3 - Graceful Partial Failure (Priority: P1)

When downloading many schemas concurrently, some may fail (network errors, missing subjects, auth issues). The system must attempt all downloads and report all failures — not fail-fast on the first error.

**Why this priority**: Fail-fast on first error in parallel execution would leave users guessing which other schemas also have problems. Collecting all errors in one run saves repeated fix-and-retry cycles.

**Independent Test**: Can be tested by configuring a mix of valid and invalid subjects, running the download, and verifying all valid schemas were written and all errors reported.

**Acceptance Scenarios**:

1. **Given** 10 subjects where 2 have invalid names, **When** the user runs the download task with parallelism > 1, **Then** 8 schemas download successfully, 2 errors are reported with subject names and failure reasons, and the task fails with a summary of all errors.
2. **Given** the schema registry is temporarily unreachable for one subject during parallel download, **When** the download completes, **Then** all other subjects that succeeded are written to disk and the failed subject is reported clearly.

---

### User Story 4 - Parallel Downloads with Incremental Caching (Priority: P2)

The parallel download feature must work correctly with the existing incremental download cache (from feature 005). Unchanged schemas should be skipped based on the cache, and only schemas needing re-download should participate in concurrent fetching.

**Why this priority**: Incremental download is an existing feature. Parallel downloads must compose correctly with it — otherwise users are forced to choose between the two optimizations instead of benefiting from both.

**Independent Test**: Can be tested by running download once (populating cache), modifying one subject, then running again with parallelism > 1 and verifying only the changed subject is re-fetched.

**Acceptance Scenarios**:

1. **Given** a populated incremental download cache and 20 subjects where 3 have changed, **When** the user runs the download task with parallelism enabled, **Then** only the 3 changed subjects are fetched concurrently and the other 17 are skipped from cache.
2. **Given** an empty cache (first run), **When** the user runs the download with parallelism enabled, **Then** all subjects are fetched concurrently as normal.

---

### Edge Cases

- What happens when parallelism is set to 0, negative, or above 32? The system should reject the value as a configuration error with a clear message.
- What happens when parallelism exceeds the number of subjects (but is within the valid range)? The system should work correctly — effectively downloading all subjects at once with no wasted resources.
- What happens when the schema registry enforces rate limiting? Concurrent requests may hit rate limits. The system should retry with backoff per the configured retry policy, then report persistent failures per-subject.
- What happens during a network partition mid-download? Some concurrent requests may succeed while others time out. All successes should be preserved and all failures reported.
- What happens if the thread pool fails to shut down cleanly? The system must ensure the thread pool is always cleaned up (loan pattern), even when downloads throw unexpected exceptions.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support a configurable parallelism setting that controls the maximum number of concurrent schema downloads.
- **FR-002**: System MUST default the parallelism setting to a reasonable value greater than 1 (e.g., 4) to enable concurrency out of the box.
- **FR-003**: When parallelism is set to 1, system MUST execute downloads sequentially without creating any thread pool or concurrency machinery.
- **FR-004**: System MUST attempt to download all configured subjects regardless of individual failures — no fail-fast behavior.
- **FR-005**: System MUST collect and report all download failures at the end of the task, including the subject name and failure reason for each.
- **FR-006**: System MUST ensure concurrent download resources (thread pools, execution contexts) are always cleaned up after the download completes, including on failure.
- **FR-007**: System MUST work correctly with the existing incremental download cache, only fetching subjects that need re-downloading.
- **FR-008**: System MUST reject invalid parallelism values (zero, negative, or above the maximum cap of 32) with a clear error message at configuration time.
- **FR-009**: System MUST preserve the existing download behavior (file output format, error reporting style) — only the execution strategy changes.
- **FR-010**: The individual download operation for each schema MUST return success or failure without throwing exceptions — errors captured as values.
- **FR-011**: System MUST retry transiently failed downloads (network timeouts, server errors) with configurable retry count and backoff strategy, defaulting to 3 retries.
- **FR-012**: System MUST support a configurable retry count setting so users can tune or disable retries (setting retries to 0 disables automatic retry).
- **FR-013**: System MUST log each subject's download completion with a running count (e.g., "Downloaded subject-name (5/20)") so users have real-time visibility into parallel progress.
- **FR-014**: System MUST log a final summary after all downloads complete, including total successes, failures, and skipped-from-cache counts.

### Key Entities

- **Parallelism Setting**: User-configurable integer controlling max concurrent downloads. Valid range: 1–32.
- **Download Result**: Per-subject outcome — either a successfully downloaded schema or a structured error containing subject name and failure reason.
- **Thread Pool / Execution Context**: Bounded concurrency resource with lifecycle tied to the download task invocation (created before, destroyed after).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Projects with 20+ schemas experience at least 3x wall-clock speedup compared to sequential download when parallelism is set to 4 or higher.
- **SC-002**: Setting parallelism to 1 produces identical behavior and timing to the current sequential implementation.
- **SC-003**: When partial failures occur, 100% of successful downloads are preserved and 100% of failures are reported with actionable details.
- **SC-004**: No thread pool or execution context leaks — resources are always cleaned up regardless of success or failure.
- **SC-005**: Existing plugin users who do not configure parallelism benefit from faster downloads with no configuration changes required.
- **SC-006**: Transient failures (network timeouts, 503s) are automatically retried up to the configured limit before being reported as failures, reducing false-negative download runs.

## Assumptions

- The Schema Registry client used by the plugin is thread-safe and can be shared across concurrent download threads.
- Network bandwidth to the schema registry is not a bottleneck — the speedup comes from overlapping HTTP round-trip latency.
- The default parallelism of 4 is appropriate for most environments. Users with rate-limited registries can tune down.
- File system writes from concurrent downloads do not conflict because each subject writes to a distinct file path.
- The incremental download cache (feature 005) uses thread-safe read access for checking schema freshness.
