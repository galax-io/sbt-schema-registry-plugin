package org.galaxio.avro

sealed trait SubjectSpec extends Product with Serializable

object SubjectSpec {
  final case class Exact(subject: RegistrySubject) extends SubjectSpec
  final case class Pattern(regex: String)          extends SubjectSpec
}
