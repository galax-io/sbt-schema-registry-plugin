# Data Model: Parallel Schema Downloads

## New Entities

### ParallelDownloader

Orchestrates concurrent schema downloads with bounded parallelism and retry.

**Fields/Parameters**:
- `downloader: Downloader` — existing single-subject fetcher (injected)
- `parallelism: Int` — max concurrent downloads (1–32)
- `retryPolicy: RetryPolicy` — retry configuration
- `logger: Logger` — sbt logger for progress reporting

**Behavior**:
- When `parallelism == 1`: delegates to sequential `List.map` directly, no thread pool
- When `parallelism > 1`: creates fixed thread pool, wraps each download in `Future`, collects results via `Future.sequence`, shuts down pool in `finally` block
- Returns `List[(RegistrySubject, Either[DownloadError, Path])]` — same shape as current sequential results

**Relationships**:
- Uses `Downloader` (composition, not inheritance)
- Used by `SchemaDownloaderPlugin` (replaces direct `downloader.schemaSubjectToFile` calls)

### RetryPolicy

Configures retry behavior for transient download failures.

**Fields**:
- `maxRetries: Int` — maximum retry attempts (0 = no retry, valid range 0–10)
- `initialDelay: Long` — base delay in milliseconds (default: 100)
- `backoffMultiplier: Double` — exponential multiplier (default: 2.0)

**Behavior**:
- `execute(op: => Either[DownloadError, A]): Either[DownloadError, A]` — runs operation, retries on retryable failures
- Only `SchemaFetchFailed` is retryable — all other `DownloadError` subtypes are permanent
- Delay between attempts: `initialDelay * backoffMultiplier^attemptIndex`
- Logs each retry at warn level: `"Retry {n}/{max} for {subject}: {error}"`

**Relationships**:
- Used by `ParallelDownloader` (wraps each per-subject download)

## Modified Entities

### DownloadError (existing sealed trait)

**New case classes**:
- `InvalidParallelism(value: Int)` — parallelism outside valid range 1–32
- `InvalidRetryConfig(value: Int)` — retry count outside valid range 0–10

### SchemaDownloaderPlugin (existing object)

**New sbt settings**:
- `schemaRegistryParallelism: Int` — max concurrent downloads (default: 4)
- `schemaRegistryRetries: Int` — max retry attempts per subject (default: 3)

**Modified task**: `schemaRegistryDownload` — wires `ParallelDownloader` instead of direct `Downloader` calls

## Unchanged Entities

- `Downloader` — single-subject fetch+write, `Either`-based, no changes
- `RegistrySubject` — sealed ADT (Pinned/Latest), no changes
- `DownloadDecision` — sealed ADT (Download/Skip), no changes
- `IncrementalResolver` — sequential filtering, no changes
- `VersionManifest` — JSON-based manifest, no changes
- `SubjectResolver` — pattern resolution, no changes

## State Transitions

```
Download Task Invocation
  │
  ├─ Validate parallelism (1–32) and retries (0–10)
  │   └─ Invalid → error, task aborts
  │
  ├─ Resolve subjects (patterns + exact) [sequential]
  │
  ├─ Incremental filter → DownloadDecision list [sequential]
  │   ├─ Skip decisions → log "up to date"
  │   └─ Download decisions → feed to ParallelDownloader
  │
  ├─ ParallelDownloader.downloadAll(decisions)
  │   ├─ parallelism=1 → sequential map with retry
  │   └─ parallelism>1 → fixed thread pool, Future per subject with retry
  │       ├─ Per subject: RetryPolicy.execute(downloader.schemaSubjectToFile)
  │       ├─ On completion: AtomicInteger counter, log progress
  │       └─ Collect all results (no fail-fast)
  │
  ├─ Shutdown thread pool (finally block)
  │
  ├─ Update manifest with successful downloads
  │
  └─ Report: N downloaded, M skipped, K failed
      └─ If any failed → sys.error with summary
```
