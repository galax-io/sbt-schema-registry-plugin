# sbt-schema-registry-plugin — Agent Guide

sbt plugin for downloading Avro schemas from Confluent Schema Registry. Published plugin — treat public sbt keys and task behavior as compatibility-sensitive.

## Role

Principal Engineer: Scala 2.12, sbt plugin API, Confluent Schema Registry, Avro. Prefer small, backward-compatible changes.

## Stack

Cross-built from one source tree for two axes: **Scala 2.12 → sbt 1.x** (artifact `_2.12_1.0`) and **Scala 3.8.x → sbt 2.x** (artifact `_sbt2_3`). Built from an sbt 2.0.9 launcher; Java 17+. Axis mapping in `build.sbt` (a `projectMatrix` with one row per Scala version + `pluginCrossBuild / sbtVersion`); dependency versions in `project/Dependencies.scala`. Row IDs: `sbtSchemaRegistryPlugin` / `it` (Scala 3) and `sbtSchemaRegistryPlugin2_12` / `it2_12` (Scala 2.12). sbt-version-only API differences (e.g. `Def.uncached`) live behind `PluginCompat` in `plugin/src/main/scala-2.12` / `plugin/src/main/scala-3`; almost all code is shared in `plugin/src/main/scala`.

## Source Layout

```
plugin/src/main/scala/org/galaxio/avro/        # plugin core + domain (shared by both axes)
plugin/src/main/scala-2.12/org/galaxio/avro/   # PluginCompat seam — sbt-1 axis (Def.uncached no-op)
plugin/src/main/scala-3/org/galaxio/avro/      # PluginCompat seam — sbt-2 axis (Def.uncached native)
plugin/src/test/scala/org/galaxio/avro/        # unit tests (mock client via mockito-scala)
it/src/test/scala/org/galaxio/avro/            # integration tests (Testcontainers — real Schema Registry + Kafka)
plugin/src/sbt-test/                           # scripted e2e tests (sbt plugin test framework)
```

## Commands

```bash
pre-commit install                                               # install git hook (one-time)
sbt scalafmtAll scalafmtSbt                                     # format
sbt scalafmtCheckAll scalafmtSbtCheck +compile +test            # verify both axes
sbt +compile +test                                              # CI unit, both axes
sbt sbtSchemaRegistryPlugin/test                                # sbt-2 / Scala-3 axis only
sbt sbtSchemaRegistryPlugin2_12/test                            # sbt-1 / Scala-2.12 axis only
sbt it/test it2_12/test                                         # integration both axes (Docker required)
sbt sbtSchemaRegistryPlugin2_12/scripted                        # sbt-1 plugin e2e (all fixtures)
sbt "sbtSchemaRegistryPlugin/scripted schema-registry/<fixture>" # sbt-2 e2e (skip sbt-1-only-plugin fixtures, e.g. download-success)
```

`.sbtopts` passes `--server`: sbt 2's default thin client would read `sbt a b c` as a single command line.

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

## Feature milestones (ALWAYS)

