# Feature Specification: List Subjects Task

**Feature Branch**: `008-list-subjects-task`

**Created**: 2026-06-20

**Status**: Draft

**Input**: GitHub issue #32 — "feat: list subjects task for discovery and debugging". A `schemaRegistryListSubjects` sbt task that lists registry subjects (with versions and compatibility) directly from sbt, so developers can discover and debug what exists in the registry without reaching for `curl` or a separate UI.

## Clarifications

### Session 2026-06-20

- Q: Filter matching semantics — substring case-sensitive, substring case-insensitive, or full-match regex? → A: Substring, case-insensitive (subject name contains the filter text, ignoring case).
- Q: When a subject's versions can't be fetched after it appeared in the subject list — fail-fast or partial results? → A: Fail-fast — the whole task fails with a message naming the affected subject (version fetch is fatal; compatibility lookup stays best-effort).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Discover All Subjects from sbt (Priority: P1)

A plugin user is configuring schema downloads but does not remember which subjects exist in the registry. Instead of running `curl http://localhost:8081/subjects` or opening a separate UI, they run a single sbt task. The task connects to the registry using the build's existing connection settings, fetches every subject with its available versions and compatibility level, and prints a readable, sorted listing to the sbt log.

**Why this priority**: Core value proposition. Discovery directly from the build tool — using the connection settings already configured — is the whole point of the feature. Without it there is nothing to filter or inspect.

**Independent Test**: Register several subjects in a real Schema Registry, run the list task, and verify the log shows every registered subject with its version range and compatibility level.

**Acceptance Scenarios**:

1. **Given** the registry contains subjects `["user-value", "order-value", "payment-value"]`, **When** the user runs the list task, **Then** the log reports the total subject count and lists all three subjects sorted by name, each with its version range and compatibility level.
2. **Given** a subject has multiple registered versions (e.g. versions 1 through 5), **When** the user runs the list task, **Then** that subject's entry shows its version range (e.g. `1..5`).
3. **Given** a subject has only one registered version, **When** the user runs the list task, **Then** that subject's entry shows the single version (e.g. `1`), not a range.
4. **Given** the registry contains no subjects, **When** the user runs the list task, **Then** the task completes successfully and reports that zero subjects were found.

---

### User Story 2 - Filter Subjects by Pattern (Priority: P2)

A user works against a registry with many subjects and only cares about a subset (e.g. everything related to `order`). They set a filter and run the list task. Only subjects whose names contain the filter text are listed; the reported count reflects the filtered result.

**Why this priority**: Important for usability on large registries, but builds on P1 — the unfiltered listing is already useful on its own. Filtering is a refinement, not the foundation.

**Independent Test**: Register subjects across several name groups, set a filter that matches only one group, run the list task, and verify only matching subjects appear and the count matches.

**Acceptance Scenarios**:

1. **Given** the registry contains `["user-value", "order-value", "payment-value"]` and the filter is set to `order`, **When** the user runs the list task, **Then** only `order-value` is listed and the reported count is 1.
2. **Given** a filter is set that matches no subject names, **When** the user runs the list task, **Then** the task completes successfully and reports that zero subjects matched.
3. **Given** no filter is set, **When** the user runs the list task, **Then** all subjects are listed (filtering is inactive by default).

---

### User Story 3 - Inspect Versions and Compatibility for Debugging (Priority: P3)

A user is debugging a compatibility problem and wants to see, per subject, how many versions exist and what compatibility level governs evolution. The listing surfaces the version range and the compatibility level for each subject; when a subject has no subject-level compatibility override, the listing makes clear the global default applies.

**Why this priority**: A debugging convenience layered on the listing from P1. Valuable, but the raw subject/version information already covers the most common discovery need.

**Independent Test**: Register a subject with a subject-level compatibility override and another without one, run the list task, and verify the first shows its explicit level while the second is shown as using the default.

**Acceptance Scenarios**:

1. **Given** a subject has a subject-level compatibility setting (e.g. `BACKWARD`), **When** the user runs the list task, **Then** that subject's entry shows `BACKWARD`.
2. **Given** a subject has no subject-level compatibility override, **When** the user runs the list task, **Then** that subject's entry indicates the default (global) compatibility applies rather than failing.

---

### Edge Cases

