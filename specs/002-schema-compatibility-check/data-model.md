# Data Model: Schema Compatibility Check

## Entities

### CompatibilityResult (sealed ADT)

Represents the outcome of checking one subject's schema against the registry.

| Variant | Fields | Description |
|---------|--------|-------------|
| `Compatible` | `subject: String` | Schema is compatible with the registered version |
| `Incompatible` | `subject: String`, `messages: List[String]` | Schema is NOT compatible; messages explain what broke |
| `Failed` | `subject: String`, `cause: RegistryError` | Check could not complete (file error, network error, parse error) |

**Invariants**:
- `Incompatible.messages` is always non-empty
- `Failed.cause` is one of the existing `RegistryError` cases (`FileNotFound`, `FileReadFailed`, `RegistrationFailed`)

### CompatibilityReport (aggregate)

Aggregates all individual check results for a batch of subjects.

| Field / Accessor | Type | Description |
|------------------|------|-------------|
| `results` | `List[CompatibilityResult]` | All individual results |
| `compatible` | `List[Compatible]` | Partition: subjects that passed |
| `incompatible` | `List[Incompatible]` | Partition: subjects that failed compatibility |
| `failed` | `List[Failed]` | Partition: subjects where check errored |
| `isSuccess` | `Boolean` | `true` iff `incompatible` and `failed` are both empty |

**Invariants**:
- `results.size == compatible.size + incompatible.size + failed.size`
- Empty `results` → `isSuccess == true` (vacuous truth)

## Relationships

```
RegistryRegistration (existing) ──> CompatibilityChecker.checkOne ──> CompatibilityResult
     │                                                                        │
     │ subject, file, schemaType                                              │
     │                                                                        ▼
     └──────── List[RegistryRegistration] ──> checkAll ──> CompatibilityReport
```

## Reused Entities (from registration feature)

- `RegistryRegistration` — input: subject + file + schema type
- `RegistryError` — failure cases for file/parse/network errors
- `SchemaType` — Avro, Protobuf, Json
