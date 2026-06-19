# Quickstart: Auto-Download Schema References

Validation guide proving feature 007 end-to-end. Details live in
[data-model.md](data-model.md), [contracts/](contracts/), [research.md](research.md).

## Prerequisites

- JDK 17+, sbt 1.12.x
- Docker (for `it/test` Testcontainers and the `scripted` registry fixture)
- Repo builds clean today: `sbt compile`

## User-facing usage

In a consuming `build.sbt`:

```scala
schemaRegistryUrl     := "http://localhost:8081"
schemaRegistrySubjects := Seq(RegistrySubject.latest("order-value"))
// schemaRegistryResolveReferences := true   // default — referenced schemas auto-downloaded
```

Run `sbt "Compile / schemaRegistryDownload"`. If `order-value` references `customer-value`,
both `order-value-<v>.avsc` and `customer-value-<v>.avsc` appear in
`schemaRegistryTargetFolder`. Set `schemaRegistryResolveReferences := false` to download only
the requested subjects (prior behavior).

## Validation scenarios

### 1. Unit — pure resolver (fast, no Docker)

```bash
sbt "testOnly org.galaxio.avro.ReferenceResolverSpec"
```

**Expected**: all cases green — transitive `A→B→C` (RR-2), cycle `A↔B` terminates (RR-3),
shared dep once (RR-4), divergent diamond keeps both `B@1`+`B@2` (RR-5), fail-fast (RR-6),
deep-chain no SOE (RR-7), latest/pinned reconciliation (RR-8), fetch-count dedup proof (RR-9).
See [contracts/reference-resolver.md](contracts/reference-resolver.md).

### 2. Scripted — plugin e2e (Docker)

```bash
sbt "scripted schema-registry/resolve-references"
```

**Expected**: registers a base + dependent-with-reference schema, runs the download task, and
the script asserts (`$ exists` / `$ must-mirror`) that **both** files are on disk (PS-1). The
opt-out path asserts the referenced file is absent when
`schemaRegistryResolveReferences := false` (PS-2).

### 3. Integration — real registry (Docker)

```bash
sbt "it/testOnly org.galaxio.avro.ReferenceResolutionIntegrationSpec"
```

**Expected**: Testcontainers Kafka + Schema Registry; a dependent schema with a reference is
registered; resolve+download produces both files with correct content; a missing reference
fails fast with `SchemaFetchFailed` (PS-4).

### 4. Backward-compat regression

```bash
sbt "scripted schema-registry/download-success"   # existing test, must stay green
```

**Expected**: unchanged — a subject with no references still yields exactly one file
(acceptance 1.3 / SC-004).

## Full gate (Constitution V)

```bash
sbt scalafmtCheckAll scalafmtSbtCheck "compile" "test"
sbt "it/test"        # Docker
sbt scripted         # Docker
```

All must pass under `-Xfatal-warnings`.

## Done signals (map to spec)

| Check | Spec |
|-------|------|
| Transitive files on disk in one run | SC-001, FR-002 |
| Referenced files at pinned version | SC-002, FR-003 |
| Cycle/shared/divergent terminate, each subject+version once | SC-003, FR-004/FR-005 |
| `:= false` ⇒ byte-identical to prior | SC-004, FR-007 |
| Missing reference ⇒ named error | SC-005, FR-008 |
| Composes with wildcard/incremental/parallel | FR-009 |
