# Data Model: Register (Push) Schemas to Registry

## Entities

### SchemaType

Sealed ADT representing the schema format.

| Case       | Extension | ParsedSchema class  | Dependency                   |
|------------|-----------|---------------------|------------------------------|
| `Avro`     | `avsc`    | `AvroSchema`        | (already on classpath)       |
| `Protobuf` | `proto`   | `ProtobufSchema`    | `kafka-protobuf-provider`    |
| `Json`     | `json`    | `JsonSchema`        | `kafka-json-schema-provider` |

**Companion**: `SchemaType.fromExtension(ext): Either[RegistryError, SchemaType]`

### RegistryRegistration

Input model — user-facing configuration in `build.sbt`.

| Field        | Type         | Default          | Description                     |
|--------------|--------------|------------------|---------------------------------|
| `subject`    | `String`     | (required)       | Registry subject name           |
| `file`       | `File`       | (required)       | Path to local schema file       |
| `schemaType` | `SchemaType` | `SchemaType.Avro`| Schema format                   |

### RegisteredSchema

Output model — result of successful registration.

| Field      | Type     | Description                           |
|------------|----------|---------------------------------------|
| `subject`  | `String` | Subject name registered under         |
| `schemaId` | `Int`    | Schema ID assigned by registry        |

### RegistryError

Sealed ADT — typed error cases.

| Case                    | Fields                          | Message pattern                          |
|-------------------------|---------------------------------|------------------------------------------|
| `FileNotFound`          | `path: File`                    | "Schema file not found: {path}"          |
| `FileReadFailed`        | `path: File, cause: Throwable`  | "Failed to read schema file: {path}"     |
| `RegistrationFailed`    | `subject: String, cause: Throwable` | "Failed to register {subject}: {msg}" |
| `UnsupportedSchemaType` | `ext: String`                   | "Unsupported schema file extension: {ext}" |

## Relationships

```
RegistryRegistration --[input to]--> Registrar.registerAll
Registrar.registerAll --[produces]--> List[Either[RegistryError, RegisteredSchema]]
SchemaType --[determines]--> which ParsedSchema subtype to construct
```

## Validation Rules

- `subject` must not be empty
- `file` must exist and be readable at task execution time
- `schemaType` defaults to Avro if not specified
- File content must parse as valid schema of the declared type (registry validates)

## State Transitions

None — registration is a one-shot operation. No persistent state managed by
the plugin (the registry is the source of truth).
