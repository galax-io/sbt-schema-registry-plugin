# sbt Public API Contract: Schema Registration

## New Setting Keys

### `schemaRegistryRegistrations`

```
Type:    SettingKey[Seq[RegistryRegistration]]
Scope:   Global (project-level)
Default: Seq.empty
```

User configures subject-to-file mappings:

```sbt
schemaRegistryRegistrations := Seq(
  RegistryRegistration("user-value", baseDirectory.value / "src/main/avro/User.avsc"),
  RegistryRegistration("order-value", baseDirectory.value / "src/main/avro/Order.avsc", SchemaType.Protobuf),
)
```

## New Task Keys

### `schemaRegistryRegister`

```
Type:    TaskKey[Unit]
Scope:   Compile (default), available at project level via delegation
Trigger: Manual — `sbt schemaRegistryRegister` or `sbt "Compile / schemaRegistryRegister"`
```

**Behavior**:
1. If `schemaRegistryRegistrations` is empty → warn and return
2. Build client using existing `schemaRegistryUrl`, `schemaRegistryAuth`, `schemaRegistryProperties`
3. For each registration: read file, construct `ParsedSchema`, call `client.register`
4. Log each success: `"Registered {subject} → schema ID {id}"`
5. Collect all errors, log each: `"Failed to register {subject}: {message}"`
6. If any errors → `sys.error("N registration(s) failed")`

## Reused Existing Keys (no changes)

| Key                        | Used for                    |
|----------------------------|-----------------------------|
| `schemaRegistryUrl`        | Registry endpoint           |
| `schemaRegistryAuth`       | Credentials                 |
| `schemaRegistryProperties` | SSL/timeout/custom config   |
| `schemaRegistryCacheSize`  | Client cache size           |

## New Public Types

| Type                    | Package            | Visibility |
|-------------------------|--------------------|------------|
| `SchemaType`            | `org.galaxio.avro` | Public     |
| `RegistryRegistration`  | `org.galaxio.avro` | Public     |
| `RegisteredSchema`      | `org.galaxio.avro` | Public     |
| `RegistryError`         | `org.galaxio.avro` | Public     |
| `Registrar`             | `org.galaxio.avro` | Public     |

## Backward Compatibility

- No existing keys changed
- No existing behavior modified
- New keys are additive only
- `Downloader` and `SchemaDownloaderPlugin` untouched
