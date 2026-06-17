# Quickstart Validation: Parallel Schema Downloads

## Prerequisites

- Docker running (for integration tests with Testcontainers)
- JDK 17+
- sbt 1.12.x

## Validation Scenarios

### 1. Unit Tests — Parallel Download Logic

```bash
sbt test
```

**Expected**: All tests pass, including:
- `ParallelDownloaderSpec` — verifies parallel execution with mocked Downloader
- `RetryPolicySpec` — verifies retry/backoff logic with mocked operations
- Settings validation tests — verifies parallelism (1–32) and retry (0–10) bounds

### 2. Integration Tests — Real Schema Registry

```bash
sbt it/test
```

**Expected**: `ParallelDownloaderIntegrationSpec` passes, verifying:
- Concurrent download of multiple subjects from Testcontainers registry
- Partial failure handling (mix of valid/invalid subjects)
- Retry behavior with transient errors
- Thread pool cleanup (no leaked threads after test)

### 3. E2E — sbt Scripted Test

```bash
sbt scripted parallel-download/*
```

**Expected**: sbt scripted test passes, verifying:
- `schemaRegistryParallelism := 4` downloads schemas concurrently
- `schemaRegistryParallelism := 1` downloads sequentially
- Invalid parallelism (0, -1, 33) rejected with error
- `schemaRegistryRetries := 0` disables retry

### 4. Manual Validation — Parallel vs Sequential Timing

```bash
# Sequential baseline
sbt 'set schemaRegistryParallelism := 1' schemaRegistryDownload

# Parallel (default)
sbt schemaRegistryDownload
```

**Expected**: Parallel run completes noticeably faster for projects with 5+ subjects. Progress logging shows running count: `Downloaded subject-name (3/20)`.

### 5. Manual Validation — Incremental + Parallel Composition

```bash
# First run — downloads all
sbt schemaRegistryDownload

# Second run — skips unchanged
sbt schemaRegistryDownload
```

**Expected**: Second run logs `N skipped` for cached subjects, only changed subjects downloaded concurrently.

### 6. Validation — Error Reporting

```bash
# Configure a non-existent subject alongside valid ones
# Run download
sbt schemaRegistryDownload
```

**Expected**: Valid schemas downloaded, invalid subject reported as error with subject name and reason. Task fails with summary count.

## Key Artifacts to Verify

- [ ] `ParallelDownloader.scala` — see [data-model.md](data-model.md) for entity details
- [ ] `RetryPolicy.scala` — see [data-model.md](data-model.md) for retry configuration
- [ ] New sbt settings wired — see [contracts/sbt-keys.md](contracts/sbt-keys.md) for key definitions
- [ ] Existing `Downloader.scala` unchanged
- [ ] Existing `IncrementalResolver.scala` unchanged
- [ ] `scalafmtAll` and `scalafmtSbt` pass
- [ ] `compile` with `-Xfatal-warnings` passes
