package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.rest.entities.{SchemaReference => ConfluentSchemaReference}
import org.apache.avro.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.file.{Files, Path}
import java.util.Collections

class DownloaderIntegrationSpec extends AnyFlatSpec with Matchers with SchemaRegistryContainerSuite {

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private def readFile(path: Path): String =
    new String(Files.readAllBytes(path))

  private val silentLogger: Logger = Logger.Null

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("downloader-it")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  }

  private def withDownloader(downloader: Downloader)(test: Downloader => Any): Unit =
    try test(downloader)
    finally downloader.close()

  private def withFixture(test: (Path, Downloader) => Any): Unit =
    withTempDir { dir =>
      withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
        test(dir, downloader)
      }
    }

  "Downloader (integration)" should "download schema by specific version" in withFixture { (dir, downloader) =>
    val subject    = "it-specific"
    val schemaJson =
      """{"type":"record","name":"ItSpecific","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
    readFile(file) shouldBe schemaJson
  }

  it should "download latest schema version and write versioned filename" in withFixture { (dir, downloader) =>
    val subject    = "it-latest"
    val schemaJson = """{"type":"record","name":"ItLatest","namespace":"org.galaxio","fields":[{"name":"v","type":"string"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))

    result shouldBe a[Right[_, _]]
    val file = dir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
    readFile(file) shouldBe schemaJson
  }

  it should "return Left with SchemaFetchFailed for missing subject" in withFixture { (_, downloader) =>
    val result = downloader.schemaSubjectToFile(RegistrySubject("does-not-exist", 1))
    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.SchemaFetchFailed]
  }

  it should "return Left with SchemaFetchFailed for missing version of existing subject" in withFixture { (_, downloader) =>
    val subject    = "it-version-miss"
    val schemaJson =
      """{"type":"record","name":"ItVersionMiss","namespace":"org.galaxio","fields":[{"name":"x","type":"int"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = downloader.schemaSubjectToFile(RegistrySubject(subject, 99))
    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.SchemaFetchFailed]
  }

  it should "produce identical output files across two downloads" in withTempDir { dir =>
    val subject    = "it-determinism"
    val schemaJson = """{"type":"record","name":"ItDet","namespace":"org.galaxio","fields":[{"name":"ts","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val dir1 = dir.resolve("run1")
    val dir2 = dir.resolve("run2")
    Files.createDirectories(dir1)
    Files.createDirectories(dir2)

    withDownloader(Downloader(registryUrl, dir1, silentLogger)) { d1 =>
      withDownloader(Downloader(registryUrl, dir2, silentLogger)) { d2 =>
        val r1 = d1.schemaSubjectToFile(RegistrySubject(subject, 1))
        val r2 = d2.schemaSubjectToFile(RegistrySubject(subject, 1))

        (r1, r2) match {
          case (Right(p1), Right(p2)) =>
            // Equal AND correct — mutual equality alone would pass if both were identically wrong.
            readFile(p1) shouldBe schemaJson
            readFile(p2) shouldBe schemaJson
          case _                      => fail("Both downloads should succeed")
        }
      }
    }
  }

  it should "download successfully when BasicAuth is configured against non-auth registry" in withTempDir { dir =>
    val subject    = "it-basic-auth"
    val schemaJson =
      """{"type":"record","name":"ItBasicAuth","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    withDownloader(
      Downloader(registryUrl, dir, silentLogger, auth = Some(SchemaRegistryAuth.BasicAuth("user", "pass"))),
    ) { downloader =>
      val result = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))

      result shouldBe a[Right[_, _]]
      val file = dir.resolve(s"$subject-1.avsc")
      Files.exists(file) shouldBe true
      readFile(file) shouldBe schemaJson
    }
  }

  it should "track multi-version evolution and download latest or pinned" in withTempDir { dir =>
    val subject = "it-evolve"
    val v1Json  =
      """{"type":"record","name":"Evolve","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    val v2Json  =
      """{"type":"record","name":"Evolve","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":["null","string"],"default":null}]}"""
    val v3Json  =
      """{"type":"record","name":"Evolve","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":["null","string"],"default":null},{"name":"ts","type":["null","long"],"default":null}]}"""

    registryClient.register(subject, avroSchema(v1Json))
    registryClient.register(subject, avroSchema(v2Json))
    registryClient.register(subject, avroSchema(v3Json))

    withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
      val latestResult = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))
      latestResult shouldBe a[Right[_, _]]
      val latestFile   = dir.resolve(s"$subject-3.avsc")
      Files.exists(latestFile) shouldBe true
      readFile(latestFile) shouldBe v3Json

      val pinnedResult = downloader.schemaSubjectToFile(RegistrySubject(subject, 2))
      pinnedResult shouldBe a[Right[_, _]]
      val pinnedFile   = dir.resolve(s"$subject-2.avsc")
      Files.exists(pinnedFile) shouldBe true
      readFile(pinnedFile) shouldBe v2Json
    }

    val v4Json =
      """{"type":"record","name":"Evolve","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":["null","string"],"default":null},{"name":"ts","type":["null","long"],"default":null},{"name":"tag","type":["null","string"],"default":null}]}"""
    registryClient.register(subject, avroSchema(v4Json))

    val dir2 = dir.resolve("latest2")
    Files.createDirectories(dir2)
    withDownloader(Downloader(registryUrl, dir2, silentLogger)) { downloader2 =>
      val latestV4Result = downloader2.schemaSubjectToFile(RegistrySubject.latest(subject))
      latestV4Result shouldBe a[Right[_, _]]
      val latestV4File   = dir2.resolve(s"$subject-4.avsc")
      Files.exists(latestV4File) shouldBe true
      readFile(latestV4File) shouldBe v4Json
    }
  }

  it should "download schema registered with references" in withTempDir { dir =>
    val baseSubject = "it-base-ref"
    val baseJson    =
      """{"type":"record","name":"Base","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(baseSubject, avroSchema(baseJson))

    val mainSubject = "it-main-ref"
    val mainJson    =
      """{"type":"record","name":"Main","namespace":"org.galaxio","fields":[{"name":"base_id","type":"long"},{"name":"value","type":"string"}]}"""
    val ref         = new ConfluentSchemaReference("Base", baseSubject, 1)
    val mainSchema  = new AvroSchema(mainJson, Collections.singletonList(ref), Collections.emptyMap[String, String](), null)
    registryClient.register(mainSubject, mainSchema)

    withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
      val result = downloader.schemaSubjectToFile(RegistrySubject.latest(mainSubject))

      result shouldBe a[Right[_, _]]
      val file = dir.resolve(s"$mainSubject-1.avsc")
      Files.exists(file) shouldBe true
      readFile(file) shouldBe mainJson

      // The referencing subject must actually carry its reference (would pass even if refs were dropped otherwise).
      val mainRefs = registryClient.getLatestSchemaMetadata(mainSubject).getReferences
      mainRefs.size shouldBe 1
      mainRefs.get(0).getSubject shouldBe baseSubject
      mainRefs.get(0).getVersion.intValue shouldBe 1
    }
  }

  it should "auto-create deeply nested output directory" in withTempDir { dir =>
    val subject    = "it-nested-dir"
    val schemaJson =
      """{"type":"record","name":"ItNestedDir","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val deepDir = dir.resolve("nested").resolve("deep").resolve("output")
    Files.exists(deepDir) shouldBe false

    withDownloader(Downloader(registryUrl, deepDir, silentLogger)) { downloader =>
      val result = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))

      result shouldBe a[Right[_, _]]
      Files.exists(deepDir) shouldBe true
      val file = deepDir.resolve(s"$subject-1.avsc")
      Files.exists(file) shouldBe true
      readFile(file) shouldBe schemaJson
    }
  }

  it should "overwrite corrupted file on re-download" in withTempDir { dir =>
    val subject    = "it-overwrite"
    val schemaJson =
      """{"type":"record","name":"ItOverwrite","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
      val first = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))
      first shouldBe a[Right[_, _]]

      val file = dir.resolve(s"$subject-1.avsc")
      Files.write(file, "corrupted".getBytes())
      readFile(file) shouldBe "corrupted"

      val second = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))
      second shouldBe a[Right[_, _]]
      readFile(file) shouldBe schemaJson
    }
  }

  it should "download Protobuf schema with .proto extension" in withFixture { (dir, downloader) =>
    val subject      = "it-dl-proto"
    val protoContent = """syntax = "proto3"; message DlProto { string value = 1; }"""
    val protoFile    = Files.createTempFile("dl-proto-", ".proto")
    protoFile.toFile.deleteOnExit()
    Files.write(protoFile, protoContent.getBytes("UTF-8"))
    val reg          = RegistryRegistration(subject, protoFile.toFile, SchemaType.Protobuf)

    val regResults = Registrar.registerAll(registryClient, List(reg))
    regResults.head shouldBe a[Right[_, _]]

    val result = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))
    result shouldBe a[Right[_, _]]
    val file   = dir.resolve(s"$subject-1.proto")
    Files.exists(file) shouldBe true
    readFile(file) should include("message DlProto") // body, not just the filename
  }

  it should "download JSON Schema with .json extension" in withFixture { (dir, downloader) =>
    val subject     = "it-dl-json"
    val jsonContent = """{"type":"object","properties":{"id":{"type":"integer"}}}"""
    val jsonFile    = Files.createTempFile("dl-json-", ".json")
    jsonFile.toFile.deleteOnExit()
    Files.write(jsonFile, jsonContent.getBytes("UTF-8"))
    val reg         = RegistryRegistration(subject, jsonFile.toFile, SchemaType.Json)

    val regResults = Registrar.registerAll(registryClient, List(reg))
    regResults.head shouldBe a[Right[_, _]]

    val result = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))
    result shouldBe a[Right[_, _]]
    val file   = dir.resolve(s"$subject-1.json")
    Files.exists(file) shouldBe true
    readFile(file) should include("\"id\"") // body, not just the filename
  }

  it should "download mixed schema types with correct extensions" in withFixture { (dir, downloader) =>
    val avroSubject  = "it-dl-mix-avro"
    val protoSubject = "it-dl-mix-proto"
    val jsonSubject  = "it-dl-mix-json"

    val avroJson =
      """{"type":"record","name":"MixAvro","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(avroSubject, avroSchema(avroJson))

    val protoFile = Files.createTempFile("mix-proto-", ".proto")
    protoFile.toFile.deleteOnExit()
    Files.write(protoFile, """syntax = "proto3"; message MixProto { string v = 1; }""".getBytes("UTF-8"))
    Registrar.registerAll(registryClient, List(RegistryRegistration(protoSubject, protoFile.toFile, SchemaType.Protobuf)))

    val jsonFile = Files.createTempFile("mix-json-", ".json")
    jsonFile.toFile.deleteOnExit()
    Files.write(jsonFile, """{"type":"object","properties":{"v":{"type":"string"}}}""".getBytes("UTF-8"))
    Registrar.registerAll(registryClient, List(RegistryRegistration(jsonSubject, jsonFile.toFile, SchemaType.Json)))

    val avroResult  = downloader.schemaSubjectToFile(RegistrySubject.latest(avroSubject))
    val protoResult = downloader.schemaSubjectToFile(RegistrySubject.latest(protoSubject))
    val jsonResult  = downloader.schemaSubjectToFile(RegistrySubject.latest(jsonSubject))

    avroResult shouldBe a[Right[_, _]]
    protoResult shouldBe a[Right[_, _]]
    jsonResult shouldBe a[Right[_, _]]

    Files.exists(dir.resolve(s"$avroSubject-1.avsc")) shouldBe true
    Files.exists(dir.resolve(s"$protoSubject-1.proto")) shouldBe true
    Files.exists(dir.resolve(s"$jsonSubject-1.json")) shouldBe true

    // Each file holds the right body, not just the right extension.
    readFile(dir.resolve(s"$avroSubject-1.avsc")) shouldBe avroJson
    readFile(dir.resolve(s"$protoSubject-1.proto")) should include("MixProto")
    readFile(dir.resolve(s"$jsonSubject-1.json")) should include("\"v\"")
  }
}
