import Dependencies.*

// Cross-build axes (one shared source tree under plugin/, one projectMatrix row per axis,
// built from the sbt 2 launcher pinned in project/build.properties):
//   sbt 1.x consumer -> Scala 2.12 -> pluginCrossBuild sbtVersion 1.12.15 -> artifact _2.12_1.0
//   sbt 2.x consumer -> Scala 3    -> pluginCrossBuild sbtVersion 2.0.0   -> artifact _sbt2_3
// NOTE: these axis versions are bumped manually — scala-steward does not track plain vals.
// `scala3` must match the Scala version the targeted sbt 2.x ships (see its release notes); bump both together.
val scala212 = "2.12.21"
val scala3   = "3.8.4" // the Scala 3 version sbt 2.0.0 is built against

val sbt1 = "1.12.12"
val sbt2 = "2.0.0"

// Map the active Scala binary version to the sbt line it targets. Kept as a helper so the
// plugin's pluginCrossBuild target and the it-module's util-logging version stay decoupled (D8).
// Any Scala 2.x (incl. a future 2.13) routes to sbt 1.x and reuses the plugin/src/main/scala-2.12
// PluginCompat seam; adding 2.13-specific compat code would require a new plugin/src/main/scala-2.13 source tree.
def sbtLineFor(scalaBinVersion: String): String =
  if (scalaBinVersion.startsWith("2.")) sbt1 else sbt2

// Both axes compile under -Xfatal-warnings (no warnings tolerated). Scala 3 emits a different warning
// set, so two syntax deprecations from Java-interop reflection in Registrar.scala (`_` type wildcards
// and `args: _*` vararg splices) — which are valid/required on 2.12 but have no single form accepted by
// both — are scoped away with targeted -Wconf on the Scala 3 axis only (D13). Everything else stays fatal.
def scalacOptionsFor(scalaBinVersion: String): Seq[String] = {
  val base = Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-unchecked")
  if (scalaBinVersion.startsWith("2."))
    base ++ Seq(
      "-Xfatal-warnings", // Scala 2.12 spelling; on Scala 3 this is a deprecated alias of -Werror
      "-language:implicitConversions",
      "-language:higherKinds",
      "-language:existentials",
      "-language:postfixOps",
    )
  else
    base ++ Seq(
      "-Werror", // Scala 3 spelling of fatal warnings
      "-language:implicitConversions",
      "-language:higherKinds",
      "-language:postfixOps",
      // -language:existentials intentionally omitted — Scala 3 enables it by default (2.12 still needs the flag).
      "-Wconf:msg=is deprecated for wildcard arguments:s",
      "-Wconf:msg=is no longer supported for vararg:s",
    )
}

lazy val commonSettings = Seq(
  scalacOptions := scalacOptionsFor(scalaBinaryVersion.value),
  // scala-collection-compat backports scala.jdk to 2.12 only; Scala 3 has it in the stdlib, so don't ship it there.
  libraryDependencies ++= (if (scalaBinaryVersion.value.startsWith("2.")) Seq(collectionCompat) else Seq.empty),
)

// Aggregates both plugin rows so root `+compile` / `+test` cover both axes. `it` stays out, as before the
// projectMatrix move: its Testcontainers suites need Docker and run explicitly (`it/test`, `it2_12/test`).
lazy val root = (project in file("."))
  .aggregate(sbtSchemaRegistryPlugin.projectRefs *)
  .settings(
    name           := "sbt-schema-registry-plugin-root",
    publish / skip := true,
  )

lazy val sbtSchemaRegistryPlugin = (projectMatrix in file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings)
  .settings(
    name                          := "sbt-schema-registry-plugin",
    sbtPlugin                     := true,
    pluginCrossBuild / sbtVersion := sbtLineFor(scalaBinaryVersion.value),
    resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/"),
    libraryDependencies ++= Seq(
      schemaRegistryClient,
      scalatest    % Test,
      mockitoScala % Test,
    ),
    // sbt 2 adds scala3-library at compile scope, while the sbt 1 launcher published it as provided (the
    // consumer's sbt supplies it). Keep it provided so the _sbt2_3 POM does not change with the launcher.
    libraryDependencies           := libraryDependencies.value.map { m =>
      if (m.organization == scalaOrganization.value && m.name == "scala3-library" && m.configurations.isEmpty)
        m % Provided
      else m
    },
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      "-Dplugin.version=" + version.value,
    ),
    scriptedBufferLog             := false,
    // Binary-compatibility check against the latest published release, per matrix row -- both
    // _2.12_1.0 and _sbt2_3 are published through 1.9.0. If a future release misses an axis, that
    // axis keeps its newest existing baseline.
    mimaPreviousArtifacts         := {
      val artifactId =
        if (scalaBinaryVersion.value.startsWith("2.")) "sbt-schema-registry-plugin_2.12_1.0"
        else "sbt-schema-registry-plugin_sbt2_3"
      Set(organization.value % artifactId % "1.9.0")
    },
  )
  .jvmPlatform(scalaVersions = Seq(scala3, scala212))

lazy val it = (projectMatrix in file("it"))
  .dependsOn(sbtSchemaRegistryPlugin)
  .settings(commonSettings)
  .settings(
    name           := "sbt-schema-registry-plugin-it",
    publish / skip := true,
    Test / fork    := true,
    libraryDependencies ++= Seq(
      "org.scala-sbt"    %% "util-logging" % sbtLineFor(scalaBinaryVersion.value) % Test,
      scalatest           % Test,
      testcontainersKafka % Test,
      protobufProvider    % Test,
      jsonSchemaProvider  % Test,
    ),
  )
  .jvmPlatform(scalaVersions = Seq(scala3, scala212))
