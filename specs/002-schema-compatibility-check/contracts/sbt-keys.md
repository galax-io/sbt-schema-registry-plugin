# sbt Keys Contract: Schema Compatibility Check

## New Task Key

| Key | Type | Scope | Description |
|-----|------|-------|-------------|
| `schemaRegistryTestCompatibility` | `taskKey[Unit]` | `Compile` | Check local schemas against registry compatibility rules. Fails build if any schema is incompatible. |

## Reused Setting Keys (no new settings)

| Key | Used For |
|-----|----------|
| `schemaRegistryRegistrations` | Subject-to-file mappings to check |
| `schemaRegistryUrl` | Registry endpoint |
| `schemaRegistryAuth` | Authentication |
| `schemaRegistryProperties` | Extra client properties |
| `schemaRegistryCacheSize` | Client cache size |

## Task Behavior

### Success Output

```
[info] ✓ user-value is compatible
[info] ✓ order-value is compatible
```

### Failure Output

```
[info] ✓ user-value is compatible
[error] ✗ order-value is NOT compatible:
[error]     new required field has no default value
[error] 1 incompatible, 0 failed
```

### Edge Cases

- No registrations configured → `[warn] No schema registrations configured.` + succeed
- File not found → reported as `Failed`, does not prevent checking other subjects
- Registry unreachable → reported as `Failed` per subject
