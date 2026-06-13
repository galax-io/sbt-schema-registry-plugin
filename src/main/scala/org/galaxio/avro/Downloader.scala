package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema

import java.nio.file.{Files, Path}
import java.util.{Collections, HashMap => JHashMap}
import scala.util.Try
import sbt.util.Logger

class Downloader private (client: SchemaRegistryClient, schemaOutputDir: Path, logger: Logger, closeAction: () => Unit)
    extends AutoCloseable {

  def this(client: SchemaRegistryClient, schemaOutputDir: Path, logger: Logger) =
    this(client, schemaOutputDir, logger, () => ())

  override def close(): Unit =
    Try(closeAction()).failed.foreach(e => logger.warn(s"Failed to close schema registry client: ${e.getMessage}"))

  private def createOutputDirIfNeeded(): Unit =
    if (Files.notExists(schemaOutputDir))
      Files.createDirectories(schemaOutputDir)

  private def fileName(subject: String, version: Int): String =
    s"$subject-$version.${Downloader.avroSchemaFileExtension}"

  private def fetchSchema(subject: RegistrySubject): Schema =
    subject.version match {
      case Some(v) =>
        client.getByVersion(subject.name, v, false)
      case None    =>
        val meta   = client.getLatestSchemaMetadata(subject.name)
        val schema =
          new Schema(subject.name, meta.getVersion, meta.getId, meta.getSchemaType, Collections.emptyList(), meta.getSchema)
        schema
    }

  def schemaSubjectToFile(subject: RegistrySubject): Try[Path] = Try {
    val versionLabel = subject.version.map(_.toString).getOrElse("latest")
    logger.info(s"Downloading schema ${subject.name} version=$versionLabel")
    val schema       = fetchSchema(subject)
    createOutputDirIfNeeded()
    val name         = fileName(schema.getSubject, schema.getVersion)
    val path         = Files.write(schemaOutputDir.resolve(name), schema.getSchema.getBytes())
    logger.info(s"Saved schema ${subject.name} to $path")
    path
  }

}

object Downloader {
  val avroSchemaFileExtension = "avsc"

  def apply(
      rootUrl: String,
      schemaOutputDir: Path,
      logger: Logger,
      cacheSize: Int = 200,
      auth: Option[SchemaRegistryAuth] = None,
      properties: Map[String, String] = Map.empty,
  ): Downloader = {
    val config = new JHashMap[String, Any]()
    properties.foreach { case (k, v) => config.put(k, v) }

    auth.foreach { case SchemaRegistryAuth.BasicAuth(user, pass) =>
      config.put("basic.auth.credentials.source", "USER_INFO")
      config.put("basic.auth.user.info", s"$user:$pass")
    }

    val client =
      if (config.isEmpty) new CachedSchemaRegistryClient(rootUrl, cacheSize)
      else new CachedSchemaRegistryClient(Collections.singletonList(rootUrl), cacheSize, config)

    new Downloader(client, schemaOutputDir, logger, () => client.close())
  }
}
