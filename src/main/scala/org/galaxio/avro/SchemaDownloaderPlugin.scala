package org.galaxio.avro

import _root_.io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import sbt.*
import Keys.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => JPath}
import scala.util.{Try, Using}

object SchemaDownloaderPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val schemaRegistryDownload          = taskKey[Unit]("Download schemas from schema registry")
    val schemaRegistryRegister          = taskKey[Unit]("Register (push) schemas to schema registry")
    val schemaRegistryTestCompatibility = taskKey[Unit]("Check schema compatibility against registry")
    val schemaRegistryUrl               = settingKey[String]("URL of the schema registry")
    val schemaRegistryTargetFolder      = settingKey[File]("Output directory for downloaded schemas")
    val schemaRegistrySubjects          = settingKey[Seq[RegistrySubject]]("Schema subjects to download")
    val schemaRegistryRegistrations     = settingKey[Seq[RegistryRegistration]]("Schema registrations to push")
    val schemaRegistryCacheSize         = settingKey[Int]("Schema registry client cache size")
    val schemaRegistryAuth              = settingKey[Option[SchemaRegistryAuth]]("Schema registry authentication")
    val schemaRegistryProperties        = settingKey[Map[String, String]]("Additional schema registry client properties")
    val schemaRegistrySubjectPatterns   =
      settingKey[Seq[String]]("Regex patterns to match subjects for download")
    val schemaRegistryIncremental       =
      settingKey[Boolean]("Enable incremental download — skip unchanged schemas")

    lazy val defaultSettings: Seq[Setting[?]] = Seq(
      schemaRegistryUrl             := "http://localhost:8081",
      schemaRegistryTargetFolder    := sourceDirectory.value / "main" / "avro",
      schemaRegistrySubjects        := Seq(),
      schemaRegistrySubjectPatterns := Seq(),
      schemaRegistryRegistrations   := Seq(),
      schemaRegistryCacheSize       := Downloader.defaultCacheSize,
      schemaRegistryAuth            := None,
      schemaRegistryProperties      := Map.empty,
      schemaRegistryIncremental     := true,
    )
  }

  import autoImport.*

  private def withRegistryClient[A](
      url: String,
      cacheSize: Int,
      auth: Option[SchemaRegistryAuth],
      properties: Map[String, String],
  )(f: SchemaRegistryClient => A): A =
    Using.resource(Downloader.buildClient(url, cacheSize, auth, properties))(f)

  private def loadManifest(path: JPath, incremental: Boolean, logger: sbt.util.Logger): VersionManifest =
    if (!incremental || !Files.exists(path)) VersionManifest.empty
    else
      Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither
        .flatMap(VersionManifest.fromJson) match {
        case Right(m) => m
        case Left(_)  =>
          logger.warn("Version manifest corrupted, re-downloading all schemas")
          VersionManifest.empty
      }

  private def writeManifest(path: JPath, manifest: VersionManifest, logger: sbt.util.Logger): Unit =
    Try {
      Files.createDirectories(path.getParent)
      Files.write(path, manifest.toJson.getBytes(StandardCharsets.UTF_8))
    }.failed.foreach(e => logger.warn(s"Failed to write version manifest: ${e.getMessage}"))

  override lazy val projectSettings: Seq[Setting[?]] = defaultSettings ++ Seq(
    schemaRegistryDownload                    := (Compile / schemaRegistryDownload).value,
    Compile / schemaRegistryDownload          := {
      val logger       = streams.value.log
      val subjects     = schemaRegistrySubjects.value
      val patterns     = schemaRegistrySubjectPatterns.value
      val incremental  = schemaRegistryIncremental.value
      val manifestFile =
        streams.value.cacheDirectory.toPath.resolve(".schema-versions.json")

      logger.debug(s"schemaRegistryUrl: ${schemaRegistryUrl.value}")
      logger.debug(s"schemaRegistryTargetFolder: ${schemaRegistryTargetFolder.value}")
      logger.debug(s"schemaRegistrySubjects: ${subjects.mkString(", ")}")
      logger.debug(s"schemaRegistrySubjectPatterns: ${patterns.mkString(", ")}")
      logger.debug(s"schemaRegistryIncremental: $incremental")

      if (subjects.isEmpty && patterns.isEmpty) {
        logger.warn(
          "No schema subjects configured. Set schemaRegistrySubjects or schemaRegistrySubjectPatterns to download schemas.",
        )
      } else {
        withRegistryClient(
          schemaRegistryUrl.value,
          schemaRegistryCacheSize.value,
          schemaRegistryAuth.value,
          schemaRegistryProperties.value,
        ) { client =>
          val resolvedSubjects = if (patterns.isEmpty) {
            subjects
          } else {
            val specs =
              subjects.map(s => SubjectSpec.Exact(s)).toList ++
                patterns.map(SubjectSpec.Pattern).toList

            SubjectResolver.resolve(client, specs) match {
              case Left(err)   =>
                err.cause.foreach(logger.trace(_))
                sys.error(err.message)
              case Right(plan) =>
                logger.info(s"Resolved ${plan.subjects.size} subject(s) from patterns")
                plan.subjects
            }
          }

          val manifest = loadManifest(manifestFile, incremental, logger)

          val decisions =
            if (incremental)
              IncrementalResolver.plan(
                manifest,
                resolvedSubjects.toList,
                s =>
                  Try(client.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
                    .map(DownloadError.SchemaFetchFailed(s, _)),
              )
            else
              resolvedSubjects.toList.map(s => DownloadDecision.Download(s, "incremental disabled"))

          val (skips, downloads) = decisions.partition {
            case _: DownloadDecision.Skip => true
            case _                        => false
          }

          skips.foreach {
            case DownloadDecision.Skip(name, v) => logger.info(s"$name v$v → up to date")
            case _                              => ()
          }

          val downloader =
            Downloader.withExternalClient(client, schemaRegistryTargetFolder.value.toPath, logger)

          val downloadResults = downloads.collect { case d @ DownloadDecision.Download(subject, reason, _) =>
            logger.info(s"${subject.name}: $reason")
            d -> downloader.schemaSubjectToFile(subject)
          }

          val failures = downloadResults.collect { case (d, Left(e)) => d.subject -> e }

          val downloaded = downloadResults.collect {
            case (d, Right(_)) if d.resolvedVersion.isDefined =>
              d.subject.name -> d.resolvedVersion.get
          }

          if (incremental && downloaded.nonEmpty) {
            val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)
            writeManifest(manifestFile, newManifest, logger)
          }

          if (failures.nonEmpty) {
            failures.foreach { case (s, e) =>
              logger.error(s"Failed to download schema ${s.name}: ${e.message}")
              e.cause.foreach(logger.trace(_))
            }
            sys.error(s"Failed to download ${failures.size} schema(s)")
          }

          logger.info(s"${downloads.size} downloaded, ${skips.size} skipped")
        }
      }
    },
    schemaRegistryRegister                    := (Compile / schemaRegistryRegister).value,
    Compile / schemaRegistryRegister          := {
      val logger        = streams.value.log
      val registrations = schemaRegistryRegistrations.value.toList

      if (registrations.isEmpty) {
        logger.warn("No schema registrations configured. Set schemaRegistryRegistrations to register schemas.")
      } else {
        withRegistryClient(
          schemaRegistryUrl.value,
          schemaRegistryCacheSize.value,
          schemaRegistryAuth.value,
          schemaRegistryProperties.value,
        ) { c =>
          val results             = Registrar.registerAll(c, registrations)
          val (errors, successes) = Registrar.partitionResults(results)

          successes.foreach(r => logger.info(s"Registered ${r.subject} → schema ID ${r.schemaId}"))
          errors.foreach { e =>
            logger.error(e.message)
            e.cause.foreach(logger.trace(_))
          }

          if (errors.nonEmpty) sys.error(s"${errors.size} registration(s) failed")
        }
      }
    },
    schemaRegistryTestCompatibility           := (Compile / schemaRegistryTestCompatibility).value,
    Compile / schemaRegistryTestCompatibility := {
      val logger        = streams.value.log
      val registrations = schemaRegistryRegistrations.value.toList

      if (registrations.isEmpty) {
        logger.warn("No schema registrations configured. Set schemaRegistryRegistrations to check compatibility.")
      } else {
        withRegistryClient(
          schemaRegistryUrl.value,
          schemaRegistryCacheSize.value,
          schemaRegistryAuth.value,
          schemaRegistryProperties.value,
        ) { c =>
          val report = CompatibilityChecker.checkAll(c, registrations)

          report.compatible.foreach(r => logger.info(s"✓ ${r.subject} is compatible"))
          report.incompatible.foreach { i =>
            logger.error(s"✗ ${i.subject} is NOT compatible:")
            i.messages.foreach(m => logger.error(s"    $m"))
          }
          report.failed.foreach(f => logger.error(s"⚠ ${f.subject}: ${f.cause.message}"))

          if (!report.isSuccess)
            sys.error(s"${report.incompatible.size} incompatible, ${report.failed.size} failed")
        }
      }
    },
  )
}
