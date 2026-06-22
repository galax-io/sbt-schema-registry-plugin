# sbt-schema-registry-plugin — Agent Guide

sbt plugin for downloading Avro schemas from Confluent Schema Registry. Published plugin — treat public sbt keys and task behavior as compatibility-sensitive.

## Role

Principal Engineer: Scala 2.12, sbt plugin API, Confluent Schema Registry, Avro. Prefer small, backward-compatible changes.

## Stack

Cross-built from one source tree for two axes: **Scala 2.12 → sbt 1.x** (artifact `_2.12_1.0`) and **Scala 3.8.x → sbt 2.x** (artifact `_sbt2_3`). Java 17+. Axis mapping in `build.sbt` (`crossScalaVersions` + `pluginCrossBuild / sbtVersion`); dependency versions in `project/Dependencies.scala`. sbt-version-only API differences (e.g. `Def.uncached`) live behind `PluginCompat` in `src/main/scala-2.12` / `src/main/scala-3`; almost all code is shared in `src/main/scala`.

## Source Layout

```
src/main/scala/org/galaxio/avro/        # plugin core + domain (shared by both axes)
src/main/scala-2.12/org/galaxio/avro/   # PluginCompat seam — sbt-1 axis (Def.uncached no-op)
src/main/scala-3/org/galaxio/avro/      # PluginCompat seam — sbt-2 axis (Def.uncached native)
src/test/scala/org/galaxio/avro/        # unit tests (mock client via mockito-scala)
it/src/test/scala/org/galaxio/avro/     # integration tests (Testcontainers — real Schema Registry + Kafka)
src/sbt-test/                           # scripted e2e tests (sbt plugin test framework)
```

## Commands

```bash
pre-commit install                                         # install git hook (one-time)
sbt scalafmtAll scalafmtSbt                               # format
sbt scalafmtCheckAll scalafmtSbtCheck +compile +test      # verify both axes
sbt +compile +test                                        # CI unit, both axes
sbt ++3.8.4 compile test                                  # sbt-2 / Scala-3 axis only
sbt ++2.12.21 compile test                               # sbt-1 / Scala-2.12 axis only
sbt +it/test                                             # integration both axes (Docker required)
sbt ++2.12.21 scripted                                   # sbt-1 plugin e2e (all fixtures)
sbt ++3.8.4 "scripted schema-registry/<fixture>"         # sbt-2 e2e (skip sbt-1-only-plugin fixtures, e.g. download-success)
```

## Public sbt Keys (compatibility-sensitive — never rename/retype/remove)

### Task keys (`taskKey[Unit]`)
- `schemaRegistryDownload` — download schemas from registry
- `schemaRegistryRegister` — register schemas to registry
- `schemaRegistryTestCompatibility` — check schema compatibility

### Setting keys
- `schemaRegistryUrl: String` — registry URL (required)
- `schemaRegistryTargetFolder: File` — output dir for downloads
- `schemaRegistrySubjects: Seq[RegistrySubject]`
- `schemaRegistryRegistrations: Seq[RegistryRegistration]`
- `schemaRegistryCacheSize: Int`
- `schemaRegistryAuth: Option[SchemaRegistryAuth]`
- `schemaRegistryProperties: Map[String, String]`
- `schemaRegistrySubjectPatterns: Seq[String]`
- `schemaRegistryIncremental: Boolean`
- `schemaRegistryParallelism: Int`
- `schemaRegistryRetries: Int`
- `schemaRegistryResolveReferences: Boolean`

## Architecture

Each class owns one responsibility. `SchemaDownloaderPlugin` wires sbt tasks → `Downloader` fetches → `RegistrySubject` (sealed ADT: Pinned/Latest) models subjects → `DownloadError` (sealed ADT) models failures as `Either`. `SchemaRegistryAuth` handles credentials. `ReferenceResolver` is a pure, tail-recursive BFS that transitively expands schema references (identity = subject+version) via an injected `fetch`; it runs between wildcard-expansion and incremental-skip in the download task. Inject dependencies; don't construct internally.

## Boundaries

**Always:** format before commit (`pre-commit run --all-files` or `sbt scalafmtAll scalafmtSbt`), branch from `main`, keep commits semantic and green, preserve backward compat for published keys. `build.sbt`/`project/` = dependency truth, `.github/workflows/ci.yml` = CI truth.

**Ask first:** new deps or major upgrades, changing public sbt key names/types/semantics, editing another repo, release/publish workflow changes.

**Never:** force-push or commit to `main`, merge commits in PR branches (rebase only), commit broken code, opportunistic refactors outside scope, mock Schema Registry HTTP where real integration path exists.

## Release Process (MANDATORY)

Trunk-based with release branches. `v*` tags trigger sbt-ci-release to Sonatype via `.github/workflows/release.yml`.

### Minor/Major release (e.g. 1.2.0, 2.0.0)

1. `git checkout -b release/X.Y.0 main` — cut release branch from `main`
2. `git push -u origin release/X.Y.0`
3. `git tag vX.Y.0` on the release branch
4. `git push origin vX.Y.0` — triggers release workflow

### Patch release (e.g. 1.2.1)

1. Fix lands on `main` first (via PR as usual)
2. `git cherry-pick <fix-sha>` onto `release/X.Y.0`
3. `git tag vX.Y.1` on the release branch
4. `git push origin vX.Y.1` — triggers release workflow

### Rules

- **One tag cross-publishes both axes** — `sbt ci-release` (`+publishSigned`) emits both `_2.12_1.0` (sbt 1.x) and `_sbt2_3` (sbt 2.x) from a single `vX.Y.Z` tag, under the same version number
- **Every minor version gets its own `release/X.Y.0` branch** — no exceptions
- **Tags ONLY on `release/*` branches or `main`** — release.yml validates this
- **Branch name must match tag version**: `release/1.2.0` → `v1.2.0`, `v1.2.1`, etc.
- **Never delete a release tag** after Sonatype deployment starts — creates stuck deployments
- **Never reuse a version number** — Sonatype Central rejects duplicates permanently
