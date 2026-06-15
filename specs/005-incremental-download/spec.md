# Feature Specification: Incremental Schema Download

**Feature Branch**: `feat/005-incremental-download`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "feat: incremental download — skip unchanged schemas (GitHub issue #29)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Skip Unchanged Schemas on Repeated Download (Priority: P1)

As a developer running `schemaRegistryDownload` repeatedly during local development,
I want schemas that haven't changed in the registry to be skipped automatically,
so that my build cycle is faster and I don't waste bandwidth re-downloading identical schemas.

**Why this priority**: This is the core value proposition. Every user benefits immediately — fewer network calls, faster builds, less noise in logs.

**Independent Test**: Run `schemaRegistryDownload` twice in succession with no registry changes. Second run should skip all subjects and complete significantly faster.

**Acceptance Scenarios**:

1. **Given** schemas were previously downloaded and a version manifest exists, **When** `schemaRegistryDownload` runs again with no registry changes, **Then** all subjects are skipped and logs show "up to date" for each.
2. **Given** schemas were previously downloaded, **When** one schema is updated in the registry, **Then** only the changed schema is re-downloaded and the rest are skipped.
3. **Given** a fresh project with no prior downloads (no manifest), **When** `schemaRegistryDownload` runs, **Then** all subjects are downloaded and a version manifest is created.

---

### User Story 2 - Incremental Download with Pinned Versions (Priority: P1)

As a developer using pinned schema versions in my configuration,
I want the plugin to skip download when the pinned version matches the locally cached version,
so that version-locked schemas are not re-fetched unnecessarily.

**Why this priority**: Pinned versions are a common pattern for reproducible builds. Skipping known-cached pinned versions is essential for correctness and performance parity.

**Independent Test**: Configure a subject with a specific version. Download once, then run again. Second run should skip without contacting the registry.

**Acceptance Scenarios**:

1. **Given** a subject is configured with `version = 3` and the manifest records version 3 for that subject, **When** `schemaRegistryDownload` runs, **Then** the subject is skipped without a registry call.
2. **Given** a subject is configured with `version = 4` but the manifest records version 3, **When** `schemaRegistryDownload` runs, **Then** the subject is downloaded and the manifest is updated to version 4.

---

### User Story 3 - Force Full Re-download (Priority: P2)

As a developer who suspects stale or corrupted local schemas,
I want a way to force a full re-download bypassing the cache,
so that I can recover from inconsistent state.

**Why this priority**: Escape hatch for when caching goes wrong. Less frequently used but critical for trust in the system.

**Independent Test**: Download schemas, then force re-download. All subjects should be downloaded regardless of manifest state.

**Acceptance Scenarios**:

1. **Given** a version manifest exists with cached versions, **When** the user runs `sbt clean schemaRegistryDownload`, **Then** the manifest is deleted and all subjects are downloaded fresh.
2. **Given** `schemaRegistryIncremental` is set to `false`, **When** `schemaRegistryDownload` runs, **Then** all subjects are downloaded regardless of manifest state.

---

### User Story 4 - Transparent Logging of Download Decisions (Priority: P2)

As a developer or CI operator reviewing build logs,
I want clear log output showing which schemas were downloaded and which were skipped (and why),
so that I can understand what happened and troubleshoot issues.

**Why this priority**: Observability builds trust in the incremental mechanism. Without clear logs, users cannot verify correctness.

**Independent Test**: Run download with a mix of changed and unchanged schemas. Logs should show skip/download decisions with reasons for each subject.

**Acceptance Scenarios**:

1. **Given** a mix of up-to-date and stale schemas, **When** `schemaRegistryDownload` runs, **Then** each subject's decision (skip or download) and reason appear in the logs.
2. **Given** download completes, **When** the user reviews the summary log line, **Then** it shows counts of downloaded and skipped schemas (e.g., "3 downloaded, 7 skipped").

---

### User Story 5 - Graceful Degradation on Version Check Failure (Priority: P3)

As a developer whose registry is intermittently unreachable,
I want the plugin to fall back to downloading a schema when its version check fails,
so that a transient error doesn't block my build.

