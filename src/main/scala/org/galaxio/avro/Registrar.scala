package org.galaxio.avro

import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

import java.nio.file.Files
import scala.util.Try

object Registrar {

  def readSchemaFile(reg: RegistryRegistration): Either[RegistryError, String] =
    if (!reg.file.exists()) Left(RegistryError.FileNotFound(reg.file))
    else
      Try(new String(Files.readAllBytes(reg.file.toPath), "UTF-8")).toEither.left
        .map(RegistryError.FileReadFailed(reg.file, _))

  def buildParsedSchema(
      subject: String,
      content: String,
      schemaType: SchemaType,
  ): Either[RegistryError, ParsedSchema] =
    schemaType match {
      case SchemaType.Avro     =>
        Try(new AvroSchema(content): ParsedSchema).toEither.left
          .map(RegistryError.RegistrationFailed(subject, _))
      case SchemaType.Protobuf =>
        loadSchema(subject, "io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema", content)
      case SchemaType.Json     =>
        loadSchema(subject, "io.confluent.kafka.schemaregistry.json.JsonSchema", content)
    }

  private def loadSchema(
      subject: String,
      className: String,
      content: String,
  ): Either[RegistryError, ParsedSchema] =
    Try {
      val clazz       = Class.forName(className)
      val constructor = clazz.getConstructor(classOf[String])
      constructor.newInstance(content).asInstanceOf[ParsedSchema]
    }.toEither.left.map {
      case e: ClassNotFoundException =>
        RegistryError.RegistrationFailed(
          subject,
          new RuntimeException(
            s"Schema provider not on classpath ($className). Add the appropriate Confluent provider dependency.",
            e,
          ),
        )
      case e                         =>
        RegistryError.RegistrationFailed(subject, e)
    }

  def registerAll(
      client: SchemaRegistryClient,
      registrations: List[RegistryRegistration],
  ): List[Either[RegistryError, RegisteredSchema]] =
    registrations.map { reg =>
      for {
        content  <- readSchemaFile(reg)
        parsed   <- buildParsedSchema(reg.subject, content, reg.schemaType)
        schemaId <- Try(client.register(reg.subject, parsed)).toEither.left
                      .map(RegistryError.RegistrationFailed(reg.subject, _))
      } yield RegisteredSchema(reg.subject, schemaId)
    }

  def partitionResults[E, A](results: List[Either[E, A]]): (List[E], List[A]) = {
    val errors    = results.collect { case Left(e) => e }
    val successes = results.collect { case Right(a) => a }
    (errors, successes)
  }
}
