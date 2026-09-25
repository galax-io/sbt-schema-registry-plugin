package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

import scala.jdk.CollectionConverters._
import scala.util.Try

object CompatibilityChecker {

  def checkOne(
      client: SchemaRegistryClient,
      reg: RegistryRegistration,
  ): CompatibilityResult = {
    val result = for {
      content  <- Registrar.readSchemaFile(reg)
      parsed   <- Registrar.buildParsedSchema(reg.subject, content, reg.schemaType, reg.references)
      messages <- Try(client.testCompatibilityVerbose(reg.subject, parsed).asScala.toList).toEither.left
                    .map(RegistryError.CompatibilityCheckFailed(reg.subject, _))
    } yield messages

    result match {
      case Right(Nil)      => CompatibilityResult.Compatible(reg.subject)
      case Right(messages) => CompatibilityResult.Incompatible(reg.subject, messages)
      case Left(err)       => CompatibilityResult.Failed(reg.subject, err)
    }
  }

  def checkAll(
      client: SchemaRegistryClient,
      registrations: List[RegistryRegistration],
  ): CompatibilityReport =
    CompatibilityReport(registrations.map(checkOne(client, _)))
}
