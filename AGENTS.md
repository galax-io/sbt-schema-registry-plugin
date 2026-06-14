# sbt-schema-registry-plugin

sbt plugin for downloading Avro schemas (`.avsc`) from Confluent Schema Registry. Source generation is left to a downstream plugin (e.g. sbt-avrohugger). Published plugin — all public sbt keys and task behavior are compatibility-sensitive.

## Role

Scala / sbt plugin engineer. Prefer small, backward-compatible changes. Pure functional style: ADTs, `Either` for errors, effects at edges, immutable data. Inject dependencies, don't construct them.

## Project layout

```
src/main/scala/org/galaxio/avro/   plugin source (effect edge + pure core)
src/test/                          unit tests (mocked client)
it/                                integration tests (Testcontainers, Docker)
src/sbt-test/schema-registry/     scripted E2E tests
  .fixtures/                         shared fixtures (symlinked)
```

## Commands

```bash
sbt scalafmtAll scalafmtSbt          # format
sbt compile test                      # compile + unit tests
sbt it/test                           # integration tests (Docker required)
sbt scripted                          # E2E plugin tests
```

## Pre-commit hook

`.claude/settings.json` configures a PreCommit hook: `sbt --client scalafmtCheckAll scalafmtSbtCheck compile test`. Runs automatically before every commit. If format fails — run `sbt scalafmtAll scalafmtSbt` and re-commit.

## Trunk-based development

```
main ─────●────●────●────●────●──── (always releasable)
           \        \         ↑
            \        \     merge PR
          feat/x   fix/y
```

### Branch lifecycle

1. **Branch from `main`** — short-lived feature/fix branches only.
2. **Develop** — small, semantic commits. Rebase on `main` if behind.
3. **PR** — CI runs: format → compile → test → it/test. All must pass.
4. **Merge** — squash or rebase into `main`. No merge commits.
5. **Delete branch** — immediately after merge.

### Release

Automated on every merge to `main` via `.github/workflows/ci.yml`:

1. CI computes next semver from commit messages (conventional commits):
   - `feat:` → minor bump
   - `fix:` → patch bump
   - `BREAKING CHANGE` / `!:` → major bump
2. Tags `vX.Y.Z` on `main`
3. Publishes to Maven Central via `sbt-ci-release`
4. Creates GitHub Release with changelog

Manual release: push a `vX.Y.Z` tag on `main`. No `release/*` branches unless stabilization needed.

### Commit message format

```
<type>(<scope>): <description>

type:  feat | fix | test | refactor | docs | chore
scope: optional, e.g. downloader, plugin, ci
```

## Testing strategy

| Layer | What | When | Command |
|-------|------|------|---------|
| **Unit** | Mocked `SchemaRegistryClient`, pure logic | Every commit (pre-commit hook) | `sbt test` |
| **Integration** | Real registry via Testcontainers | CI, local with Docker | `sbt it/test` |
| **Scripted (E2E)** | Full sbt plugin lifecycle | CI | `sbt scripted` |

Rules:
- Never mock Schema Registry HTTP where a real integration path exists
- Unit tests for pure functions — no client needed
- Integration tests for client interactions — Testcontainers only
- Scripted tests for sbt task behavior — real `build.sbt` scenarios

## Code style — idiomatic functional Scala

Pure functional core, effects at edges. No imperative Java-isms.

**Error handling:**
- `Either[DomainError, A]` in core — never throw, never `Try` in new code
- Sealed `DomainError` ADT per bounded context (e.g. `RegistryError`)
- `Validated` / `NonEmptyList` when accumulating multiple errors
- Exceptions only at sbt task boundary (`sys.error` from `Either.Left`)

**Domain modeling:**
- Sealed traits + case classes for all domain types
- Smart constructors returning `Either` — never construct invalid values
- Exhaustive pattern matching — no `default` / `case _` on domain ADTs
- Prefer `final case class` — avoid `class` unless extending Java interop

**Composition:**
- `for`-comprehension over `Either` chains
- `traverse` / `sequence` for `List[Either[E, A]]` → `Either[E, List[A]]`
- Pure functions: `(Input) => Either[Error, Output]` — no logging, no mutation
- Higher-order functions for client abstraction: `(String => Either[E, A])` over concrete client types

**Effects and IO:**
- sbt task layer = effect edge: logging, file I/O, `sys.error`, client lifecycle
- Core functions return values — never `Unit`, never log
- `Using.resource` / loan pattern for client lifecycle
- No `Future` / `Await` without cats-effect `IO.blocking` alternative

**Dependencies:**
- Inject `SchemaRegistryClient` — don't construct inside core
- No `new` in core logic — factory methods in companion objects

**General:**
- No comments unless the WHY is non-obvious
- No mutable state (`var`, mutable collections)
- Prefer `List` over `Seq` in domain types (concrete, immutable)

## Issues and PRs

All GitHub issues, PR titles, and commit messages in English.

## Boundaries

⚠️ Ask first:
- Adding dependencies or major-version upgrades
- Changing public sbt key names, types, or task semantics
- Any change to CI/release workflow

🚫 Never:
- Force-push or commit directly to `main`
- Merge commits in PR branches
- Commit broken code to `main`
- Opportunistic refactors outside task scope
- Skip pre-commit hooks
