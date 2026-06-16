# sbt Keys Contract: Parallel Schema Downloads

## New Setting Keys

### schemaRegistryParallelism

```
settingKey[Int]("Number of concurrent schema downloads (1 = sequential)")
```

- **Type**: `Int`
- **Default**: `4`
- **Valid range**: 1–32
- **Behavior**:
  - `1` → sequential downloads, no thread pool created
  - `2–32` → concurrent downloads with fixed thread pool of that size
  - `< 1` or `> 32` → configuration error at task execution time

### schemaRegistryRetries

```
settingKey[Int]("Maximum retry attempts for transient download failures (0 = no retry)")
```

- **Type**: `Int`
- **Default**: `3`
- **Valid range**: 0–10
- **Behavior**:
  - `0` → no automatic retry, failures reported immediately
  - `1–10` → retry transient failures (network errors, server errors) with exponential backoff
  - `< 0` or `> 10` → configuration error at task execution time
- **Backoff**: exponential, starting at 100ms with 2x multiplier

## Existing Keys (unchanged)

All existing sbt keys retain their current behavior and defaults:
- `schemaRegistryUrl`, `schemaRegistryTargetFolder`, `schemaRegistrySubjects`
- `schemaRegistrySubjectPatterns`, `schemaRegistryCacheSize`
- `schemaRegistryAuth`, `schemaRegistryProperties`
- `schemaRegistryIncremental`

## Usage Examples

```scala
// build.sbt — use defaults (parallelism=4, retries=3)
schemaRegistryUrl := "http://localhost:8081"
schemaRegistrySubjects := Seq(latest("my-topic-value"))

// build.sbt — aggressive parallelism for large projects
schemaRegistryParallelism := 16

// build.sbt — sequential mode for debugging
schemaRegistryParallelism := 1

// build.sbt — disable retry for fast-fail CI
schemaRegistryRetries := 0

// build.sbt — rate-limited registry
schemaRegistryParallelism := 2
schemaRegistryRetries := 5
```

## Logging Contract

### Progress (info level)

```
Downloaded {subject-name} ({completed}/{total})
```

Emitted as each subject completes. Counter is thread-safe (atomic). Order of completion messages is non-deterministic when parallelism > 1.

### Retry (warn level)

```
Retry {attempt}/{max} for {subject-name}: {error-message}
```

### Summary (info level)

```
{downloaded} downloaded, {skipped} skipped, {failed} failed
```

### Errors (error level)

```
Failed to download schema {subject-name}: {error-message}
```
