package org.galaxio.avro

import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File

class RegistrarSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def tempFileWith(content: String): File = {
    val f = File.createTempFile("schema-", ".avsc")
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
    results(1).left.get shouldBe RegistryError.FileNotFound(f2)
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
    results.head.left.get shouldBe a[RegistryError.RegistrationFailed]
    results.head.left.get.message should include("fail-sub")
    results.head.left.get.message should include("Connection refused")
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
}
