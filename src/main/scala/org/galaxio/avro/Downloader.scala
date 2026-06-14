package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}

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

  private def validateSubjectName(name: String): Unit =
    require(!name.contains('/') && !name.contains('\\'), s"Subject name must not contain path separators: $name")

  def schemaSubjectToFile(subject: RegistrySubject): Try[Path] = Try {
    validateSubjectName(subject.name)
    val versionLabel = subject.version.map(_.toString).getOrElse("latest")
    logger.info(s"Downloading schema ${subject.name} version=$versionLabel")

    val (version: Int, schemaText: String) = subject.version match {
      case Some(v) =>
        val s = client.getByVersion(subject.name, v, false)
        (s.getVersion: Int, s.getSchema)
      case None    =>
        val meta = client.getLatestSchemaMetadata(subject.name)
        (meta.getVersion: Int, meta.getSchema)
    }

    createOutputDirIfNeeded()
    val name = fileName(subject.name, version)
    val path = Files.write(schemaOutputDir.resolve(name), schemaText.getBytes())
    logger.info(s"Saved schema ${subject.name} to $path")
    path
  }

}

object Downloader {
  val avroSchemaFileExtension = "avsc"
  val defaultCacheSize        = 200

  private[avro] def buildConfig(
      auth: Option[SchemaRegistryAuth],
      properties: Map[String, String],
  ): JHashMap[String, Any] = {
    val config = new JHashMap[String, Any]()
    properties.foreach { case (k, v) => config.put(k, v) }
    auth.foreach { case SchemaRegistryAuth.BasicAuth(user, pass) =>
      config.put("basic.auth.credentials.source", "USER_INFO")
      config.put("basic.auth.user.info", s"$user:$pass")
    }
    config
  }

  def apply(
      rootUrl: String,
      schemaOutputDir: Path,
      logger: Logger,
      cacheSize: Int = defaultCacheSize,
      auth: Option[SchemaRegistryAuth] = None,
      properties: Map[String, String] = Map.empty,
  ): Downloader = {
    val config = buildConfig(auth, properties)

    val client =
      if (config.isEmpty) new CachedSchemaRegistryClient(rootUrl, cacheSize)
      else new CachedSchemaRegistryClient(Collections.singletonList(rootUrl), cacheSize, config)

    new Downloader(client, schemaOutputDir, logger, () => client.close())
  }
}