A feature is an issue in `galax-io/galaxio-sdlc-backlog`. It belongs to exactly one backlog milestone whose description has the line `Release: business`. Backlog milestones without that line (the backlog's own tooling and documents) are not releases and follow "Milestones" above.

- **Feature issue.** Lists the work in every repository it touches as a checklist, one line per issue or PR: `- [ ] galax-io/<repo>#<N> → <repo milestone>`. A line is checked when that work is merged and the repository milestone it names has been released, so its tag exists.
- **Repository work.** Every issue and PR of a feature carries the version milestone of its own repository and writes `Part of galax-io/galaxio-sdlc-backlog#<feature>`. A repository ships its own version when its milestone is complete, as under "Release Process". `scripts/check-linkage.sh` skips the same-milestone rule for a link to a feature; the PR still needs its own milestone.
- **Every PR links a feature.** No PR of a product repository merges without `Part of galax-io/galaxio-sdlc-backlog#<feature>` (or a closing link to a feature); `scripts/check-linkage.sh --pr` fails it. A bot's PR (a bot author, or a `dependabot/` or `update/` branch) and the backlog's own PRs are exempt. The backlog is private, so the CI job reads it with `BACKLOG_READ_TOKEN`, a read-only token for that repository (Issues and Metadata) set as an organization secret; without it the gate fails and says it cannot read the backlog. A technical PR with no feature of its own may skip this check with the maintainer's agreement: a line `No feature: <why, and who agreed>` of its own in its description, at least ten characters long, which the gate prints in its log. A milestone gets no standing feature of its own any more; the ones that exist stay until their release.
- **Work enters through the backlog.** A bug, an audit finding or a request is not worked on until it belongs to a feature: grouped into one or given one. An issue from outside stays in its repository, which is public, and carries the label `status:needs-feature` until it is linked to a feature in the backlog.
- **Feature done.** A feature issue is closed only when every line of its checklist is checked.
- **Release.** When every feature of a business milestone is closed, close the milestone and publish a GitHub Release in `galax-io/galaxio-sdlc-backlog` that lists each feature with the repository versions that carry it. Name the release and its tag by the milestone's id, the title up to its first em dash, lowercased (`M1 — first run` → `m1`).
- **Published releases are not edited.** A revert after the release is a new feature in the next business milestone.
- **Never** close a business milestone with an open feature, release a feature whose repository version is not tagged, or leave a repository version out of the release text.


## Labels (ALWAYS)

One label scheme for every repository of the organization: `scripts/labels.json` in `galax-io/galaxio-sdlc-backlog` (`plans/label-scheme.md` there says why). A label says what the milestone and the feature link do not: the kind of work, its topic, how bad it is, where it stands.

- **Names.** Every label is `group:value`. Labels a tool owns (GitHub's `good first issue` and `help wanted`, Dependabot's, and those code reads, listed as `reserved`) are left alone.
- **Exactly one `type:*` on every open issue:** `bug`, `enhancement`, `docs`, `maintenance`, `question`; in the backlog `type:feature` (an issue of a business milestone) or `type:standing` (the standing feature of an older milestone). An issue without a type is not triaged.
- **At most one each:** `severity:*` (`critical`, `high`, `medium`, `low`: the impact, not the order of work), `status:*` (`needs-triage`, `needs-info`, `needs-feature`, `ready-for-agent`, `ready-for-human`, `blocked`), `tier:*`.
- **Any number:** `area:*` (topics shared by every repository), `module:*` (parts of one repository, declared in the scheme), `source:external`, `source:audit`.
- **Not labels.** The order of work is the milestone: no `priority:*`. The feature is the `Part of` link: no `feature:*`. A rejected or duplicate issue is closed with that reason and a comment: no `wontfix`, `invalid`, `duplicate`.
- **The engine's labels** `sdlc:*` live in the backlog: the maintainer sets `sdlc:run`; only the engine sets `sdlc:gate`, `sdlc:done` and `sdlc:failed`.
- **A new label is a change to the scheme**, a PR to `scripts/labels.json`, not a label made in one repository. `scripts/sync-labels.sh` applies the scheme; `--check` lists what breaks it.

## Task number (ALWAYS)

A task of the backlog has one number across the organization: `BCKLG-<N>`, where `<N>` is its issue number in `galax-io/galaxio-sdlc-backlog`. It is the only number a spec gets, and it names the task wherever `#N` alone would be read as an issue or PR of another repository.

- **Spec.** Its directory is `specs/BCKLG-<N>-<name>/`, `<N>` its main task. Its `spec.md`, `plan.md` and `tasks.md` carry the line `**Задачи**: BCKLG-<N>[, BCKLG-<M>…]` under the title, or `**Tasks**: …` in a repository whose files are English only, read alike: every task it carries, the main one first. A task has one spec: before writing one, find the spec whose tasks line names the task, check it against the task as its issue says now, and update it rather than write another. A spec directory from before the number keeps its name.
- **Branch.** A spec branch made by hand is `spec/BCKLG-<N>-<name>`; the engine's run is `BCKLG-<N>-<time>`, on the branch `sdlc/BCKLG-<N>-<time>`.
- **Commit.** A commit that does a task's work carries the trailer `Refs: BCKLG-<N>`; its subject stays a Conventional Commit.
- **PR.** Its body carries the line `Refs: BCKLG-<N>` beside `Part of galax-io/galaxio-sdlc-backlog#<N>`.
- **Checked.** `scripts/check-linkage.sh --pr` fails a PR of a feature without its number, a spec directory it adds that is not named by a task, and a spec, plan or tasks without the tasks line; `--specs` checks every spec of a checkout.

