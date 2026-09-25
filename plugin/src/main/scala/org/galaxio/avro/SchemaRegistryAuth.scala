package org.galaxio.avro

sealed trait SchemaRegistryAuth

object SchemaRegistryAuth {
  final case class BasicAuth(username: String, password: String) extends SchemaRegistryAuth
}
