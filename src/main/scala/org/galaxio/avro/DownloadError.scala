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

  final case class InvalidPattern(pattern: String, error: Throwable) extends DownloadError {
    val message: String                   = s"Invalid regex pattern '$pattern': ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class SubjectListFailed(error: Throwable) extends DownloadError {
    val message: String                   = s"Failed to fetch subject list: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class ManifestParseError(error: Throwable) extends DownloadError {
    val message: String                   = s"Failed to parse version manifest: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class InvalidParallelism(value: Int) extends DownloadError {
    val message: String = s"Invalid parallelism value $value: must be between 1 and 32"
  }

  final case class InvalidRetryConfig(value: Int) extends DownloadError {
    val message: String = s"Invalid retry count $value: must be between 0 and 10"
  }
}
