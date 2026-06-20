package org.galaxio.avro

final case class SubjectInfo(
    name: String,
    versions: List[Int],
    compatibility: Option[String],
) {
  def latestVersion: Option[Int] = versions.lastOption

  def versionRange: String = versions match {
    case Nil      => "none"
    case v :: Nil => v.toString
    case _        => s"${versions.head}..${versions.last}"
  }
}
