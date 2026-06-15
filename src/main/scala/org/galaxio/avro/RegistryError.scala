package org.galaxio.avro

import java.io.File

sealed trait RegistryError {
  def message: String
  def cause: Option[Throwable] = None
}

object RegistryError {

  final case class FileNotFound(path: File) extends RegistryError {
    val message: String = s"Schema file not found: $path"
  }

  final case class FileReadFailed(path: File, error: Throwable) extends RegistryError {
    val message: String                   = s"Failed to read $path: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class RegistrationFailed(subject: String, error: Throwable) extends RegistryError {
    val message: String                   = s"Failed to register $subject: ${error.getMessage}"
    override val cause: Option[Throwable] = Some(error)
  }

  final case class UnsupportedSchemaType(ext: String) extends RegistryError {
    val message: String = s"Unsupported schema file extension: $ext"
  }
}
