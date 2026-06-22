package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.{SchemaMetadata, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import org.mockito.ArgumentMatchers.{anyBoolean, anyInt, anyString}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.Collections

class DownloaderSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def testLogger: Logger = mock[Logger]

  private def readFile(path: Path): String =
    new String(Files.readAllBytes(path))

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("downloader-test")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
  }

  private def schemaEntity(subject: String, version: Int, schemaStr: String): Schema =
    new Schema(subject, version, 1, "AVRO", Collections.emptyList(), schemaStr)

  "Downloader" should "download schema with specific version" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("test-subject", 2, """{"type":"record","name":"Test","fields":[]}""")
    when(client.getByVersion("test-subject", 2, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("test-subject", 2))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve("test-subject-2.avsc")
    Files.exists(file) shouldBe true
    readFile(file) shouldBe """{"type":"record","name":"Test","fields":[]}"""
  }

  it should "download latest version when version is None" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 5, "AVRO", Collections.emptyList(), """{"type":"string"}""")
    when(client.getLatestSchemaMetadata("test-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("test-subject"))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve("test-subject-5.avsc")
    Files.exists(file) shouldBe true
    readFile(file) shouldBe """{"type":"string"}"""
  }

  it should "create output directory if it does not exist" in withTempDir { dir =>
    val outputDir = dir.resolve("nested").resolve("output")
    val client    = mock[SchemaRegistryClient]
    val schema    = schemaEntity("test", 1, """{"type":"null"}""")
    when(client.getByVersion("test", 1, false)).thenReturn(schema)

    val downloader = new Downloader(client, outputDir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("test", 1))

    result shouldBe a[Right[_, _]]
    Files.exists(outputDir) shouldBe true
    Files.exists(outputDir.resolve("test-1.avsc")) shouldBe true
  }

  it should "return Left with SchemaFetchFailed on network error" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion(anyString(), anyInt(), anyBoolean()))
      .thenThrow(new IOException("Connection refused"))

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("fail-subject", 1))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.SchemaFetchFailed]
    result.left.value.message should include("Connection refused")
  }

  it should "return Left with SchemaFetchFailed when schema not found" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion(anyString(), anyInt(), anyBoolean()))
      .thenThrow(new RuntimeException("Subject not found"))

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("missing", 1))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.SchemaFetchFailed]
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

    config("basic.auth.credentials.source") shouldBe "USER_INFO"
    config("basic.auth.user.info") shouldBe "alice:s3cret"
  }

  it should "pass through arbitrary properties unchanged" in {
    val config = Downloader.buildConfig(
      None,
      Map(
        "schema.registry.ssl.truststore.location" -> "/etc/pki/trust.jks",
        "schema.registry.ssl.truststore.password" -> "changeit",
      ),
    )

    config("schema.registry.ssl.truststore.location") shouldBe "/etc/pki/trust.jks"
    config("schema.registry.ssl.truststore.password") shouldBe "changeit"
    config.size shouldBe 2
  }

  it should "merge auth and properties without key loss" in {
    val config = Downloader.buildConfig(
      Some(SchemaRegistryAuth.BasicAuth("bob", "pw")),
      Map("custom.prop" -> "val"),
    )

    config("basic.auth.credentials.source") shouldBe "USER_INFO"
    config("basic.auth.user.info") shouldBe "bob:pw"
    config("custom.prop") shouldBe "val"
    config.size shouldBe 3
  }

  it should "return empty map when no auth and no properties" in {
    val config = Downloader.buildConfig(None, Map.empty)
    config shouldBe empty
  }

  "Downloader" should "return Left with InvalidSubjectName for forward slash" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("a/b", 1))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.InvalidSubjectName]
    result.left.value.message should include("path separators")
  }

  it should "return Left with InvalidSubjectName for backslash" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("a\\b", 1))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.InvalidSubjectName]
    result.left.value.message should include("path separators")
  }

  it should "return Left with WriteError when output path is not writable" in withTempDir { dir =>
    val blocker = dir.resolve("blocker")
    Files.createFile(blocker)

    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("test", 1, """{"type":"null"}""")
    when(client.getByVersion("test", 1, false)).thenReturn(schema)

    val downloader = new Downloader(client, blocker.resolve("sub"), testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("test", 1))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.WriteError]
  }

  it should "be safe to call close multiple times" in withTempDir { dir =>
    val client     = mock[SchemaRegistryClient]
    val downloader = new Downloader(client, dir, testLogger)

    noException should be thrownBy {
      downloader.close()
      downloader.close()
    }
  }

  it should "use resolved version number in filename for latest" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 7, "AVRO", Collections.emptyList(), """{"type":"int"}""")
    when(client.getLatestSchemaMetadata("my-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("my-subject"))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve("my-subject-7.avsc")
    Files.exists(file) shouldBe true
    Files.exists(dir.resolve("my-subject-latest.avsc")) shouldBe false
  }

  it should "write zero-byte file for empty schema body" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = schemaEntity("empty-schema", 1, "")
    when(client.getByVersion("empty-schema", 1, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("empty-schema", 1))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve("empty-schema-1.avsc")
    Files.exists(file) shouldBe true
    readFile(file) shouldBe ""
  }

  it should "download schema with Protobuf type using .proto extension" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 3, "PROTOBUF", Collections.emptyList(), """syntax = "proto3";""")
    when(client.getLatestSchemaMetadata("proto-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("proto-subject"))

    result shouldBe a[Right[_, _]]
    Files.exists(dir.resolve("proto-subject-3.proto")) shouldBe true
  }

  it should "download schema with JSON type using .json extension" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 2, "JSON", Collections.emptyList(), """{"type":"object"}""")
    when(client.getLatestSchemaMetadata("json-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("json-subject"))

    result shouldBe a[Right[_, _]]
    Files.exists(dir.resolve("json-subject-2.json")) shouldBe true
  }

  it should "download schema with null type using .avsc extension for backward compatibility" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 1, null, Collections.emptyList(), """{"type":"string"}""")
    when(client.getLatestSchemaMetadata("null-type-subject")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("null-type-subject"))

    result shouldBe a[Right[_, _]]
    Files.exists(dir.resolve("null-type-subject-1.avsc")) shouldBe true
  }

  it should "return error for unknown schema type" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 1, "GRAPHQL", Collections.emptyList(), "type Query {}")
    when(client.getLatestSchemaMetadata("unknown-type")).thenReturn(meta)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest("unknown-type"))

    result shouldBe a[Left[_, _]]
    result.left.value shouldBe a[DownloadError.UnsupportedSchemaType]
  }

  it should "download pinned schema with Protobuf type using .proto extension" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val schema = new Schema("proto-pinned", 2, 1, "PROTOBUF", Collections.emptyList(), """syntax = "proto3";""")
    when(client.getByVersion("proto-pinned", 2, false)).thenReturn(schema)

    val downloader = new Downloader(client, dir, testLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject("proto-pinned", 2))

    result shouldBe a[Right[_, _]]
    Files.exists(dir.resolve("proto-pinned-2.proto")) shouldBe true
  }
}
