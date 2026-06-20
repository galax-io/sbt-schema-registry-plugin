package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import sbt.util.Logger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Collections, HashMap => JHashMap}
import scala.collection.JavaConverters._
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
      _                  <- validateSubjectName(subject.name)
      resolved           <- fetchSchema(subject)
      (version, body, st) = resolved
      path               <- writeSchema(subject.name, version, body, st)
    } yield path

  private def validateSubjectName(name: String): Either[DownloadError, Unit] =
    if (name.contains('/') || name.contains('\\')) Left(DownloadError.InvalidSubjectName(name))
    else Right(())

  private def fetchSchema(subject: RegistrySubject): Either[DownloadError, (Int, String, SchemaType)] = {
    logger.info(s"Downloading schema ${subject.name} version=${versionLabel(subject)}")
    for {
      raw <- Try {
               subject match {
                 case RegistrySubject.Pinned(name, version) =>
                   val s = client.getByVersion(name, version, false)
                   (s.getVersion: Int, s.getSchema, s.getSchemaType)
                 case RegistrySubject.Latest(name)          =>
                   val meta = client.getLatestSchemaMetadata(name)
                   (meta.getVersion: Int, meta.getSchema, meta.getSchemaType)
               }
             }.toEither.left.map(DownloadError.SchemaFetchFailed(subject.name, _))
      st  <- SchemaType
               .fromRegistryLabel(raw._3)
               .left
               .map(_ => DownloadError.UnsupportedSchemaType(Option(raw._3).getOrElse(""), subject.name))
    } yield (raw._1, raw._2, st)
  }

  private def writeSchema(
      subjectName: String,
      version: Int,
      body: String,
      schemaType: SchemaType,
  ): Either[DownloadError, Path] =
    Try {
      if (Files.notExists(schemaOutputDir)) Files.createDirectories(schemaOutputDir)
      val fileName = s"$subjectName-$version.${schemaType.extension}"
      val path     = Files.write(schemaOutputDir.resolve(fileName), body.getBytes(StandardCharsets.UTF_8))
      logger.info(s"Saved schema $subjectName to $path")
      path
    }.toEither.left.map(DownloadError.WriteError(schemaOutputDir, _))

  private def versionLabel(subject: RegistrySubject): String = subject match {
    case RegistrySubject.Pinned(_, v) => v.toString
    case _: RegistrySubject.Latest    => "latest"
  }
}

object Downloader {
  val defaultCacheSize = 200

  /** Build the `fetch` function [[ReferenceResolver]] needs, backed by a registry client.
    *
    * Reads a schema's metadata (`getSchemaMetadata` for a pinned version, `getLatestSchemaMetadata` for latest) and maps its
    * references to the domain [[SchemaReference]]. Confluent's `getReferences` may be null and its `getVersion` is a boxed
    * `Integer`, both handled here. Shared by the download task and integration tests so they exercise the same mapping.
    */
  private[avro] def referenceFetch(
      client: SchemaRegistryClient,
  ): (String, Option[Int]) => Either[DownloadError, ResolvedSchema] =
    (subject, version) =>
      Try {
        val meta = version match {
          case Some(v) => client.getSchemaMetadata(subject, v)
          case None    => client.getLatestSchemaMetadata(subject)
        }
        val refs = Option(meta.getReferences)
          .map(_.asScala.toList)
          .getOrElse(Nil)
          .map(r => SchemaReference(r.getName, r.getSubject, r.getVersion.intValue))
        ResolvedSchema(subject, meta.getVersion: Int, refs)
      }.toEither.left.map(DownloadError.SchemaFetchFailed(subject, _))

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

  private[avro] def buildClient(
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

  private[avro] def withExternalClient(
      client: SchemaRegistryClient,
      schemaOutputDir: Path,
      logger: Logger,
  ): Downloader =
    new Downloader(client, schemaOutputDir, logger)

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
