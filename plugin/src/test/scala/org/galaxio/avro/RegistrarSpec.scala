package org.galaxio.avro

import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File

class RegistrarSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def tempFileWith(content: String, suffix: String = ".avsc"): File = {
    val f = File.createTempFile("schema-", suffix)
    f.deleteOnExit()
    java.nio.file.Files.write(f.toPath, content.getBytes("UTF-8"))
    f
  }

  "Registrar.readSchemaFile" should "read content from existing file" in {
    val f   = tempFileWith("""{"type":"string"}""")
    val reg = RegistryRegistration("test-subject", f)

    Registrar.readSchemaFile(reg) shouldBe Right("""{"type":"string"}""")
  }

  it should "return FileNotFound for missing file" in {
    val reg = RegistryRegistration("test-subject", new File("/nonexistent/path.avsc"))

    Registrar.readSchemaFile(reg) shouldBe Left(RegistryError.FileNotFound(new File("/nonexistent/path.avsc")))
  }

  "Registrar.registerAll" should "register all schemas successfully" in {
    val client = mock[SchemaRegistryClient]
    val f1     = tempFileWith("""{"type":"string"}""")
    val f2     = tempFileWith("""{"type":"int"}""")

    when(client.register(eqTo("sub1"), any[ParsedSchema]())).thenReturn(1)
    when(client.register(eqTo("sub2"), any[ParsedSchema]())).thenReturn(2)

    val regs = List(
      RegistryRegistration("sub1", f1),
      RegistryRegistration("sub2", f2),
    )

    val results = Registrar.registerAll(client, regs)
    results should have size 2
    results(0) shouldBe Right(RegisteredSchema("sub1", 1))
    results(1) shouldBe Right(RegisteredSchema("sub2", 2))
  }

  it should "return errors for failed registrations without stopping" in {
    val client = mock[SchemaRegistryClient]
    val f1     = tempFileWith("""{"type":"string"}""")
    val f2     = new File("/does/not/exist.avsc")

    when(client.register(eqTo("sub1"), any[ParsedSchema]())).thenReturn(1)

    val regs = List(
      RegistryRegistration("sub1", f1),
      RegistryRegistration("sub2", f2),
    )

    val results = Registrar.registerAll(client, regs)
    results should have size 2
    results(0) shouldBe Right(RegisteredSchema("sub1", 1))
    results(1) shouldBe a[Left[_, _]]
    results(1).left.value shouldBe RegistryError.FileNotFound(f2)
  }

  it should "handle empty registration list" in {
    val client  = mock[SchemaRegistryClient]
    val results = Registrar.registerAll(client, List.empty)
    results shouldBe empty
  }

  it should "return RegistrationFailed when client throws" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"string"}""")

    when(client.register(eqTo("fail-sub"), any[ParsedSchema]()))
      .thenThrow(new RuntimeException("Connection refused"))

    val results = Registrar.registerAll(client, List(RegistryRegistration("fail-sub", f)))
    results should have size 1
    results.head shouldBe a[Left[_, _]]
    results.head.left.value shouldBe a[RegistryError.RegistrationFailed]
    results.head.left.value.message should include("fail-sub")
    results.head.left.value.message should include("Connection refused")
  }

  "Registrar.partitionResults" should "separate errors and successes" in {
    val results: List[Either[String, Int]] = List(Right(1), Left("e1"), Right(2), Left("e2"))
    val (errors, successes)                = Registrar.partitionResults(results)
    errors shouldBe List("e1", "e2")
    successes shouldBe List(1, 2)
  }

  it should "handle all successes" in {
    val results: List[Either[String, Int]] = List(Right(1), Right(2))
    val (errors, successes)                = Registrar.partitionResults(results)
    errors shouldBe empty
    successes shouldBe List(1, 2)
  }

  it should "handle all errors" in {
    val results: List[Either[String, Int]] = List(Left("e1"), Left("e2"))
    val (errors, successes)                = Registrar.partitionResults(results)
    errors shouldBe List("e1", "e2")
    successes shouldBe empty
  }

  "Registrar.buildParsedSchema" should "build Avro schema from valid content" in {
    val result = Registrar.buildParsedSchema("test", """{"type":"string"}""", SchemaType.Avro)
    result shouldBe a[Right[_, _]]
    result.value.schemaType() shouldBe "AVRO"
  }

  it should "return RegistrationFailed for invalid Avro content" in {
    val result = Registrar.buildParsedSchema("test", "not valid avro", SchemaType.Avro)
    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[RegistryError.RegistrationFailed]
  }

  it should "return RegistrationFailed for Protobuf when provider not on classpath" in {
    val result = Registrar.buildParsedSchema("test", "syntax = \"proto3\";", SchemaType.Protobuf)
    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[RegistryError.RegistrationFailed]
    result.left.value.message should include("Schema provider not on classpath")
  }

  it should "return RegistrationFailed for Json when provider not on classpath" in {
    val result = Registrar.buildParsedSchema("test", """{"type":"object"}""", SchemaType.Json)
    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[RegistryError.RegistrationFailed]
    result.left.value.message should include("Schema provider not on classpath")
  }

  it should "build Avro schema with references" in {
    val refs   = List(SchemaReference("Base", "base-subject", 1))
    val result = Registrar.buildParsedSchema("test", """{"type":"string"}""", SchemaType.Avro, refs)
    result shouldBe a[Right[_, _]]
    result.value.references() should have size 1
  }

  it should "be extension-agnostic — schema type is determined by the SchemaType parameter, not the file" in {
    val result = Registrar.buildParsedSchema("test", """{"type":"string"}""", SchemaType.Avro)
    result shouldBe a[Right[_, _]]
    result.value.schemaType() shouldBe "AVRO"
  }

  it should "include dependency name in error for missing Protobuf provider" in {
    val result = Registrar.buildParsedSchema("test", "syntax = \"proto3\";", SchemaType.Protobuf)
    result shouldBe a[Left[_, _]]
    result.left.value.message should include("kafka-protobuf-provider")
  }

  it should "include dependency name in error for missing JSON provider" in {
    val result = Registrar.buildParsedSchema("test", """{"type":"object"}""", SchemaType.Json)
    result shouldBe a[Left[_, _]]
    result.left.value.message should include("kafka-json-schema-provider")
  }

  "Registrar.registerAll" should "pass references from RegistryRegistration to client" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"string"}""")
    val refs   = List(SchemaReference("Base", "base-subject", 1))

    when(client.register(eqTo("ref-sub"), any[ParsedSchema]())).thenReturn(1)

    val regs    = List(RegistryRegistration("ref-sub", f, SchemaType.Avro, refs))
    val results = Registrar.registerAll(client, regs)
    results should have size 1
    results.head shouldBe Right(RegisteredSchema("ref-sub", 1))
  }
}
