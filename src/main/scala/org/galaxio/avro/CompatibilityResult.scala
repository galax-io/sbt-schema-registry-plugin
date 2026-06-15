package org.galaxio.avro

sealed trait CompatibilityResult extends Product with Serializable {
  def subject: String
}

object CompatibilityResult {
  final case class Compatible(subject: String)                           extends CompatibilityResult
  final case class Incompatible(subject: String, messages: List[String]) extends CompatibilityResult
  final case class Failed(subject: String, cause: RegistryError)         extends CompatibilityResult
}
