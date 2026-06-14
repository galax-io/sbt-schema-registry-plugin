# sbt-schema-registry-plugin — Agent Guide

sbt plugin for downloading Avro schemas from Confluent Schema Registry. Published plugin — treat public sbt keys and task behavior as compatibility-sensitive.

## Role

Principal Engineer: Scala 2.12, sbt plugin API, Confluent Schema Registry, Avro. Prefer small, backward-compatible changes.

## Stack

Scala 2.12.21, sbt 1.12.11, Java 17+, Confluent `kafka-schema-registry-client` 8.2.1, ScalaTest 3.2.19, mockito-scala 2.0.0, Testcontainers 1.21.3 (`it/test`).

## Commands

```bash
sbt scalafmtAll scalafmtSbt                        # format
sbt scalafmtCheckAll scalafmtSbtCheck compile test  # verify
sbt compile test                                    # CI (unit only)
sbt it/test                                         # integration (Docker)
sbt scripted                                        # sbt plugin e2e
```

## Architecture

Each class owns one responsibility. `SchemaDownloaderPlugin` wires sbt tasks → `Downloader` fetches → `RegistrySubject` (sealed ADT: Pinned/Latest) models subjects → `DownloadError` (sealed ADT) models failures as `Either`. `SchemaRegistryAuth` handles credentials. Inject dependencies; don't construct internally.

## Boundaries

**Always:** format before commit, branch from `main`, keep commits semantic and green, preserve backward compat for published keys. `build.sbt`/`project/` = dependency truth, `.github/workflows/ci.yml` = CI truth.

**Ask first:** new deps or major upgrades, changing public sbt key names/types/semantics, editing another repo, release/publish workflow changes.

**Never:** force-push or commit to `main`, merge commits in PR branches (rebase only), commit broken code, opportunistic refactors outside scope, mock Schema Registry HTTP where real integration path exists.

## Release

Trunk-based: tag-driven from `main` via `v*` tags, sbt-ci-release to Sonatype. Align with existing workflows.
