# Feature Specification: Auto-Download Schema References

**Feature Branch**: `007-resolve-schema-references`

**Created**: 2026-06-19

**Status**: Draft

**Input**: GitHub issue [#31](https://github.com/galax-io/sbt-schema-registry-plugin/issues/31): "feat: auto-download schema references"

## Clarifications

### Session 2026-06-19

- Q: Dedup / identity key for resolved schemas (cycle protection + write-once)? → A: Key by **subject + version**. Distinct pinned versions of the same subject are all downloaded and written as separate files (`customer-value-1.avsc`, `customer-value-2.avsc`); cycle protection skips a re-visit of the same subject+version. This supersedes issue #31's `Set[String]` (subject-only) sketch, which predates the version-in-filename convention.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Transitive reference download (Priority: P1)

A developer downloads a schema (e.g., `order-value`) that references another schema
(e.g., `customer-value`). The plugin automatically detects the reference, downloads the
referenced schema too, and continues transitively until every dependency is on disk.
The developer ends up with a complete, self-contained set of schema files without having
to manually list each dependency.

**Why this priority**: This is the core value of the feature. Without transitive
resolution, a downloaded schema that depends on others is unusable — consumers cannot
deserialize messages because the referenced types are missing. This single story
delivers the entire MVP.

**Independent Test**: Register a root schema with a reference to a second schema, run the
download task for the root subject only, and verify both schema files appear on disk with
correct content.

**Acceptance Scenarios**:

1. **Given** subject `order-value` references `customer-value`, **When** the developer
   downloads `order-value`, **Then** both `order-value` and `customer-value` are written
   to disk.
2. **Given** a chain `A → B → C` where A references B and B references C, **When** the
   developer downloads A, **Then** all three schemas (A, B, C) are written to disk.
3. **Given** a subject with no references, **When** the developer downloads it, **Then**
   only that one schema is written and behavior is identical to today.

---

### User Story 2 - Opt-out of reference resolution (Priority: P2)

A developer wants to download only the explicitly requested subjects without pulling in
referenced schemas (for example, when references are managed separately or already
vendored). They disable reference resolution via a setting and the plugin downloads only
the requested subjects.

**Why this priority**: Auto-resolution is the right default, but some workflows need the
previous behavior. Providing an explicit toggle preserves backward compatibility and user
control. Secondary to the core resolution capability.

**Independent Test**: Set the resolve-references toggle to off, download a subject that has
references, and verify only the requested subject is written and no referenced schemas are
fetched.

**Acceptance Scenarios**:

1. **Given** reference resolution is disabled, **When** the developer downloads a subject
   with references, **Then** only the requested subject is written to disk.
2. **Given** reference resolution is enabled (the default), **When** the developer
   downloads a subject with references, **Then** referenced schemas are written too.

---

### User Story 3 - Safe handling of cyclic and shared references (Priority: P3)

A developer downloads schemas whose reference graph contains cycles (A references B and B
references A) or shared dependencies (both A and B reference C). The plugin completes
without looping forever and without downloading or writing the same schema twice.

**Why this priority**: Cycles and shared dependencies are real in mature schema
ecosystems. Correctness here prevents hangs and duplicate work, but it is a robustness
concern layered on top of the core resolution capability.

**Independent Test**: Construct a reference graph with a cycle and a shared dependency,
run download, and verify resolution terminates and each schema is written exactly once.

**Acceptance Scenarios**:

1. **Given** a cyclic reference `A → B → A`, **When** the developer downloads A, **Then**
   resolution terminates and both A and B are written exactly once.
2. **Given** a shared dependency where A and B both reference C at the same version,
   **When** the developer downloads A and B, **Then** C is written exactly once.
3. **Given** a diamond with divergent versions where A references B@1 and A→C→B@2, **When**
   the developer downloads A, **Then** both B@1 and B@2 are written as separate files
   (identity is subject+version, not subject alone).

---

### Edge Cases

- **Pinned version mismatch**: A referenced schema is requested at a specific (pinned)
  version that differs from the latest. The exact referenced version MUST be downloaded,
  not the latest.
- **Reference fetch failure**: A referenced schema cannot be fetched (deleted, permission
  denied, registry error). Resolution stops and reports the failing subject; the developer
  sees a clear error rather than a partial silent result.
- **Deep reference chains**: A long chain of references (many levels deep) MUST resolve
  without exhausting resources or failing on depth.
- **Reference points back to an already-downloaded root**: A referenced subject+version is
  also one of the explicitly requested roots (same version). It MUST be written exactly
  once. If the reference pins a *different* version than the root, both versions are
  written (each is a distinct subject+version identity).
- **Reference resolution combined with wildcard/incremental/parallel download**: Resolution
  composes with existing download modes (wildcard subject selection, incremental skip,
  parallel downloads) without conflict.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST detect schema references reported by the registry for each
  downloaded subject.
- **FR-002**: System MUST transitively download every referenced schema, following
  references to arbitrary depth.
- **FR-003**: System MUST download each referenced schema at the exact version recorded in
  the reference (pinned version), not the latest version.
- **FR-004**: System MUST prevent infinite loops when the reference graph contains cycles,
  by tracking visited schemas keyed by **subject + version**.
- **FR-005**: Within a single download run, System MUST write each resolved schema
  (identified by subject + version) to disk exactly once, even when it is referenced by
  multiple subjects or is also an explicitly requested root. Distinct pinned versions of the
  same subject are written as separate files and MUST NOT be deduplicated against each other.
  (Cross-run re-writes of identical bytes under incremental download are a known, acceptable
  redundancy — never a duplicate within one run.)
- **FR-006**: System MUST provide a configuration setting that enables or disables
  automatic reference resolution, defaulting to enabled.
- **FR-007**: When reference resolution is disabled, System MUST download only the
  explicitly requested subjects, matching the behavior prior to this feature.
- **FR-008**: System MUST report a clear error identifying the failing subject when a
  referenced schema cannot be fetched, and MUST NOT silently omit it.
- **FR-009**: Reference resolution MUST compose with existing download capabilities
  (wildcard subject selection, incremental download, parallel download).
- **FR-010**: Root (explicitly requested) schemas MUST appear in deterministic order in
  the resolution output, ahead of their transitively discovered references.

### Key Entities *(include if feature involves data)*

- **Schema Reference**: A pointer from one schema to another. Has a logical name, the
  target subject, and the exact target version.
- **Resolved Schema**: A schema that has been fetched, including its subject, version,
  content, and the list of references it declares.
- **Resolution Result**: The complete set of schemas produced by resolving all roots and
  their transitive references, plus the bookkeeping of which subject+version pairs have
  already been visited (to guarantee single-write per subject+version and cycle safety).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Downloading a single root subject with a dependency chain of depth N
  produces all N+1 schema files on disk in one task run, with no manual listing of
  dependencies.
- **SC-002**: 100% of referenced schemas are written at their pinned version (zero version
  drift between the declared reference version and the file written).
- **SC-003**: Within a single run, resolution of any reference graph — including cyclic,
  shared-dependency, and divergent-version-diamond graphs — terminates and writes each
  distinct subject+version exactly once (zero duplicate files, zero hangs, zero
  silently-dropped versions).
- **SC-004**: With resolution disabled, download output is byte-for-byte equivalent to the
  pre-feature behavior for the same inputs (full backward compatibility).
- **SC-005**: A reference fetch failure surfaces an error naming the failing subject in
  100% of failure cases (no silent partial success).

## Assumptions

- The registry exposes per-subject/version reference metadata (logical name, target
  subject, target version) that can be queried for any fetched schema. This matches the
  Confluent Schema Registry behavior described in issue #31.
- References are addressed by subject + version; the referenced version is always pinned
  and available from the reference metadata, so no "latest" lookup is needed for
  referenced schemas.
- Auto-resolution enabled-by-default is the desired behavior; existing users who download
  schemas with references will now also receive the referenced schemas. This is treated as
  an additive enhancement, with the opt-out setting available for the prior behavior.
- Fail-fast on the first reference fetch error is the desired default. Partial/accumulated
  results (continue past failures) are out of scope for this feature.
- Referenced schemas are written to disk using the same file layout and naming convention
  as directly downloaded subjects (no separate directory or naming scheme for references).
