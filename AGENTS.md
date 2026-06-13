# sbt-schema-registry-plugin — Agent Guide

sbt plugin for downloading Avro schemas from Confluent Schema Registry and generating sources. Published plugin — treat all public sbt keys and task behavior as compatibility-sensitive.

## Role

Principal Engineer in sbt plugin and Scala tooling development. Strong Scala 2.12, sbt plugin API, Confluent Schema Registry, and Avro expertise. Prefer small, clear, backward-compatible changes unless the task explicitly requires otherwise.

## Stack

- Scala 2.12.21, sbt 1.12.11, Java 17+
- Confluent `kafka-schema-registry-client` 8.2.1
- ScalaTest 3.2.19 + mockito-scala-scalatest 2.0.0 (mockito-core 5.x) for unit tests
- Testcontainers 1.21.3 (`org.testcontainers:kafka`) for integration tests in the `it` subproject (`it/test`)

## Commands

Format:
```
sbt scalafmtAll scalafmtSbt
```

Verify (default):
```
sbt scalafmtCheckAll scalafmtSbtCheck compile test
```

Full CI (unit tests only, no external services):
```
sbt compile test
```

Integration tests (requires Docker):
```
sbt it/test
```

Scripted (sbt plugin integration tests, if present):
```
sbt scripted
```

## Design Rules

**KISS — one responsibility per layer:**
`SchemaDownloaderPlugin` wires sbt tasks. `Downloader` fetches. `RegistrySubject` models subjects/versions. `SchemaRegistryAuth` handles credentials. Don't merge concerns between layers.

**DRY — reuse existing abstractions:**
`RegistrySubject` is the single model for subject + version resolution. `SchemaRegistryAuth` is the single auth abstraction. Don't invent parallel credential or subject types — extend these.

**SOLID:** Each class owns one responsibility; extend via new classes/traits rather than modifying existing ones; keep sbt key interfaces narrow; inject dependencies from outside.

```scala
// ✅
class Downloader(auth: SchemaRegistryAuth)   // inject, don't construct
// ❌
class Downloader { val auth = BasicAuth(...) }
```

## Boundaries

✅ Always:
- Run `sbt scalafmtAll` before committing
- Branch from `main`; keep commits semantic and green
- Preserve backward compatibility for published sbt keys and task behavior
- Treat `build.sbt`, `project/Dependencies.scala`, `project/plugins.sbt` as source of truth for dependencies
- Treat `.github/workflows/ci.yml` (PR/branch checks) and `.github/workflows/release.yml` (tag-driven release) as source of truth for CI and release behavior

⚠️ Ask first:
- Adding new dependencies or major-version upgrades
- Changing public sbt key names, types, or task semantics
- Editing another repository
- Any change to release or publish workflow

🚫 Never:
- Force-push or commit directly to `main`
- Add merge commits to PR branches (rebase-oriented history)
- Commit knowingly broken code to `main`
- Add opportunistic refactors outside task scope
- Mock Schema Registry HTTP responses where a real integration path exists

## PR Workflow

1. Branch from `main`.
2. Run verify commands before commit.
3. Keep commits semantic and green.
4. Prefer rebase-oriented history; avoid merge commits in PR branches.
5. CI in `.github/workflows/ci.yml` is the source of truth for formatting, compile, and tests.
6. Trunk-based: `main` is trunk, `release/*` branches for stabilization. Releases are tag-driven — push `vX.Y.Z` on `main` or a `release/*` branch; `.github/workflows/release.yml` verifies the tag, runs tests, publishes via sbt-ci-release, and writes notes with git-cliff (`cliff.toml`). Align with this rather than inventing a parallel path.
