# sbt-schema-registry-plugin: wire Confluent Schema Registry into your sbt build

*Download, register, and compatibility-check Avro / Protobuf / JSON Schema as ordinary sbt tasks — so the registry is the source of truth your build actually reads, not a thing you copy-paste around.*

---

We do performance testing with **Gatling** — the JVM flavor, driven by sbt. A lot of what we load-test is Kafka, and to write a Kafka simulation that means anything you need the **Avro** schemas for the topics you're hitting. Those schemas live in a **Confluent Schema Registry**, addressed by *subject* and *version*, and they drift every time another team ships a contract change.

For a long time our "schema sync" was a person. Someone opened the registry UI, found the subject, copied the schema, pasted an `.avsc` into the repo, and moved on. Then the schema changed upstream, nobody re-copied it, and a perf run failed for a reason that had nothing to do with performance. So we wrote a tiny sbt task to fetch schemas instead — and then it sat there, a one-trick downloader, for a long while. (Why it stayed raw, and what finally got it finished, in a minute.)

## The real problem is context, not copying

Copying a schema once is easy. Keeping a *copy* honest is the hard part, and it fails quietly. A checked-in `.avsc` is a snapshot that starts lying the moment the registry moves on, and you usually find out in the worst place: a consumer that won't deserialize, a perf number that makes no sense, a green CI run built on a stale contract. The usual escape hatches aren't better — a `curl`-and-`jq` script nobody wants to own, or a "just remember to re-download it" convention that no one remembers.

sbt already resolves your dependencies, compiles your Scala, and runs your simulations. Fetching schemas should be one more task in that same build — reproducible, versioned, and pointed straight at the registry. That was always the fix. The reason it took us so long to build it properly is the boring truth of internal tooling: the *surrounding* code — auth, a version manifest, bounded parallelism, transitive reference resolution, a cross-build matrix, tests — is unglamorous, and unglamorous work loses every prioritization fight. What changed for us was simple: a capable AI coding agent made that boring code cheap to write, and we drove it with **spec-driven development** (GitHub's [spec-kit](https://github.com/github/spec-kit)) so each capability was specified before it was generated. The downloader finally grew up.

## Meet the plugin

**`sbt-schema-registry-plugin`** makes the Schema Registry a first-class citizen of your build. Add it to `project/plugins.sbt`, point `schemaRegistryUrl` at a registry, and you get sbt tasks to **download**, **register**, and **compatibility-check** schemas — for **Avro, Protobuf, and JSON Schema**. It's cross-built from a single source tree for **sbt 1.x (Scala 2.12)** and **sbt 2.x (Scala 3)** with the *same* public keys, so it doesn't matter which sbt line you're on. It also doesn't care what Scala version your project uses — the plugin runs on sbt's own Scala.

## What you get (zero ceremony)

No code generation step to wire up, no extra config files — settings in `build.sbt` and you're done:

- ✅ **Declarative download** — list subjects (or a regex), get files. Pin a version or follow `latest`.
- ✅ **More than Avro** — Avro `.avsc`, Protobuf `.proto`, JSON Schema `.json`, each written with the right extension.
- ✅ **Wildcards** — `"orders-.*"` instead of naming every subject by hand.
- ✅ **Transitive references resolved automatically** — a schema that references others pulls its whole dependency closure, so it actually compiles.
- ✅ **Incremental by default** — a version manifest means unchanged schemas aren't re-fetched.
- ✅ **Parallel + retry** — bounded concurrency for big sets, with retry/backoff so a flaky registry call doesn't fail the build.
- ✅ **Push, not just pull** — register local schema files and get back their schema IDs.
- ✅ **A compatibility gate** — check candidate schemas against the registry and fail the build on a breaking change, before it reaches production.
- ✅ **Discovery** — list every subject with its version range and compatibility level.
- ✅ **sbt 1.x and sbt 2.x** — identical keys on both; upgrading your build line changes nothing here.

## How to use it

1. **Add the plugin** in `project/plugins.sbt` (the Confluent resolver is needed for the registry client):

   ```sbt
   resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/")

   addSbtPlugin("org.galaxio" % "sbt-schema-registry-plugin" % "<latest-version>")
   ```

   The latest version is on [Maven Central](https://central.sonatype.com/artifact/org.galaxio/sbt-schema-registry-plugin).

2. **Point it at your registry** and declare what you want, in `build.sbt`:

   ```sbt
   import org.galaxio.avro.RegistrySubject

   schemaRegistryUrl      := "http://schema-registry-host:8081"
   schemaRegistrySubjects ++= Seq(
     RegistrySubject("orders-value", 3),     // pinned to version 3
     RegistrySubject.latest("payments-value"), // follows the registry
   )
   ```

3. **Run the download:**

   ```bash
   sbt "Compile / schemaRegistryDownload"
   ```

   Files land in `schemaRegistryTargetFolder` (default `src/main/avro`) as `<subject>-<version>.<ext>`.

4. **Reach for the rest when you need it** — `schemaRegistryRegister` to push schemas, `schemaRegistryTestCompatibility` to gate a deploy, `schemaRegistryListSubjects` to explore. Full key reference and source links are in [`docs/FEATURES.md`](https://github.com/galax-io/sbt-schema-registry-plugin/blob/main/docs/FEATURES.md).

## A realistic example

Say you don't want to name subjects at all — you want everything in an `orders` domain, with all referenced schemas, and you don't want to re-download what hasn't changed:

```sbt
import org.galaxio.avro.SchemaRegistryAuth

schemaRegistryUrl               := "https://schema-registry.internal:8081"
schemaRegistryAuth              := Some(SchemaRegistryAuth.BasicAuth("ci", sys.env("SR_TOKEN")))
schemaRegistrySubjectPatterns   += "orders\\..*"   // every subject in the orders domain
schemaRegistryResolveReferences := true            // pull the full reference closure (default)
schemaRegistryIncremental       := true            // skip unchanged schemas (default)
schemaRegistryParallelism       := 8               // fetch concurrently
```

```bash
$ sbt "Compile / schemaRegistryDownload"
[info] orders.order-value          v7   downloaded
[info] orders.line-item-value      v3   downloaded   (referenced by order-value)
[info] orders.address              v2   downloaded   (referenced by line-item-value)
[info] orders.customer-value       v5   skipped       (unchanged)
[info] 3 downloaded, 1 skipped
```

One task expands the wildcard, chases references transitively, fetches in parallel, and skips what's already current — and it's reproducible on every machine and every CI run, because it reads the registry instead of trusting a copy.

## One idea to take with you

A schema is a contract, and a contract you keep a private copy of isn't really a contract. Put the registry where it belongs — *inside* the build that compiles against it — and a whole class of "works on my machine, stale in CI, broken in prod" problems quietly disappears. That's the whole pitch, and it's why we keep sharpening the plugin: each new capability has started as a small spec and shipped as a focused PR, and there's more we want it to do.

## Give it a try

If your build still pulls schemas with `curl` and a prayer — or your Gatling-on-Kafka setup quietly depends on schemas drifting away from the registry — this might save you the same chore it saved us.

👉 **[github.com/galax-io/sbt-schema-registry-plugin](https://github.com/galax-io/sbt-schema-registry-plugin)**

Try it against a real registry, kick the tires, and **let us know** how it goes — issues and PRs are genuinely welcome (they tend to turn into the next feature). If it saves you a bash script, a ⭐ helps other people on the same treadmill find it.
