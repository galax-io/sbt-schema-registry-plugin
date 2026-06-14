package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{SchemaMetadata, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import org.mockito.ArgumentMatchers.{anyBoolean, anyInt, anyString}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.Collections
import scala.util.{Failure, Success}

class DownloaderSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def testLogger: Logger = {
    val log = mock[Logger]
    log
  }

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("downloader-test")
    try test(dir)
    finally {
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
    }
  }

  private def schemaEntity(subject: String, version: Int, schemaStr: String): Schema = {
    val s = new Schema(subject, version, 1, "AVRO", Collections.emptyList(), schemaStr)
    s
  }

  "Downloader" should "download schema with specific version" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("test-subject", 2, """{"type":"record","name":"Test","fields":[]}""")
    when(client.getByVersion("test-subject", 2, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("test-subject", 2))

    result shouldBe a[Success[_]]
    val file = dir.resolve("test-subject-2.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe """{"type":"record","name":"Test","fields":[]}"""
  }

  it should "download latest version when version is None" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 5, "AVRO", Collections.emptyList(), """{"type":"string"}""")
    when(client.getLatestSchemaMetadata("test-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("test-subject"))

    result shouldBe a[Success[_]]
    val file = dir.resolve("test-subject-5.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe """{"type":"string"}"""
  }

  it should "create output directory if it does not exist" in withTempDir { dir =>
    val outputDir = dir.resolve("nested").resolve("output")
    val client    = mock[SchemaRegistryClient]
    val schema    = schemaEntity("test", 1, """{"type":"null"}""")
    when(client.getByVersion("test", 1, false)).thenReturn(schema)

    val downloader = new Downloader(client, outputDir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("test", 1))

    result shouldBe a[Success[_]]
    Files.exists(outputDir) shouldBe true
    Files.exists(outputDir.resolve("test-1.avsc")) shouldBe true
  }

  it should "return Failure on network error" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion(anyString(), anyInt(), anyBoolean()))
      .thenThrow(new IOException("Connection refused"))

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("fail-subject", 1))

    result shouldBe a[Failure[_]]
    result.failed.get.getMessage shouldBe "Connection refused"
  }

  it should "return Failure when schema not found" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion(anyString(), anyInt(), anyBoolean()))
      .thenThrow(new RuntimeException("Subject not found"))

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("missing", 1))

    result shouldBe a[Failure[_]]
  }

  it should "use correct file extension" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("my.schema", 3, """{}""")
    when(client.getByVersion("my.schema", 3, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    downloader.schemaSubjectToFile(RegistrySubject("my.schema", 3))

    Files.exists(dir.resolve("my.schema-3.avsc")) shouldBe true
  }

  "Downloader.buildConfig" should "set BasicAuth credentials" in {
    val config = Downloader.buildConfig(
      Some(SchemaRegistryAuth.BasicAuth("alice", "s3cret")),
      Map.empty,
    )

    config.get("basic.auth.credentials.source") shouldBe "USER_INFO"
    config.get("basic.auth.user.info") shouldBe "alice:s3cret"
  }

  it should "pass through arbitrary properties unchanged" in {
    val config = Downloader.buildConfig(
      None,
      Map(
        "schema.registry.ssl.truststore.location" -> "/etc/pki/trust.jks",
        "schema.registry.ssl.truststore.password" -> "changeit",
      ),
    )

    config.get("schema.registry.ssl.truststore.location") shouldBe "/etc/pki/trust.jks"
    config.get("schema.registry.ssl.truststore.password") shouldBe "changeit"
    config.size() shouldBe 2
  }

  it should "merge auth and properties without key loss" in {
    val config = Downloader.buildConfig(
      Some(SchemaRegistryAuth.BasicAuth("bob", "pw")),
      Map("custom.prop" -> "val"),
    )

    config.get("basic.auth.credentials.source") shouldBe "USER_INFO"
    config.get("basic.auth.user.info") shouldBe "bob:pw"
    config.get("custom.prop") shouldBe "val"
    config.size() shouldBe 3
  }

  it should "return empty map when no auth and no properties" in {
    val config = Downloader.buildConfig(None, Map.empty)
    config shouldBe empty
  }

  "Downloader" should "reject subject name with forward slash" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("a/b", 1))

    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("path separators")
  }

  it should "reject subject name with backslash" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("a\\b", 1))

    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("path separators")
  }

  it should "close should be safe to call multiple times" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)

    noException should be thrownBy {
      downloader.close()
      downloader.close()
    }
  }

  it should "latest download should use resolved version number in filename" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 7, "AVRO", Collections.emptyList(), """{"type":"int"}""")
    when(client.getLatestSchemaMetadata("my-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("my-subject"))

    result shouldBe a[Success[_]]
    val file = dir.resolve("my-subject-7.avsc")
    Files.exists(file) shouldBe true
    Files.exists(dir.resolve("my-subject-latest.avsc")) shouldBe false
  }

  it should "empty schema body should write zero-byte file" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("empty-schema", 1, "")
    when(client.getByVersion("empty-schema", 1, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("empty-schema", 1))

    result shouldBe a[Success[_]]
    val file = dir.resolve("empty-schema-1.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe ""
  }

}