**Why this priority**: Resilience matters in CI/CD environments with flaky networks. A failed version check should not break the build.

**Independent Test**: Simulate a version check failure for one subject. That subject should be downloaded; other subjects should still benefit from caching.

**Acceptance Scenarios**:

1. **Given** the registry version check for a subject fails, **When** `schemaRegistryDownload` runs, **Then** the subject is downloaded (fallback) and the log shows the reason ("version check failed, re-downloading").
2. **Given** the registry is fully unreachable, **When** `schemaRegistryDownload` runs, **Then** all subjects are attempted for download (same behavior as non-incremental mode) and normal download error handling applies.

---

### Edge Cases

- What happens when the manifest file is corrupted or contains invalid JSON? The plugin should treat it as empty (fresh download) and log a warning.
- What happens when a subject exists in the manifest but is no longer in the configured subjects list? The manifest entry is stale but harmless; it is ignored. No cleanup is performed.
- What happens when the `cacheDirectory` is deleted between runs but schema output files still exist? The manifest is recreated from scratch; all subjects are re-downloaded.
- What happens during concurrent sbt sessions writing to the same manifest? The last writer wins. This is acceptable because sbt tasks run sequentially within a session, and concurrent sbt sessions on the same project are an unsupported edge case.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST maintain a version manifest that records the last downloaded version number for each subject.
- **FR-002**: System MUST persist the version manifest as a JSON file in sbt's cache directory so that `sbt clean` automatically removes it.
- **FR-003**: System MUST compare each configured subject's expected version against the manifest before downloading.
- **FR-004**: For subjects configured with a pinned version, system MUST skip download when the manifest records a matching version — without contacting the registry.
- **FR-005**: For subjects configured without a pinned version (latest), system MUST query the registry for the current version and skip download when it matches the manifest.
- **FR-006**: System MUST download a subject and update the manifest when the version has changed or the subject is not in the manifest.
- **FR-007**: System MUST fall back to downloading when a version check against the registry fails.
- **FR-008**: System MUST provide an `schemaRegistryIncremental` sbt setting (defaulting to `true`) that, when set to `false`, bypasses all caching and downloads every subject.
- **FR-009**: System MUST log each download decision (skip or download) with a human-readable reason.
- **FR-010**: System MUST log a summary line after completion showing counts of downloaded and skipped subjects.
- **FR-011**: System MUST treat a missing or unparseable manifest file as empty (triggering full download) and log a warning if the file existed but was unreadable.
- **FR-012**: The download decision logic MUST be a pure function that can be tested without a live registry connection.

### Key Entities

- **VersionManifest**: An immutable record mapping subject names to their last-downloaded version numbers. Supports JSON serialization and deserialization.
- **DownloadDecision**: A decision for a single subject — either "Download" (with subject reference and reason string) or "Skip" (with subject name and cached version number). Represents the output of the planning phase.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Repeated builds with no registry changes complete the download task with zero network requests to the registry for schema content (only lightweight version checks for non-pinned subjects).
- **SC-002**: Users can force a full re-download within one command (`sbt clean schemaRegistryDownload`) without manual file deletion.
- **SC-003**: Every download decision is visible in build logs, enabling users to verify correctness without inspecting internal state.
- **SC-004**: Existing users who upgrade experience no change in behavior until they opt in — the feature defaults to enabled but produces identical results on first run (downloads everything, creates manifest).
- **SC-005**: The download decision logic is fully testable with unit tests that require no external services.

## Assumptions

- The registry's version number for a subject increases monotonically — a matching version number implies identical schema content.
- sbt's `cacheDirectory` is a reliable location for per-task persistent state and is cleared by `sbt clean`.
- Concurrent sbt sessions targeting the same project and cache directory are not a supported scenario.
- The existing `schemaRegistryDownload` task structure allows insertion of a planning phase before the download loop without breaking backward compatibility.
- Wildcard subject expansion (feature #004) resolves subjects before the incremental check — the incremental planner receives already-resolved subjects.
