# Quickstart Validation: Multi-Schema Type Support

## Prerequisites

- Docker running (for integration and scripted tests)
- JDK 17+
- sbt 1.12.x

## Validation Scenarios

### 1. Unit tests — SchemaType enrichment

Validates `fromExtension` and `fromRegistryLabel` constructors, extension/label fields.

```bash
sbt test
```

**Expected**: All `SchemaTypeSpec` tests pass, including new `fromRegistryLabel` tests.

### 2. Unit tests — Registrar with references

Validates `buildParsedSchema` constructs schemas with references, and `registerAll` passes references to client.

```bash
sbt test
```

**Expected**: `RegistrarSpec` tests pass for Protobuf/JSON with and without references.

### 3. Unit tests — Downloader with correct extensions

Validates downloaded files get correct extension based on schema type from registry metadata.

```bash
sbt test
```

**Expected**: `DownloaderSpec` tests pass for `.avsc`, `.proto`, `.json` extensions.

### 4. Integration tests — full register/download/compat cycle

Validates against real Schema Registry (Testcontainers). Tests all three schema types end-to-end.

```bash
sbt it/test
```

**Expected**: All integration specs pass. Protobuf and JSON Schema schemas register, download with correct extensions, and compatibility checks work.

### 5. Integration tests — schema references

Validates registering a schema with references to another registered schema.

```bash
sbt it/test
```

**Expected**: `RegistrarIntegrationSpec` reference tests pass — base schema registered first, dependent schema registered with references.

### 6. Scripted tests — sbt plugin behavior

Validates plugin tasks work from user's perspective in sbt projects.

```bash
sbt scripted
```

**Expected**: New scripted tests pass:
- `register-protobuf` — registers `.proto` file
- `register-json` — registers `.json` file
- `download-multi-type` — downloads schemas with correct extensions
- `register-references` — registers schema with references

### 7. Backward compatibility — existing scripted tests

Validates no regression for Avro-only workflows.

```bash
sbt scripted
```

**Expected**: All 16 existing scripted tests pass unchanged.

### 8. Error handling — missing dependency

Validates actionable error when Protobuf/JSON serializer lib is missing.

```bash
sbt test
```

**Expected**: `RegistrarSpec` test for missing classpath dependency produces error message containing the class name and guidance to add the dependency.

## Full validation command

```bash
sbt scalafmtCheckAll scalafmtSbtCheck compile test it/test scripted
```
