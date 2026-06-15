package org.galaxio.avro

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import scala.collection.JavaConverters._
import scala.util.Try

final case class VersionManifest(versions: Map[String, Int]) {

  def versionOf(subject: String): Option[Int] = versions.get(subject)

  def updated(subject: String, version: Int): VersionManifest =
    copy(versions = versions.updated(subject, version))

  def updatedAll(entries: List[(String, Int)]): VersionManifest =
    copy(versions = versions ++ entries)

  def toJson: String = {
    val mapper = new ObjectMapper()
    val node   = mapper.createObjectNode()
    versions.toList.sortBy(_._1).foreach { case (k, v) => node.put(k, v) }
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
  }
}

object VersionManifest {
  val empty: VersionManifest = VersionManifest(Map.empty)

  def fromJson(json: String): Either[DownloadError, VersionManifest] =
    Try {
      val mapper  = new ObjectMapper()
      val tree    = mapper.readTree(json)
      if (tree == null || !tree.isObject)
        throw new IllegalArgumentException("Expected JSON object")
      val entries = tree
        .fieldNames()
        .asScala
        .flatMap { name =>
          val value = tree.get(name)
          if (value.isIntegralNumber) Some(name -> value.asInt()) else None
        }
        .toMap
      VersionManifest(entries)
    }.toEither.left.map(e => DownloadError.ManifestParseError(e))
}
