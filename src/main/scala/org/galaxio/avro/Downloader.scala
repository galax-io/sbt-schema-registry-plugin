package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import java.nio.file.{Files, Path}
import scala.util.Try
import sbt.util.Logger

class Downloader(client: SchemaRegistryClient, schemaOutputDir: Path, logger: Logger) {

  private def createOutputDirIfNeeded: Try[Unit] = Try {
    if (Files.notExists(schemaOutputDir)) {
      Files.createDirectory(schemaOutputDir)
    }
  }

  private def fileNameFromSchema(schema: Schema): Try[String] =
    scala.util.Success(s"${schema.getSubject}-${schema.getVersion}.${Downloader.avroSchemeFileExtension}")

  private def writeSchemaToFile(schema: Schema, fileName: String) = Try {
    Files.write(schemaOutputDir.resolve(fileName), schema.getSchema.getBytes())
  }

  def schemaSubjectToFile(schemaSubject: RegistrySubject): Unit =
    (for {
      _        <- Try(logger.info(s"start downloading schema ${schemaSubject.name} with version ${schemaSubject.version}"))
      schema   <- Try(client.getByVersion(schemaSubject.name, schemaSubject.version, false))
      _        <- createOutputDirIfNeeded
      fileName <- fileNameFromSchema(schema)
      path     <- writeSchemaToFile(schema, fileName)
    } yield path).fold(e => logger.error(e.getMessage), p => logger.info(s"saved schema ${schemaSubject.name} to $p"))
}
object Downloader {
  val avroSchemeFileExtension = "avsc"

  def apply(rootUrl: String, schemaOutputDir: Path, logger: Logger): Downloader =
    new Downloader(new CachedSchemaRegistryClient(rootUrl, 200), schemaOutputDir, logger)
}