- **Registry unreachable**: When the registry cannot be reached while fetching the subject list, the task fails with a clear connection error rather than printing a partial or empty listing as if it succeeded.
- **Subject disappears mid-listing**: When a subject is returned by the subject list but its versions can no longer be fetched (e.g. deleted between calls), the task fails with a clear error identifying the affected subject. (See Assumptions for the fail-fast rationale.)
- **Compatibility lookup fails or is absent**: A missing or unreadable subject-level compatibility setting does not fail the task; the subject is shown as using the default.
- **Empty registry**: A registry with zero subjects completes successfully and reports zero subjects.
- **Empty filter value**: A filter set to an empty string is treated as "match everything" (equivalent to no filter), not "match nothing".
- **Large registry**: A registry with hundreds of subjects lists them all; the user is responsible for narrowing with a filter. The reported count communicates the scale.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a new sbt task `schemaRegistryListSubjects` that lists registry subjects to the sbt log.
- **FR-002**: System MUST reuse the build's existing registry connection settings (URL, authentication, client properties) — the list task introduces no new connection configuration.
- **FR-003**: System MUST fetch the complete set of subjects from the registry and present them sorted by subject name.
- **FR-004**: System MUST report, for each subject, its available version range — a single value when one version exists, and a `first..last` range when multiple versions exist.
- **FR-005**: System MUST report, for each subject, its compatibility level, clearly indicating when the subject uses the default (global) compatibility rather than a subject-level override.
- **FR-006**: System MUST report the total number of subjects listed (after any filtering is applied).
- **FR-007**: System MUST provide an optional filter setting that, when set, restricts the listing to subjects whose names contain the filter text (case-insensitive substring match).
- **FR-008**: System MUST treat an unset (or empty) filter as "list all subjects".
- **FR-009**: System MUST keep data retrieval and transformation separate from presentation — fetching and shaping the listing yields a result value, and all formatting and logging happen only in the sbt task layer. (Constitution Principle II: Single Responsibility.)
- **FR-010**: System MUST surface retrieval failures as a typed error result rather than throwing from the core listing logic; the sbt task layer translates that error into a clear task failure message.
- **FR-011**: System MUST fail the task with a clear, actionable message when the subject list cannot be fetched (e.g. registry unreachable).
- **FR-012**: System MUST NOT fail the listing solely because a subject's compatibility level cannot be read; such subjects are reported as using the default compatibility.
- **FR-013**: System MUST remain fully backward compatible — adding the list task and its optional filter setting MUST NOT change any existing key, task, or download behavior.

### Key Entities

- **SubjectInfo**: An immutable value describing one subject — its name, the list of available versions, and an optional compatibility level. Derived values include the latest version and a human-readable version range.
- **SubjectListing**: An immutable collection of `SubjectInfo` values representing the result of a list operation. Supports a pure filter transformation that narrows the listing by a name pattern.
- **DownloadError**: A typed representation of a retrieval failure (e.g. failure to fetch the subject list or a subject's versions), carrying enough context (which subject/operation) to produce an actionable message. Reuses the project's existing error-modeling approach — the plugin's existing `DownloadError` sealed type, which already models registry-read failures (`SubjectListFailed`) plus a new per-subject `SubjectVersionsFetchFailed` case. (Issue #32 sketched a `RegistryError.FetchFailed`; that case does not exist and `DownloadError` is the correct existing model — see plan research D1.)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can list every subject in a registry, with versions and compatibility, by running a single sbt task — no `curl`, no separate UI, no extra connection configuration.
- **SC-002**: A user can narrow the listing to a subset of subjects by setting one filter value, and the reported count reflects only matching subjects.
- **SC-003**: Each listed subject shows its version range and compatibility in a single, scannable line.
- **SC-004**: When the registry is unreachable, the user gets an actionable failure message instead of an empty or misleading listing.
- **SC-005**: Existing builds that do not use the list task are completely unaffected (zero breaking changes to existing keys, tasks, or download behavior).

## Assumptions

- **Substring filter semantics**: The filter matches subjects whose names *contain* the filter text (case-insensitive substring). Issue #32's reference code used a case-sensitive `.*pattern.*`; case-insensitivity was chosen during clarification because listing is an interactive discovery aid where casing mistakes should not hide results. This differs deliberately from the full-match, case-sensitive regex semantics of the wildcard-download feature (spec 004).
- **Fail-fast on retrieval errors** (confirmed during clarification): If any subject's versions cannot be fetched after that subject appeared in the subject list, the entire task fails rather than emitting a partial listing. This keeps the listing trustworthy (a partial list could mislead discovery) and matches the error-accumulation behavior described in issue #32. Partial-results mode (continue past per-subject failures) is explicitly out of scope for this version.
- **Compatibility is best-effort**: A subject-level compatibility lookup may legitimately be absent (no override) or fail; either case is reported as "default" and never fails the task. Only subject-list and version retrieval are treated as hard failures.
- **Read-only**: The task only reads from the registry. It never registers, deletes, or mutates subjects, versions, or configuration.
- **Output destination**: Results are presented through the standard sbt log (info level), consistent with other plugin tasks. Machine-readable output formats (JSON, file export) are out of scope for this version.
- **Connection reuse**: The registry endpoint exposes the standard subject-list, subject-versions, and subject-compatibility operations available through the existing registry client; no new endpoints or client configuration are required.
- **Scale**: The registry holds a client-manageable number of subjects (thousands, not millions); the full list is fetched and filtered client-side.
