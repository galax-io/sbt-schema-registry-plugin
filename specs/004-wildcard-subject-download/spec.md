# Feature Specification: Wildcard Subject Download

**Feature Branch**: `004-wildcard-subject-download`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "feat: download subjects by wildcard pattern — allow regex-based subject matching against Schema Registry so users don't have to list every subject explicitly"

## Clarifications

### Session 2026-06-15

- Q: Regex matching semantics — full match or partial/find? → A: Full match — pattern must match the entire subject name.
- Q: Error handling with multiple patterns — fail-fast or fail-partial? → A: Fail-fast — one invalid pattern fails the entire task.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Download Schemas by Regex Pattern (Priority: P1)

A plugin user with a large Schema Registry (50+ subjects) wants to download all schemas whose subject names match a regex pattern, instead of listing each subject individually in `build.sbt`. They add a single pattern like `"com\\.myorg\\..*-value"` to `schemaRegistrySubjectPatterns` and run the download task. The plugin fetches the full subject list from Schema Registry, filters by the regex, and downloads the latest schema for every match.

**Why this priority**: Core value proposition. Without pattern matching, every subject must be listed explicitly — the main pain point this feature solves.

**Independent Test**: Can be fully tested by registering several subjects in Schema Registry, configuring one regex pattern, running the download task, and verifying only matching schemas appear in the output directory.

**Acceptance Scenarios**:

1. **Given** Schema Registry contains subjects `["com.myorg.User-value", "com.myorg.Order-value", "internal.Audit-value"]`, **When** user configures pattern `"com\\.myorg\\..*-value"` and runs download, **Then** only `com.myorg.User-value` and `com.myorg.Order-value` schemas are downloaded.
2. **Given** Schema Registry contains subjects, **When** user configures a pattern that matches zero subjects, **Then** no schemas are downloaded and the task completes without error, logging that zero subjects matched.
3. **Given** user configures an invalid regex pattern (e.g., unclosed group `"com\\.myorg\\.(*"`), **When** download task runs, **Then** task fails with a clear error message identifying the invalid pattern.

---

### User Story 2 - Combine Exact Subjects with Pattern Subjects (Priority: P1)

A user needs some subjects at pinned versions (e.g., version 3 of `special-subject`) and others at latest via pattern. They configure both `schemaRegistrySubjects` (exact, with pinned version) and `schemaRegistrySubjectPatterns` (regex, always latest). The download task resolves both, deduplicates by subject name, and gives precedence to explicitly listed subjects.

**Why this priority**: Equally critical — existing users already have explicit subjects configured. Patterns must compose with explicit subjects, not replace them.

**Independent Test**: Can be fully tested by registering subjects, configuring one exact subject at a pinned version and one pattern that also matches that subject, running download, and verifying the pinned version is used for the overlap.

**Acceptance Scenarios**:

1. **Given** `schemaRegistrySubjects` contains `RegistrySubject("com.myorg.User-value", 3)` and `schemaRegistrySubjectPatterns` contains `"com\\.myorg\\..*-value"`, **When** download runs, **Then** `com.myorg.User-value` is downloaded at version 3 (exact wins), and other matches are downloaded at latest.
2. **Given** only `schemaRegistrySubjectPatterns` is configured (no explicit subjects), **When** download runs, **Then** all matched subjects are downloaded at their latest version.
3. **Given** only `schemaRegistrySubjects` is configured (no patterns), **When** download runs, **Then** behavior is identical to current plugin behavior (backward compatible).

---

### User Story 3 - Multiple Patterns (Priority: P2)

A user wants to download schemas from multiple namespaces using separate patterns. They configure multiple entries in `schemaRegistrySubjectPatterns`. The plugin evaluates all patterns, unions the results, and deduplicates.

**Why this priority**: Natural extension of P1 — users with multiple teams or namespaces will need multiple patterns.

**Independent Test**: Can be tested by registering subjects across namespaces, configuring two patterns that each match different subsets, and verifying the union is downloaded without duplicates.

**Acceptance Scenarios**:

1. **Given** patterns `["com\\.team-a\\..*", "com\\.team-b\\..*"]` and Registry has subjects from both namespaces, **When** download runs, **Then** subjects from both namespaces are downloaded.
2. **Given** two patterns that both match the same subject, **When** download runs, **Then** that subject is downloaded only once.

---

### Edge Cases

- What happens when Schema Registry is unreachable during subject listing? Task fails with a clear connection error.
- What happens when a pattern matches hundreds of subjects? All are downloaded; the user is responsible for scoping their patterns appropriately. A log message reports the count of matched subjects.
- What happens when the subject list API returns an empty list? Zero subjects match, task completes successfully with a log message.
- What happens when `schemaRegistrySubjectPatterns` is set but empty (`Seq.empty`)? No pattern matching occurs; only explicit subjects (if any) are downloaded.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a new sbt setting key `schemaRegistrySubjectPatterns` that accepts a sequence of regex pattern strings.
- **FR-002**: System MUST fetch the complete subject list from Schema Registry when any pattern is configured.
- **FR-003**: System MUST match each pattern against the full subject list using full-match regex semantics (pattern must match the entire subject name, not a substring).
- **FR-004**: System MUST resolve pattern-matched subjects to their latest schema version.
- **FR-005**: System MUST combine explicitly listed subjects and pattern-matched subjects into a single download plan.
- **FR-006**: System MUST deduplicate subjects by name, giving precedence to explicitly listed subjects (preserving pinned versions over latest).
- **FR-007**: System MUST fail-fast with a clear error message when any configured pattern is not a valid regex. If multiple patterns are configured and one is invalid, the entire task fails — no partial execution.
- **FR-008**: System MUST log the number of resolved subjects after pattern matching completes.
- **FR-009**: System MUST remain fully backward compatible — existing configurations using only `schemaRegistrySubjects` must work identically without changes.
- **FR-010**: System MUST handle the case where pattern matching yields zero results without failing.

### Key Entities

- **SubjectSpec**: Represents a subject specification — either an exact subject reference (with optional pinned version) or a regex pattern to match against the registry.
- **DownloadPlan**: An ordered list of concrete subjects resolved from all specs (exact + pattern-matched), deduplicated and ready for download.
- **RegistrySubject**: Existing entity representing a subject name with an optional version. Pattern-matched subjects always use latest version.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can download schemas from 50+ subjects by configuring a single pattern instead of listing each subject individually.
- **SC-002**: Existing build configurations work without modification after the feature is added (zero breaking changes).
- **SC-003**: Pattern resolution completes within the same order of time as an equivalent explicit subject download (one additional network call for the subject list).
- **SC-004**: Invalid patterns produce an actionable error message within one line that identifies the problematic pattern.

## Assumptions

- Schema Registry exposes a `GET /subjects` endpoint (or equivalent client method) that returns all registered subject names. This is standard across Confluent Schema Registry versions.
- Pattern-matched subjects always resolve to the latest version. Pinning a version on a pattern-matched subject is not meaningful and is not supported.
- The number of subjects in the registry is manageable for client-side filtering (thousands, not millions). Server-side prefix filtering (`subjectPrefix`) is a potential optimization but not required for initial implementation.
- The existing `schemaRegistrySubjects` key and its semantics remain unchanged.
- Users are familiar with Java/Scala regex syntax for writing patterns.
