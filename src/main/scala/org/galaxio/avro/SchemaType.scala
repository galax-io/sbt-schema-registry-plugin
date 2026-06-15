package org.galaxio.avro

sealed trait SchemaType extends Product with Serializable

object SchemaType {
  case object Avro     extends SchemaType
  case object Protobuf extends SchemaType
  case object Json     extends SchemaType

  def fromExtension(ext: String): Either[RegistryError, SchemaType] = ext match {
    case "avsc"  => Right(Avro)
    case "proto" => Right(Protobuf)
    case "json"  => Right(Json)
    case other   => Left(RegistryError.UnsupportedSchemaType(other))
  }
}
