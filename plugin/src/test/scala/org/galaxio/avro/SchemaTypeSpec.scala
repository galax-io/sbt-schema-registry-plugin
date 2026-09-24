package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SchemaTypeSpec extends AnyFlatSpec with Matchers {

  "SchemaType.fromExtension" should "return Avro for avsc" in {
    SchemaType.fromExtension("avsc") shouldBe Right(SchemaType.Avro)
  }

  it should "return Protobuf for proto" in {
    SchemaType.fromExtension("proto") shouldBe Right(SchemaType.Protobuf)
  }

  it should "return Json for json" in {
    SchemaType.fromExtension("json") shouldBe Right(SchemaType.Json)
  }

  it should "return UnsupportedSchemaType for unknown extension" in {
    SchemaType.fromExtension("xml") shouldBe Left(RegistryError.UnsupportedSchemaType("xml"))
  }

  it should "return UnsupportedSchemaType for empty string" in {
    SchemaType.fromExtension("") shouldBe Left(RegistryError.UnsupportedSchemaType(""))
  }

  "SchemaType.fromRegistryLabel" should "return Avro for AVRO" in {
    SchemaType.fromRegistryLabel("AVRO") shouldBe Right(SchemaType.Avro)
  }

  it should "return Protobuf for PROTOBUF" in {
    SchemaType.fromRegistryLabel("PROTOBUF") shouldBe Right(SchemaType.Protobuf)
  }

  it should "return Json for JSON" in {
    SchemaType.fromRegistryLabel("JSON") shouldBe Right(SchemaType.Json)
  }

  it should "default to Avro for null label" in {
    SchemaType.fromRegistryLabel(null) shouldBe Right(SchemaType.Avro)
  }

  it should "return UnsupportedSchemaType for unknown label" in {
    SchemaType.fromRegistryLabel("XML") shouldBe a[Left[_, _]]
  }

  "SchemaType fields" should "have correct extensions" in {
    SchemaType.Avro.extension shouldBe "avsc"
    SchemaType.Protobuf.extension shouldBe "proto"
    SchemaType.Json.extension shouldBe "json"
  }

  it should "have correct registry labels" in {
    SchemaType.Avro.registryLabel shouldBe "AVRO"
    SchemaType.Protobuf.registryLabel shouldBe "PROTOBUF"
    SchemaType.Json.registryLabel shouldBe "JSON"
  }
}
