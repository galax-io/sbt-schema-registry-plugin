package org.galaxio.avro

sealed trait RegistrySubject {
  def name: String
}

object RegistrySubject {
  final case class Pinned(name: String, version: Int) extends RegistrySubject
  final case class Latest(name: String)               extends RegistrySubject

  def apply(name: String, version: Int): RegistrySubject                = Pinned(name, version)
  def apply(name: String, version: Option[Int] = None): RegistrySubject =
    version.fold(latest(name))(Pinned(name, _))
  def latest(name: String): RegistrySubject                             = Latest(name)
}
