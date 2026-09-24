package org.galaxio.avro

import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.{entities => confluent}

import java.nio.file.Files
import java.util.{Collections, ArrayList => JArrayList}
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
      references: List[SchemaReference] = Nil,
  ): Either[RegistryError, ParsedSchema] = {
    val cRefs = toConfluentRefs(references)
    schemaType match {
      case SchemaType.Avro     =>
        Try {
          if (cRefs.isEmpty) new AvroSchema(content): ParsedSchema
          // AvroSchema(String, List[SchemaReference], Map[String,String], SchemaMetadata) — last param nullable
          else new AvroSchema(content, cRefs, Collections.emptyMap[String, String](), null): ParsedSchema
        }.toEither.left.map(RegistryError.RegistrationFailed(subject, _))
      case SchemaType.Protobuf =>
        loadSchema(
          subject,
          "io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema",
          content,
          cRefs,
          "kafka-protobuf-provider",
        )
      case SchemaType.Json     =>
        loadSchema(subject, "io.confluent.kafka.schemaregistry.json.JsonSchema", content, cRefs, "kafka-json-schema-provider")
    }
  }

  private def toConfluentRefs(
      refs: List[SchemaReference],
  ): java.util.List[confluent.SchemaReference] = {
    val list = new JArrayList[confluent.SchemaReference](refs.size)
    refs.foreach(r => list.add(new confluent.SchemaReference(r.name, r.subject, r.version)))
    list
  }

  private def loadSchema(
      subject: String,
      className: String,
      content: String,
      cRefs: java.util.List[confluent.SchemaReference] = Collections.emptyList(),
      depArtifact: String = "",
  ): Either[RegistryError, ParsedSchema] =
    Try {
      val clazz = Class.forName(className)
      if (cRefs.isEmpty) {
        clazz.getConstructor(classOf[String]).newInstance(content).asInstanceOf[ParsedSchema]
      } else {
        // ProtobufSchema and JsonSchema share a (String, List, Map, ...) constructor shape.
        // We match on (String, List, Map) prefix and pass nulls only for reference-type trailing params.
        val ctor = clazz.getConstructors.find { c =>
          val p = c.getParameterTypes
          p.length >= 3 &&
          p(0) == classOf[String] &&
          classOf[java.util.List[_]].isAssignableFrom(p(1)) &&
          classOf[java.util.Map[_, _]].isAssignableFrom(p(2)) &&
          p.drop(3).forall(!_.isPrimitive)
        }
          .getOrElse(sys.error(s"No references constructor found for $className"))
        val args = new Array[Object](ctor.getParameterTypes.length)
        args(0) = content
        args(1) = cRefs
        args(2) = Collections.emptyMap[String, String]()
        ctor.newInstance(args: _*).asInstanceOf[ParsedSchema]
      }
    }.toEither.left.map {
      case e: ClassNotFoundException =>
        val hint =
          if (depArtifact.nonEmpty) s"Add '$depArtifact' dependency" else "Add the appropriate Confluent provider dependency"
        RegistryError.RegistrationFailed(
          subject,
          new RuntimeException(s"Schema provider not on classpath ($className). $hint.", e),
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
        parsed   <- buildParsedSchema(reg.subject, content, reg.schemaType, reg.references)
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
