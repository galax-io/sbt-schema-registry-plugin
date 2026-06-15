package org.galaxio.avro

import java.nio.file.Path

sealed trait DownloadError {
  def message: String
  def cause: Option[Throwable] = None
}

object DownloadError {

  final case class InvalidSubjectName(name: String) extends DownloadError {
    val message: String = s"Subject name must not contain path separators: $name"
  }

  final case class SchemaFetchFailed(subject: String, error: Throwable) extends DownloadError {
    val message: String                   = s"Failed to fetch schema $subject: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class WriteError(path: Path, error: Throwable) extends DownloadError {
    val message: String                   = s"Failed to write schema to $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class UnsupportedSchemaType(schemaType: String, subject: String) extends DownloadError {
    val message: String = s"Unsupported schema type '$schemaType' for subject $subject"
  }
}
