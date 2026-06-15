# Contract: sbt Keys — Wildcard Subject Download

**Feature**: 004-wildcard-subject-download | **Date**: 2026-06-15

## New Settings

### `schemaRegistrySubjectPatterns`

| Property | Value |
|----------|-------|
| Type | `SettingKey[Seq[String]]` |
| Default | `Seq.empty` |
| Scope | Project-level (same as `schemaRegistrySubjects`) |
| Description | Regex patterns to match subjects for download. Each pattern is compiled as a Java regex and applied as a full match against all subjects in the registry. |

**Usage**:

```scala
// Single pattern
schemaRegistrySubjectPatterns += "com\\.myorg\\..*-value"

// Multiple patterns
schemaRegistrySubjectPatterns ++= Seq(
  "com\\.team-a\\..*",
  "com\\.team-b\\..*-value"
)
```

## Modified Tasks

### `schemaRegistryDownload` (Compile scope)

**Behavior change**: When `schemaRegistrySubjectPatterns` is non-empty, the task first resolves patterns against the registry before downloading.

**Resolution order**:
1. Build `SubjectSpec.Exact` from `schemaRegistrySubjects.value`
2. Build `SubjectSpec.Pattern` from `schemaRegistrySubjectPatterns.value`
3. Call `SubjectResolver.resolve(client, exact ++ patterns)`
4. Download each subject in the resulting `DownloadPlan`

**Backward compatibility**: When `schemaRegistrySubjectPatterns` is empty (default), no subject listing call is made. Behavior identical to current implementation.

**Warning behavior**: When both `schemaRegistrySubjects` and `schemaRegistrySubjectPatterns` are empty, logs existing warning: "No schema subjects configured."

## Existing Settings (unchanged)

All existing sbt keys retain their current type, default, and semantics:

- `schemaRegistryUrl: SettingKey[String]`
- `schemaRegistryTargetFolder: SettingKey[File]`
- `schemaRegistrySubjects: SettingKey[Seq[RegistrySubject]]`
- `schemaRegistryCacheSize: SettingKey[Int]`
- `schemaRegistryAuth: SettingKey[Option[SchemaRegistryAuth]]`
- `schemaRegistryProperties: SettingKey[Map[String, String]]`
- `schemaRegistryRegistrations: SettingKey[Seq[RegistryRegistration]]`

## Error Contract

| Condition | Behavior |
|-----------|----------|
| Invalid regex pattern | Task fails with message: `"Invalid regex pattern '<pattern>': <error>"` |
| Registry unreachable during subject listing | Task fails with message: `"Failed to fetch subject list: <error>"` |
| Pattern matches zero subjects | No error. Logs: `"Pattern '<pattern>' matched 0 subjects"` |
| All patterns + explicit subjects resolve to empty list | Logs warning, no download occurs |
