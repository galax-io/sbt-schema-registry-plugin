# Quickstart: Validate Schema Registration

## Prerequisites

- Docker running (for integration + scripted tests)
- sbt installed
- Project cloned and on feature branch

## Step 1: Compile and Run Unit Tests

```bash
sbt compile test
```

**Expected**: All existing tests pass. New `RegistrarSpec` tests pass —
verifying pure logic (file read, error partitioning) with mocked client.

## Step 2: Integration Test — Real Registry

```bash
sbt it/test
```

**Expected**: `RegistrarIntegrationSpec` starts Schema Registry via
Testcontainers, registers a schema, verifies schema ID is returned,
and optionally downloads to verify round-trip.

## Step 3: Scripted E2E Test

```bash
sbt scripted
```

**Expected**: New scripted test `register-success` (and optionally
`register-then-download`) passes — demonstrates full sbt task behavior
from `build.sbt` configuration through to registry interaction.

## Step 4: Manual Verification (optional)

With a local Schema Registry running:

```bash
# Start registry
docker compose -f it/docker-compose.yml up -d

# Run registration
sbt "set schemaRegistryRegistrations := Seq(
  org.galaxio.avro.RegistryRegistration(\"test-subject\", baseDirectory.value / \"src/sbt-test/schema-registry/download-success/expected.avsc\")
)" "Compile / schemaRegistryRegister"

# Verify via curl
curl http://localhost:8081/subjects/test-subject/versions

# Cleanup
docker compose -f it/docker-compose.yml down
```

**Expected output**:
```
[info] Registered test-subject → schema ID 1
```

## Validation Checklist

- [ ] Unit tests cover: file not found, read failure, successful registration, unsupported type
- [ ] Integration test registers against real registry
- [ ] Scripted test proves sbt task wiring works end-to-end
- [ ] Existing download tests still pass (no regression)
- [ ] `sbt scalafmtCheckAll compile test` green
