# Quickstart: Schema Compatibility Check

## Prerequisites

- Docker running (for integration and scripted tests)
- sbt 1.12.x
- Project compiles: `sbt compile`

## Scenario 1: Compatible Schema Change

1. Start with a registered schema (e.g., from `schemaRegistryRegister`)
2. Modify the schema by adding an optional field with a default
3. Run: `sbt "Compile / schemaRegistryTestCompatibility"`
4. **Expected**: Task succeeds, output shows `✓ <subject> is compatible`

## Scenario 2: Incompatible Schema Change

1. Start with a registered schema
2. Modify the schema by removing a required field
3. Run: `sbt "Compile / schemaRegistryTestCompatibility"`
4. **Expected**: Task fails, output shows `✗ <subject> is NOT compatible:` followed by specific incompatibility messages

## Scenario 3: CI Pipeline Gate

1. Configure `build.sbt`:
   ```sbt
   schemaRegistryRegistrations := Seq(
     RegistryRegistration("my-subject", baseDirectory.value / "src/main/avro/MySchema.avsc"),
   )
   ```
2. Run: `sbt schemaRegistryTestCompatibility schemaRegistryRegister`
3. **Expected**: Compatibility check runs first. If compatible, registration proceeds. If incompatible, build fails before registration.

## Scenario 4: No Prior Versions

1. Configure a registration for a subject never registered
2. Run: `sbt "Compile / schemaRegistryTestCompatibility"`
3. **Expected**: Task succeeds — first version is always compatible

## Validation Commands

```bash
sbt compile test              # Unit tests (mocked client)
sbt it/test                   # Integration tests (real registry via Testcontainers)
sbt scripted                  # e2e tests (compatibility-pass, compatibility-fail)
```
