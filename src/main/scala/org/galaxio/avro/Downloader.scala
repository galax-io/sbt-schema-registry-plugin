package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import sbt.util.Logger

import java.nio.file.{Files, Path}
import java.util.{Collections, HashMap => JHashMap}
import scala.util.Try

final class Downloader private[avro] (
    private[avro] val client: SchemaRegistryClient,
    schemaOutputDir: Path,
    logger: Logger,
    closeAction: () => Unit = () => (),
) extends AutoCloseable {

  override def close(): Unit =
    Try(closeAction()).failed.foreach(e => logger.warn(s"Failed to close schema registry client: ${e.getMessage}"))

  def schemaSubjectToFile(subject: RegistrySubject): Either[DownloadError, Path] =
    for {
      _        <- validateSubjectName(subject.name)
      resolved <- fetchSchema(subject)
      path     <- writeSchema(subject.name, resolved)
    } yield path

  private def validateSubjectName(name: String): Either[DownloadError, Unit] =
    if (name.contains('/') || name.contains('\\')) Left(DownloadError.InvalidSubjectName(name))
    else Right(())

  private def fetchSchema(subject: RegistrySubject): Either[DownloadError, (Int, String)] = {
    logger.info(s"Downloading schema ${subject.name} version=${versionLabel(subject)}")
    Try {
      subject match {
        case RegistrySubject.Pinned(name, version) =>
          val s = client.getByVersion(name, version, false)
          (s.getVersion: Int, s.getSchema)
        case RegistrySubject.Latest(name)          =>
          val meta = client.getLatestSchemaMetadata(name)
          (meta.getVersion: Int, meta.getSchema)
      }
    }.toEither.left.map(DownloadError.SchemaFetchFailed(subject.name, _))
  }

  private def writeSchema(subjectName: String, resolved: (Int, String)): Either[DownloadError, Path] = {
    val (version, body) = resolved
    Try {
      if (Files.notExists(schemaOutputDir)) Files.createDirectories(schemaOutputDir)
      val fileName = s"$subjectName-$version.${Downloader.avroSchemaFileExtension}"
      val path     = Files.write(schemaOutputDir.resolve(fileName), body.getBytes())
      logger.info(s"Saved schema $subjectName to $path")
      path
    }.toEither.left.map(DownloadError.WriteError(schemaOutputDir, _))
  }

  private def versionLabel(subject: RegistrySubject): String = subject match {
    case RegistrySubject.Pinned(_, v) => v.toString
    case _: RegistrySubject.Latest    => "latest"
  }
}

object Downloader {
  val avroSchemaFileExtension = "avsc"
  val defaultCacheSize        = 200

  private[avro] def buildConfig(
      auth: Option[SchemaRegistryAuth],
      properties: Map[String, String],
  ): Map[String, Any] = {
    val authEntries = auth.fold(Map.empty[String, Any]) { case SchemaRegistryAuth.BasicAuth(user, pass) =>
      Map[String, Any](
        "basic.auth.credentials.source" -> "USER_INFO",
        "basic.auth.user.info"          -> s"$user:$pass",
      )
    }
    properties ++ authEntries
  }

  def buildClient(
      rootUrl: String,
      cacheSize: Int = defaultCacheSize,
      auth: Option[SchemaRegistryAuth] = None,
      properties: Map[String, String] = Map.empty,
  ): CachedSchemaRegistryClient = {
    val config     = buildConfig(auth, properties)
    val javaConfig = {
      val m = new JHashMap[String, Any]()
      config.foreach { case (k, v) => m.put(k, v) }
      m
    }

    if (javaConfig.isEmpty) new CachedSchemaRegistryClient(rootUrl, cacheSize)
    else new CachedSchemaRegistryClient(Collections.singletonList(rootUrl), cacheSize, javaConfig)
  }

  def apply(
      rootUrl: String,
      schemaOutputDir: Path,
      logger: Logger,
      cacheSize: Int = defaultCacheSize,
      auth: Option[SchemaRegistryAuth] = None,
      properties: Map[String, String] = Map.empty,
  ): Downloader = {
    val client = buildClient(rootUrl, cacheSize, auth, properties)
    new Downloader(client, schemaOutputDir, logger, () => client.close())
  }
}
