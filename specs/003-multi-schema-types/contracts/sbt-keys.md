# sbt Plugin API Contract: Multi-Schema Type Support

## Public Types (unchanged)

```scala
// Already exists — no signature changes
sealed trait SchemaType
object SchemaType {
  case object Avro     extends SchemaType
  case object Protobuf extends SchemaType
  case object Json     extends SchemaType
}
```

## Public Types (new)

```scala
final case class SchemaReference(
    name: String,      // Import path (Protobuf) or $ref URI (JSON Schema)
    subject: String,   // Registry subject of referenced schema
    version: Int,      // Version of referenced schema
)
```

## Public Types (modified)

```scala
final case class RegistryRegistration(
    subject: String,
    file: File,
    schemaType: SchemaType = SchemaType.Avro,
    references: List[SchemaReference] = Nil,   // NEW — defaults to Nil
)
```

## sbt Settings & Tasks (unchanged)

No new settings or tasks. All existing keys work as before:

| Key | Type | Change |
|-----|------|--------|
| `schemaRegistryDownload` | `taskKey[Unit]` | None — now saves correct extension per type |
| `schemaRegistryRegister` | `taskKey[Unit]` | None — already uses `RegistryRegistration.schemaType` |
| `schemaRegistryTestCompatibility` | `taskKey[Unit]` | None — already uses `RegistryRegistration.schemaType` |
| `schemaRegistryRegistrations` | `settingKey[Seq[RegistryRegistration]]` | None — type unchanged, new field has default |
| `schemaRegistrySubjects` | `settingKey[Seq[RegistrySubject]]` | None |
| `schemaRegistryUrl` | `settingKey[String]` | None |
| `schemaRegistryTargetFolder` | `settingKey[File]` | None |
| `schemaRegistryCacheSize` | `settingKey[Int]` | None |
| `schemaRegistryAuth` | `settingKey[Option[SchemaRegistryAuth]]` | None |
| `schemaRegistryProperties` | `settingKey[Map[String, String]]` | None |

## Usage Examples

```scala
// Avro (unchanged — fully backward compatible)
schemaRegistryRegistrations := Seq(
  RegistryRegistration("user-value", file("src/main/avro/User.avsc"))
)

// Protobuf (type inferred from .proto extension)
schemaRegistryRegistrations := Seq(
  RegistryRegistration("order-value", file("src/main/proto/Order.proto"))
)

// JSON Schema (type inferred from .json extension)
schemaRegistryRegistrations := Seq(
  RegistryRegistration("config-value", file("src/main/json/Config.json"))
)

// Explicit type override
schemaRegistryRegistrations := Seq(
  RegistryRegistration("config-value", file("config.schema"), SchemaType.Json)
)

// With schema references
schemaRegistryRegistrations := Seq(
  RegistryRegistration(
    "order-value",
    file("src/main/proto/Order.proto"),
    references = List(
      SchemaReference("common.proto", "common-value", 1)
    )
  )
)

// Mixed types in one project
schemaRegistryRegistrations := Seq(
  RegistryRegistration("user-value", file("User.avsc")),
  RegistryRegistration("order-value", file("Order.proto")),
  RegistryRegistration("config-value", file("Config.json")),
)
```

## Backward Compatibility Guarantee

- All existing `build.sbt` configurations compile without changes
- `RegistryRegistration("subject", file)` still works (defaults: `Avro`, `Nil`)
- No new sbt settings or tasks to configure
- Download output filenames change only for non-Avro schemas (Avro still uses `.avsc`)
- Avro-only projects need no additional dependencies
