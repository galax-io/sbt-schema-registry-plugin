package org.galaxio.avro

import sbt.*
import Keys.*

object SchemaDownloaderPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val schemaRegistryDownload     = taskKey[Unit]("Download schemas from schema registry")
    val schemaRegistryUrl          = settingKey[String]("URL of the schema registry")
    val schemaRegistryTargetFolder = settingKey[File]("Output directory for downloaded schemas")
    val schemaRegistrySubjects     = settingKey[Seq[RegistrySubject]]("Schema subjects to download")
    val schemaRegistryCacheSize    = settingKey[Int]("Schema registry client cache size")
    val schemaRegistryAuth         = settingKey[Option[SchemaRegistryAuth]]("Schema registry authentication")
    val schemaRegistryProperties   = settingKey[Map[String, String]]("Additional schema registry client properties")

    lazy val defaultSettings: Seq[Setting[?]] = Seq(
      schemaRegistryUrl          := "http://localhost:8081",
      schemaRegistryTargetFolder := sourceDirectory.value / "main" / "avro",
      schemaRegistrySubjects     := Seq(),
      schemaRegistryCacheSize    := 200,
      schemaRegistryAuth         := None,
      schemaRegistryProperties   := Map.empty,
    )
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = defaultSettings ++ Seq(
    logLevel / schemaRegistryDownload := (logLevel ?? Level.Info).value,
    Compile / schemaRegistryDownload  := {
      val logger   = streams.value.log
      val subjects = schemaRegistrySubjects.value

      logger.debug(s"schemaRegistryUrl: ${schemaRegistryUrl.value}")
      logger.debug(s"schemaRegistryTargetFolder: ${schemaRegistryTargetFolder.value}")
      logger.debug(s"schemaRegistrySubjects: ${subjects.mkString(", ")}")

      if (subjects.isEmpty) {
        logger.warn("No schema subjects configured. Set schemaRegistrySubjects to download schemas.")
      } else {
        val downloader = Downloader(
          rootUrl = schemaRegistryUrl.value,
          schemaOutputDir = schemaRegistryTargetFolder.value.toPath,
          logger = logger,
          cacheSize = schemaRegistryCacheSize.value,
          auth = schemaRegistryAuth.value,
          properties = schemaRegistryProperties.value,
        )

        try {
          val results  = subjects.map(s => s -> downloader.schemaSubjectToFile(s))
          val failures = results.collect { case (s, scala.util.Failure(e)) => s -> e }

          if (failures.nonEmpty) {
            failures.foreach { case (s, e) =>
              logger.error(s"Failed to download schema ${s.name}: ${e.getMessage}")
              logger.trace(e)
            }
            sys.error(s"Failed to download ${failures.size} schema(s)")
          }
        } finally {
          downloader.close()
        }
      }
    },
  )
}
