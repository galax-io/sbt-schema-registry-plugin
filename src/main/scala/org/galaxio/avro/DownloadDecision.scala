package org.galaxio.avro

sealed trait DownloadDecision extends Product with Serializable

object DownloadDecision {
  final case class Download(subject: RegistrySubject, reason: String) extends DownloadDecision
  final case class Skip(name: String, localVersion: Int)              extends DownloadDecision
}
