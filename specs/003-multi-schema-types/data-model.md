# Data Model: Multi-Schema Type Support

## Entities

### SchemaType (modified)

Sealed ADT representing supported schema formats.

| Field | Type | Description |
|-------|------|-------------|
| extension | String | Canonical file extension (`avsc`, `proto`, `json`) |
| registryLabel | String | Label used in Schema Registry API (`AVRO`, `PROTOBUF`, `JSON`) |

**Cases**: `Avro`, `Protobuf`, `Json`

**Constructors**:
- `fromExtension(ext: String): Either[RegistryError, SchemaType]` — already exists
- `fromRegistryLabel(label: String): Either[RegistryError, SchemaType]` — new

**Invariants**:
- Extension ↔ label mapping is 1:1
- `fromRegistryLabel(null)` and `fromRegistryLabel("AVRO")` both return `Right(Avro)` (null = legacy Avro)

### SchemaReference (new)

A pointer to another schema in the registry, used for Protobuf imports and JSON Schema `$ref`.

| Field | Type | Description |
|-------|------|-------------|
| name | String | Reference name (import path for Protobuf, `$ref` URI for JSON Schema) |
| subject | String | Registry subject where the referenced schema is registered |
| version | Int | Version of the referenced schema |

**Conversion**: Maps to `io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference(name, subject, version)` at the registration boundary.

### RegistryRegistration (modified)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| subject | String | — | Registry subject name |
| file | File | — | Path to schema file |
| schemaType | SchemaType | SchemaType.Avro | Schema format (inferred or explicit) |
| references | List[SchemaReference] | Nil | Schema references for imports/`$ref` |

**Backward compatibility**: Both new fields have defaults. Existing `RegistryRegistration("subj", file)` compiles unchanged.

### DownloadedSchema (implicit in Downloader)

Not a separate case class — represented by the `(version, body, schemaType)` tuple in `Downloader.fetchSchema`. The `schemaType` determines file extension via `SchemaType.extension`.

## Relationships

```
RegistryRegistration ──has-a──▶ SchemaType
                     ──has-many──▶ SchemaReference
SchemaType ◀──detected-by── Downloader (via fromRegistryLabel)
SchemaType ◀──inferred-by── fromExtension (at configuration time)
```

## State Transitions

None — all entities are immutable value objects. No lifecycle state.
