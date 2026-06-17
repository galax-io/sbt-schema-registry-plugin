# Research: Parallel Schema Downloads

## R1: Concurrency Approach — cats-effect vs stdlib Future

**Decision**: stdlib `scala.concurrent.Future` with `java.util.concurrent.Executors.newFixedThreadPool`

**Rationale**:
- Zero new dependencies — critical for an sbt plugin where dependency footprint matters
- `Future` is sufficient for blocking I/O parallelism (HTTP calls to registry)
- Bounded thread pool via loan pattern (`try/finally pool.shutdown()`) ensures cleanup
- `Downloader.schemaSubjectToFile` is already `Either`-based and synchronous — wrapping in `Future(...)` is trivial
- Constitution principle VI (new deps require approval) — stdlib avoids the gate entirely

**Alternatives considered**:
- **cats-effect IO + parTraverseN**: Idiomatic, automatic bounded concurrency, no manual pool management. Rejected: adds `cats-effect` + `cats-core` transitive dependencies to the plugin classpath, risk of version conflicts with user projects.
- **Java CompletableFuture**: Lower-level, more verbose, no gain over Scala Future for this use case.

## R2: Thread Pool Strategy

**Decision**: Fixed thread pool sized to parallelism setting, loan pattern lifecycle

**Rationale**:
- `Executors.newFixedThreadPool(n)` gives exact bounded concurrency matching the user's setting
- Loan pattern ensures pool shutdown even on exceptions: `val pool = ...; try { ... } finally { pool.shutdown() }`
- When `parallelism=1`, skip pool creation entirely — use sequential `List.map` directly
- `pool.shutdown()` + `pool.awaitTermination(timeout)` for clean teardown

**Alternatives considered**:
- **ForkJoinPool**: Designed for recursive tasks, not blocking I/O. Would require `managedBlock` wrappers.
- **Global ExecutionContext**: Unbounded, shared with sbt's own threads — risk of starvation and unpredictable concurrency.
- **Cached thread pool**: No upper bound on threads — defeats the purpose of bounded parallelism.

## R3: Retry Strategy

**Decision**: Exponential backoff with configurable max retries (default 3)

**Rationale**:
- Exponential backoff (100ms, 200ms, 400ms, ...) is standard for transient HTTP failures
- Retry only on `SchemaFetchFailed` (transient network/server errors) — not on `InvalidSubjectName`, `UnsupportedSchemaType`, or `WriteError` (permanent failures)
- Max retries configurable via `schemaRegistryRetries := 3` sbt setting
- `retries := 0` disables retry completely
- Each retry logs at warn level with attempt count

**Alternatives considered**:
- **Fixed delay retry**: Simpler but less effective against rate limiting / server load.
- **Jitter-based backoff**: Better for high-concurrency distributed systems. Overkill for an sbt plugin with max 32 threads hitting one registry.

## R4: Thread Safety of CachedSchemaRegistryClient

**Decision**: Safe to share across threads — confirmed

**Rationale**:
- Confluent's `CachedSchemaRegistryClient` uses `ConcurrentHashMap` internally for schema cache
- HTTP calls use the Jersey client which is thread-safe
- Already used in multi-threaded environments (Kafka Connect workers, Kafka Streams)
- sbt creates a single client instance per task invocation — safe to share across the download thread pool

**Sources**: Confluent Schema Registry client source code, Kafka Connect usage patterns.

## R5: Progress Logging Thread Safety

**Decision**: Use `java.util.concurrent.atomic.AtomicInteger` for progress counter

**Rationale**:
- sbt `Logger` is thread-safe (backed by `sbt.internal.util.ConsoleLogger` with synchronized writes)
- Progress counter (`completed` out of `total`) needs atomic increment
- `AtomicInteger.incrementAndGet()` provides lock-free thread-safe counter
- Log format: `"Downloaded {subject} ({n}/{total})"` where `n = counter.incrementAndGet()`

## R6: Interaction with Incremental Resolver

**Decision**: Incremental filtering happens BEFORE parallel download — sequential filter, parallel fetch

**Rationale**:
- `IncrementalResolver.plan()` calls `client.getLatestSchemaMetadata` for version checks — these are fast metadata calls
- Version checks remain sequential (simple list map) — they're cheap and the result is needed to filter the download list
- Only the filtered `Download` decisions are dispatched to the thread pool
- This means parallelism=4 with 3 changed subjects out of 20 → only 3 concurrent downloads, not 20

**Alternative considered**:
- Parallelize version checks too: adds complexity for minimal gain (metadata calls are ~10ms each vs ~100ms for full schema fetch+write).

## R7: Error Classification for Retry

**Decision**: Classify errors as retryable vs permanent

| Error Type | Retryable? | Reason |
|-----------|------------|--------|
| `SchemaFetchFailed` | Yes | Network timeout, 503, connection refused — transient |
| `InvalidSubjectName` | No | Config error — won't change on retry |
| `UnsupportedSchemaType` | No | Schema type mismatch — permanent |
| `WriteError` | No | Disk error — unlikely to self-heal |
| `InvalidPattern` | No | Regex syntax — config error |
| `SubjectListFailed` | No | Pre-download step, not per-subject retry |
| `ManifestParseError` | No | Already handled by fallback to empty manifest |

## R8: Validation Bounds

**Decision**: Parallelism valid range 1–32, retries valid range 0–10

**Rationale**:
- Parallelism: 32 is generous for any realistic registry setup. Higher risks socket exhaustion / rate limiting.
- Retries: 10 is a reasonable upper bound. Higher would mask real issues and delay builds excessively (exponential backoff at 10 retries = ~100s total wait).
- Both validated at task execution time with clear error messages.
