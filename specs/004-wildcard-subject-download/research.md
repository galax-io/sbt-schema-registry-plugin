# Research: Wildcard Subject Download

**Feature**: 004-wildcard-subject-download | **Date**: 2026-06-15

## R1: SchemaRegistryClient.getAllSubjects() API

**Decision**: Use `client.getAllSubjects()` from Confluent `kafka-schema-registry-client` for fetching the complete subject list.

**Rationale**: Already a dependency (v8.2.1). Method returns `java.util.List[String]` with all subject names. No additional dependencies needed. Available in all supported Schema Registry versions.

**Alternatives considered**:
- `GET /subjects?subjectPrefix=` (server-side prefix filter, SR 7.4+): Better performance for prefix-only patterns but limits to prefix matching only; not full regex. Could be added as optimization later.
- Direct HTTP calls to Schema Registry REST API: Unnecessary — the client already wraps this.

## R2: Regex Matching Strategy — Full Match

**Decision**: Use `pattern.matches(subjectName)` (full-match) semantics, equivalent to Java's `Pattern.matches()` / Scala's `Regex.pattern.matcher(s).matches()`.

**Rationale**: Full match prevents accidental over-matching (e.g., pattern `"User"` won't match `"com.myorg.User-value"`). Aligns with Gradle competitor (ImFlog/schema-registry-plugin) behavior. Users write explicit patterns covering the entire subject name.

**Alternatives considered**:
- Partial/find match (`Regex.findFirstIn`): More lenient but error-prone — short patterns match unintended subjects. Rejected per clarification session.
- Glob syntax: Simpler for users but less expressive. Regex is industry standard for this domain.

## R3: Error Handling — Fail-Fast

**Decision**: Validate all patterns before any resolution. If any pattern is syntactically invalid, fail the entire task immediately.

**Rationale**: Build tools should fail loudly on configuration errors. Silent partial success hides mistakes — user believes all schemas downloaded but a typo in one pattern means some are missing. Fail-fast per clarification session.

**Alternatives considered**:
- Fail-partial with warnings: Log invalid patterns and continue with valid ones. Rejected — too easy to miss warnings in build output.
- Deferred validation (validate on match): No benefit; patterns are cheap to compile upfront.

## R4: Deduplication Strategy

**Decision**: Exact subjects appear first in the combined list. Deduplication by subject name, keeping the first occurrence. This guarantees explicit (pinned) subjects win over pattern-matched (latest) subjects.

**Rationale**: Users who pin a specific version via `schemaRegistrySubjects` have explicit intent. Pattern matches default to latest. Ordering exact-before-pattern ensures pinned versions are preserved on overlap.

**Alternatives considered**:
- Last-wins ordering: Would require users to control declaration order between settings — confusing.
- Error on overlap: Too strict — overlapping is natural when broad patterns cover explicitly listed subjects.

## R5: Where to Place Resolution Logic

**Decision**: New `SubjectResolver` object with pure `resolve` method taking `SchemaRegistryClient` and `List[SubjectSpec]`, returning `Either[DownloadError, DownloadPlan]`.

**Rationale**: Follows Single Responsibility (Constitution II). `Downloader` stays focused on fetch+write. `SubjectResolver` owns the pattern-to-subjects expansion. Plugin wires them together. Resolver is independently testable with mocked client.

**Alternatives considered**:
- Add resolution logic directly to `Downloader`: Violates single responsibility. `Downloader` handles individual subject download, not subject discovery.
- Add resolution logic to `SchemaDownloaderPlugin`: Mixes wiring with business logic. Plugin should delegate.

## R6: New Error Types

**Decision**: Add two new cases to `DownloadError`:
1. `InvalidPattern(pattern: String, error: Throwable)` — regex compilation failure
2. `SubjectListFailed(error: Throwable)` — `getAllSubjects()` call failure

**Rationale**: Extends existing `DownloadError` ADT consistently. Both errors are specific to the download workflow (not registration/compatibility). Follows existing pattern of descriptive case classes with `message` and optional `cause`.

**Alternatives considered**:
- Separate `ResolverError` ADT: Unnecessary indirection — these errors surface during the download task.
- Reuse `SchemaFetchFailed`: Semantically different — that's for individual schema fetch, not subject listing.
