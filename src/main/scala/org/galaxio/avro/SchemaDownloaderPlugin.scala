package org.galaxio.avro

import sbt.*
import Keys.*

object SchemaDownloaderPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val schemaRegistryDownload = taskKey[Unit]("Download schemas")

    val schemaRegistryUrl          = settingKey[String]("Url to schema registry")
    val schemaRegistryTargetFolder = settingKey[File]("Target for storing the avro schemas")
    val schemaRegistrySubjects     = settingKey[Seq[RegistrySubject]]("Subjects to download")

    lazy val defaultSettings: Seq[Setting[?]] = Seq(
      schemaRegistryUrl          := "http://localhost:8081",
      schemaRegistryTargetFolder := sourceDirectory.value / "main" / "avro",
      schemaRegistrySubjects     := Seq(),
    )

  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = defaultSettings ++ Seq(
    logLevel / schemaRegistryDownload := (logLevel ?? Level.Info).value,
    Compile / schemaRegistryDownload  := {
      val logger = streams.value.log
      logger.debug(s"schemaRegistryUrl: ${schemaRegistryUrl.value}")
      logger.debug(s"schemaRegistryTargetFolder: ${schemaRegistryTargetFolder.value}")
      logger.debug(s"schemaRegistrySubjects: ${schemaRegistrySubjects.value.mkString(",")}")

      val downloader = Downloader(schemaRegistryUrl.value, schemaRegistryTargetFolder.value.toPath, logger)

      schemaRegistrySubjects.value.foreach(downloader.schemaSubjectToFile)

    },
  )
}
