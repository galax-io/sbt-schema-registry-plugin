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
}
