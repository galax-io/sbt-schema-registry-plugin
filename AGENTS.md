# sbt-schema-registry-plugin — Agent Guide

sbt plugin for downloading Avro schemas from Confluent Schema Registry. Published plugin — treat public sbt keys and task behavior as compatibility-sensitive.

## Role

Principal Engineer: Scala 2.12, sbt plugin API, Confluent Schema Registry, Avro. Prefer small, backward-compatible changes.

## Stack

Scala 2.12 (sbt's Scala), sbt 1.x, Java 17+. Exact dependency versions in `build.sbt` / `project/Dependencies.scala`.

## Source Layout

```
src/main/scala/org/galaxio/avro/    # plugin core + domain
src/test/scala/org/galaxio/avro/    # unit tests (mock client via mockito-scala)
it/src/test/scala/org/galaxio/avro/ # integration tests (Testcontainers — real Schema Registry + Kafka)
src/sbt-test/                       # scripted e2e tests (sbt plugin test framework)
```

## Commands

```bash
pre-commit install                                         # install git hook (one-time)
sbt scalafmtAll scalafmtSbt                               # format
sbt scalafmtCheckAll scalafmtSbtCheck compile test        # verify
sbt compile test                                          # CI (unit only)
sbt it/test                                               # integration (Docker required)
sbt scripted                                              # sbt plugin e2e
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

- **Every minor version gets its own `release/X.Y.0` branch** — no exceptions
- **Tags ONLY on `release/*` branches or `main`** — release.yml validates this
- **Branch name must match tag version**: `release/1.2.0` → `v1.2.0`, `v1.2.1`, etc.
- **Never delete a release tag** after Sonatype deployment starts — creates stuck deployments
- **Never reuse a version number** — Sonatype Central rejects duplicates permanently
