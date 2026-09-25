package org.galaxio.avro

sealed abstract class SchemaType(val extension: String, val registryLabel: String) extends Product with Serializable

object SchemaType {
  case object Avro     extends SchemaType("avsc", "AVRO")
  case object Protobuf extends SchemaType("proto", "PROTOBUF")
  case object Json     extends SchemaType("json", "JSON")

  val values: List[SchemaType] = List(Avro, Protobuf, Json)

  private val byExtension: Map[String, SchemaType] =
    values.map(t => t.extension -> t).toMap

  private val byLabel: Map[String, SchemaType] =
    values.map(t => t.registryLabel -> t).toMap

  def fromExtension(ext: String): Either[RegistryError, SchemaType] =
    byExtension.get(ext).toRight(RegistryError.UnsupportedSchemaType(ext))

  def fromRegistryLabel(label: String): Either[RegistryError, SchemaType] =
    byLabel
      .get(Option(label).getOrElse("AVRO"))
      .toRight(RegistryError.UnsupportedSchemaType(Option(label).getOrElse("")))
}
