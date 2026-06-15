# Quickstart: Wildcard Subject Download

**Feature**: 004-wildcard-subject-download | **Date**: 2026-06-15

## Prerequisites

- Docker running (for integration tests with Testcontainers)
- sbt 1.12.x
- Java 17+

## Validation Scenarios

### 1. Unit Tests — SubjectResolver Logic

```bash
sbt test
```

**Expected**: All existing tests pass + new tests for:
- `SubjectResolverSpec`: pattern matching, deduplication, fail-fast on invalid regex
- `SubjectSpecSpec`: ADT construction
- `DownloadPlanSpec`: deduplication ordering

### 2. Integration Tests — Real Schema Registry

```bash
sbt it/test
```

**Expected**: New `SubjectResolverIntegrationSpec` passes:
- Register 3+ subjects with distinct names
- Resolve a pattern matching 2 of them
- Verify only matched subjects returned in `DownloadPlan`
- Verify exact subjects take precedence over pattern matches

### 3. End-to-End — sbt Scripted Test

```bash
sbt scripted schema-registry/download-wildcard
```

**Expected**: Scripted test configures patterns, runs `schemaRegistryDownload`, verifies correct schema files on disk.

### 4. Manual Validation (against local Schema Registry)

```bash
# Start local Schema Registry
docker compose up -d

# In build.sbt:
# schemaRegistryUrl := "http://localhost:8081"
# schemaRegistrySubjectPatterns += "com\\.myorg\\..*-value"

sbt schemaRegistryDownload
```

**Expected output**:
```
[info] Resolved 3 subjects from patterns
[info] Downloading schema com.myorg.User-value version=latest
[info] Saved schema com.myorg.User-value to src/main/avro/com.myorg.User-value-5.avsc
...
```

### 5. Backward Compatibility Check

```bash
# Remove all schemaRegistrySubjectPatterns from build.sbt
# Keep only schemaRegistrySubjects
sbt schemaRegistryDownload
```

**Expected**: Behavior identical to current plugin. No subject listing call made. No regressions.

## Verification Checklist

- [ ] `sbt scalafmtCheckAll scalafmtSbtCheck compile test` passes
- [ ] `sbt it/test` passes (requires Docker)
- [ ] `sbt scripted` passes
- [ ] No changes to existing sbt key types or defaults
- [ ] Pattern with zero matches logs warning, doesn't error
- [ ] Invalid regex pattern fails task immediately
- [ ] Explicit subject at pinned version wins over pattern match
