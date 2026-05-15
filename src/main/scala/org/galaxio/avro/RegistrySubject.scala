package org.galaxio.avro

final case class RegistrySubject(name: String, version: Option[Int] = None)

object RegistrySubject {
  def apply(name: String, version: Int): RegistrySubject = RegistrySubject(name, Some(version))
  def latest(name: String): RegistrySubject              = RegistrySubject(name, None)
}
