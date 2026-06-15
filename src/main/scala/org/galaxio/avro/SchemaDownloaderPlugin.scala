package org.galaxio.avro

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

    lazy val defaultSettings: Seq[Setting[?]] = Seq(
      schemaRegistryUrl           := "http://localhost:8081",
      schemaRegistryTargetFolder  := sourceDirectory.value / "main" / "avro",
      schemaRegistrySubjects      := Seq(),
      schemaRegistryRegistrations := Seq(),
      schemaRegistryCacheSize     := Downloader.defaultCacheSize,
      schemaRegistryAuth          := None,
      schemaRegistryProperties    := Map.empty,
    )
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = defaultSettings ++ Seq(
    schemaRegistryDownload                    := (Compile / schemaRegistryDownload).value,
    Compile / schemaRegistryDownload          := {
      val logger   = streams.value.log
      val subjects = schemaRegistrySubjects.value

      logger.debug(s"schemaRegistryUrl: ${schemaRegistryUrl.value}")
      logger.debug(s"schemaRegistryTargetFolder: ${schemaRegistryTargetFolder.value}")
      logger.debug(s"schemaRegistrySubjects: ${subjects.mkString(", ")}")

      if (subjects.isEmpty) {
        logger.warn("No schema subjects configured. Set schemaRegistrySubjects to download schemas.")
      } else {
        Using.resource(
          Downloader(
            rootUrl = schemaRegistryUrl.value,
            schemaOutputDir = schemaRegistryTargetFolder.value.toPath,
            logger = logger,
            cacheSize = schemaRegistryCacheSize.value,
            auth = schemaRegistryAuth.value,
            properties = schemaRegistryProperties.value,
          ),
        ) { downloader =>
          val results  = subjects.map(s => s -> downloader.schemaSubjectToFile(s))
          val failures = results.collect { case (s, Left(e)) => s -> e }

          if (failures.nonEmpty) {
            failures.foreach { case (s, e) =>
              logger.error(s"Failed to download schema ${s.name}: ${e.message}")
              e.cause.foreach(logger.trace(_))
            }
            sys.error(s"Failed to download ${failures.size} schema(s)")
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
        val client = Downloader.buildClient(
          rootUrl = schemaRegistryUrl.value,
          cacheSize = schemaRegistryCacheSize.value,
          auth = schemaRegistryAuth.value,
          properties = schemaRegistryProperties.value,
        )
        Using.resource(client) { c =>
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
        val client = Downloader.buildClient(
          rootUrl = schemaRegistryUrl.value,
          cacheSize = schemaRegistryCacheSize.value,
          auth = schemaRegistryAuth.value,
          properties = schemaRegistryProperties.value,
        )
        Using.resource(client) { c =>
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
