package org.galaxio.avro

import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.util.{Collections, Arrays => JArrays}

class CompatibilityCheckerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def tempFileWith(content: String): File = {
    val f = File.createTempFile("compat-", ".avsc")
    f.deleteOnExit()
    java.nio.file.Files.write(f.toPath, content.getBytes("UTF-8"))
    f
  }

  "CompatibilityChecker.checkOne" should "return Compatible when testCompatibilityVerbose returns empty list" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"record","name":"Test","fields":[{"name":"id","type":"long"}]}""")

    when(client.testCompatibilityVerbose(eqTo("test-subject"), any[ParsedSchema]()))
      .thenReturn(Collections.emptyList[String]())

    val result = CompatibilityChecker.checkOne(client, RegistryRegistration("test-subject", f))
    result shouldBe CompatibilityResult.Compatible("test-subject")
  }

  it should "return Incompatible with messages when testCompatibilityVerbose returns non-empty list" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"record","name":"Test","fields":[{"name":"id","type":"long"}]}""")

    when(client.testCompatibilityVerbose(eqTo("test-subject"), any[ParsedSchema]()))
      .thenReturn(JArrays.asList("new required field has no default value", "field removed"))

    val result       = CompatibilityChecker.checkOne(client, RegistryRegistration("test-subject", f))
    result shouldBe a[CompatibilityResult.Incompatible]
    val incompatible = result.asInstanceOf[CompatibilityResult.Incompatible]
    incompatible.subject shouldBe "test-subject"
    incompatible.messages should contain allOf ("new required field has no default value", "field removed")
  }

  it should "return Failed when file does not exist" in {
    val client = mock[SchemaRegistryClient]
    val result = CompatibilityChecker.checkOne(
      client,
      RegistryRegistration("test-subject", new File("/nonexistent/schema.avsc")),
    )
    result shouldBe a[CompatibilityResult.Failed]
    result.asInstanceOf[CompatibilityResult.Failed].cause shouldBe a[RegistryError.FileNotFound]
  }

  it should "return Failed when client throws exception" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"record","name":"Test","fields":[{"name":"id","type":"long"}]}""")

    when(client.testCompatibilityVerbose(eqTo("test-subject"), any[ParsedSchema]()))
      .thenThrow(new RuntimeException("Connection refused"))

    val result = CompatibilityChecker.checkOne(client, RegistryRegistration("test-subject", f))
    result shouldBe a[CompatibilityResult.Failed]
    val failed = result.asInstanceOf[CompatibilityResult.Failed]
    failed.cause.message should include("Connection refused")
  }

  it should "return Failed when schema content is invalid" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("not valid json {{{")

    val result = CompatibilityChecker.checkOne(client, RegistryRegistration("test-subject", f))
    result shouldBe a[CompatibilityResult.Failed]
    result.asInstanceOf[CompatibilityResult.Failed].cause shouldBe a[RegistryError.RegistrationFailed]
  }

  "CompatibilityChecker.checkAll" should "process all subjects independently" in {
    val client = mock[SchemaRegistryClient]
    val f1     = tempFileWith("""{"type":"record","name":"A","fields":[{"name":"id","type":"long"}]}""")
    val f2     = tempFileWith("""{"type":"record","name":"B","fields":[{"name":"id","type":"long"}]}""")

    when(client.testCompatibilityVerbose(eqTo("sub1"), any[ParsedSchema]()))
      .thenReturn(Collections.emptyList[String]())
    when(client.testCompatibilityVerbose(eqTo("sub2"), any[ParsedSchema]()))
      .thenReturn(JArrays.asList("incompatible change"))

    val report = CompatibilityChecker.checkAll(
      client,
      List(RegistryRegistration("sub1", f1), RegistryRegistration("sub2", f2)),
    )

    report.results should have size 2
    report.compatible should have size 1
    report.incompatible should have size 1
    report.failed shouldBe empty
    report.isSuccess shouldBe false
  }

  it should "return empty report with isSuccess true for empty registrations" in {
    val client = mock[SchemaRegistryClient]
    val report = CompatibilityChecker.checkAll(client, List.empty)
    report.results shouldBe empty
    report.isSuccess shouldBe true
  }

  "CompatibilityReport" should "partition results correctly" in {
    val report = CompatibilityReport(
      List(
        CompatibilityResult.Compatible("a"),
        CompatibilityResult.Incompatible("b", List("msg")),
        CompatibilityResult.Failed("c", RegistryError.FileNotFound(new File("x"))),
        CompatibilityResult.Compatible("d"),
      ),
    )

    report.compatible should have size 2
    report.incompatible should have size 1
    report.failed should have size 1
    report.isSuccess shouldBe false
  }

  it should "report isSuccess true when all compatible" in {
    val report = CompatibilityReport(
      List(
        CompatibilityResult.Compatible("a"),
        CompatibilityResult.Compatible("b"),
      ),
    )
    report.isSuccess shouldBe true
  }

  it should "return Failed for Protobuf schema when provider not on classpath" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""syntax = "proto3";""")
    val result = CompatibilityChecker.checkOne(
      client,
      RegistryRegistration("proto-compat", f, SchemaType.Protobuf),
    )
    result shouldBe a[CompatibilityResult.Failed]
    result.asInstanceOf[CompatibilityResult.Failed].cause shouldBe a[RegistryError.RegistrationFailed]
    result.asInstanceOf[CompatibilityResult.Failed].cause.message should include("Schema provider not on classpath")
  }

  it should "return Failed for JSON Schema when provider not on classpath" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"object"}""")
    val result = CompatibilityChecker.checkOne(
      client,
      RegistryRegistration("json-compat", f, SchemaType.Json),
    )
    result shouldBe a[CompatibilityResult.Failed]
    result.asInstanceOf[CompatibilityResult.Failed].cause shouldBe a[RegistryError.RegistrationFailed]
    result.asInstanceOf[CompatibilityResult.Failed].cause.message should include("Schema provider not on classpath")
  }

  it should "pass references through to buildParsedSchema" in {
    val client = mock[SchemaRegistryClient]
    val f      = tempFileWith("""{"type":"record","name":"Test","fields":[{"name":"id","type":"long"}]}""")
    val refs   = List(SchemaReference("Base", "base-subject", 1))

    when(client.testCompatibilityVerbose(eqTo("ref-compat"), any[ParsedSchema]()))
      .thenReturn(Collections.emptyList[String]())

    val result = CompatibilityChecker.checkOne(
      client,
      RegistryRegistration("ref-compat", f, SchemaType.Avro, refs),
    )
    result shouldBe CompatibilityResult.Compatible("ref-compat")
  }

  "CompatibilityChecker (empty config)" should "warn and succeed when no registrations configured" in {
    val client = mock[SchemaRegistryClient]
    val report = CompatibilityChecker.checkAll(client, List.empty)
    report.isSuccess shouldBe true
  }
}
