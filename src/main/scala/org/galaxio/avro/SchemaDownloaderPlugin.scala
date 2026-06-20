package org.galaxio.avro

import _root_.io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import sbt.*
import Keys.*

import scala.util.Using

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
    val schemaRegistryParallelism       =
      settingKey[Int]("Number of concurrent schema downloads (1 = sequential)")
    val schemaRegistryRetries           =
      settingKey[Int]("Maximum retry attempts for transient download failures (0 = no retry)")
    val schemaRegistryResolveReferences =
      settingKey[Boolean]("Auto-download schemas referenced by downloaded schemas (transitive)")
    val schemaRegistryListSubjects      = taskKey[Unit]("List subjects in the schema registry")
    val schemaRegistrySubjectFilter     =
      settingKey[Option[String]]("Optional case-insensitive substring filter for schemaRegistryListSubjects")

    lazy val defaultSettings: Seq[Setting[?]] = Seq(
      schemaRegistryUrl               := "http://localhost:8081",
      schemaRegistryTargetFolder      := sourceDirectory.value / "main" / "avro",
      schemaRegistrySubjects          := Seq(),
      schemaRegistrySubjectPatterns   := Seq(),
      schemaRegistryRegistrations     := Seq(),
      schemaRegistryCacheSize         := Downloader.defaultCacheSize,
      schemaRegistryAuth              := None,
      schemaRegistryProperties        := Map.empty,
      schemaRegistryIncremental       := true,
      schemaRegistryParallelism       := 4,
      schemaRegistryRetries           := 3,
      schemaRegistryResolveReferences := true,
      schemaRegistrySubjectFilter     := None,
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

  override lazy val projectSettings: Seq[Setting[?]] = defaultSettings ++ Seq(
    schemaRegistryDownload                    := (Compile / schemaRegistryDownload).value,
    Compile / schemaRegistryDownload          := {
      val logger      = streams.value.log
      val subjects    = schemaRegistrySubjects.value
      val patterns    = schemaRegistrySubjectPatterns.value
      val parallelism = schemaRegistryParallelism.value
      val retries     = schemaRegistryRetries.value

      val cfg = DownloadConfig(
        subjects = subjects,
        patterns = patterns,
        incremental = schemaRegistryIncremental.value,
        parallelism = parallelism,
        retries = retries,
        resolveReferences = schemaRegistryResolveReferences.value,
        targetFolder = schemaRegistryTargetFolder.value.toPath,
        manifestFile = streams.value.cacheDirectory.toPath.resolve(".schema-versions.json"),
      )

      logger.debug(s"schemaRegistryUrl: ${schemaRegistryUrl.value}")
      logger.debug(s"schemaRegistryTargetFolder: ${cfg.targetFolder}")
      logger.debug(s"schemaRegistrySubjects: ${subjects.mkString(", ")}")
      logger.debug(s"schemaRegistrySubjectPatterns: ${patterns.mkString(", ")}")
      logger.debug(s"schemaRegistryIncremental: ${cfg.incremental}")
      logger.debug(s"schemaRegistryParallelism: $parallelism")
      logger.debug(s"schemaRegistryRetries: $retries")
      logger.debug(s"schemaRegistryResolveReferences: ${cfg.resolveReferences}")

      if (parallelism < 1 || parallelism > ParallelDownloader.MaxParallelism)
        sys.error(DownloadError.InvalidParallelism(parallelism).message)
      if (retries < 0 || retries > ParallelDownloader.MaxRetries)
        sys.error(DownloadError.InvalidRetryConfig(retries).message)

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
          DownloadOrchestrator.run(client, cfg, logger) match {
            case Left(err)      =>
              err.cause.foreach(logger.trace(_))
              sys.error(err.message)
            case Right(summary) =>
              if (summary.failed.nonEmpty) {
                summary.failed.foreach { case (s, e) =>
                  logger.error(s"Failed to download schema ${s.name}: ${e.message}")
                  e.cause.foreach(logger.trace(_))
                }
                sys.error(s"Failed to download ${summary.failed.size} schema(s)")
              }
              logger.info(s"${summary.succeeded} downloaded, ${summary.skipped} skipped, ${summary.failed.size} failed")
          }
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
    schemaRegistryListSubjects                := (Compile / schemaRegistryListSubjects).value,
    Compile / schemaRegistryListSubjects      := {
      val logger      = streams.value.log
      val filter      = schemaRegistrySubjectFilter.value
      val parallelism = schemaRegistryParallelism.value

      if (parallelism < 1 || parallelism > ParallelDownloader.MaxParallelism)
        sys.error(DownloadError.InvalidParallelism(parallelism).message)

      withRegistryClient(
        schemaRegistryUrl.value,
        schemaRegistryCacheSize.value,
        schemaRegistryAuth.value,
        schemaRegistryProperties.value,
      ) { client =>
        SubjectExplorer.listAll(client, filter, parallelism) match {
          case Left(err)      =>
            err.cause.foreach(logger.trace(_))
            sys.error(err.message)
          case Right(listing) =>
            logger.info(s"Found ${listing.size} subject(s):")
            val width = listing.subjects.map(_.name.length).foldLeft(0)(_ max _)
            listing.subjects.foreach { info =>
              val compat = info.compatibility.getOrElse("(default)")
              logger.info(s"  ${info.name.padTo(width, ' ')}  (versions: ${info.versionRange}, compat: $compat)")
            }
        }
      }
    },
  )
}
