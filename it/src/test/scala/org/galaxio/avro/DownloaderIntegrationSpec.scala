package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference
import org.apache.avro.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import sbt.util.Logger

import java.nio.file.{Files, Path}
import java.util.Collections
import scala.util.{Failure, Success}

/** Integration tests for [[Downloader]] using real Confluent Schema Registry and Kafka containers.
  *
  * Run with: sbt it/test (requires Docker)
  */
class DownloaderIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var network: Network                           = _
  private var kafka: ConfluentKafkaContainer             = _
  private var sr: JGenericContainer[_]                   = _
  private var registryUrl: String                        = _
  private var registryClient: CachedSchemaRegistryClient = _

  override def beforeAll(): Unit = {
    network = Network.newNetwork()

    kafka = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
    kafka.withListener("kafka:19092")
    kafka.withNetwork(network)
    kafka.start()

    sr = new JGenericContainer(DockerImageName.parse("confluentinc/cp-schema-registry:7.5.0"))
    sr.withNetwork(network)
    sr.withExposedPorts(8081)
    sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
    sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
    sr.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
    sr.start()

    registryUrl = s"http://${sr.getHost}:${sr.getMappedPort(8081)}"
    registryClient = new CachedSchemaRegistryClient(registryUrl, 100)
  }

  override def afterAll(): Unit = {
    try { if (sr != null) sr.stop() }
    catch { case _: Exception => }
    try { if (kafka != null) kafka.stop() }
    catch { case _: Exception => }
    try { if (network != null) network.close() }
    catch { case _: Exception => }
  }

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private val silentLogger: Logger = Logger.Null

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("downloader-it")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  }

  private def withDownloader(downloader: Downloader)(test: Downloader => Any): Unit =
    try test(downloader)
    finally downloader.close()

  "Downloader (integration)" should "download schema by specific version" in withTempDir { dir =>
    val subject    = "it-specific"
    val schemaJson =
      """{"type":"record","name":"ItSpecific","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = Downloader(registryUrl, dir, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))

    result shouldBe a[Success[_]]
    val file = dir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe schemaJson
  }

  it should "download latest schema version and write versioned filename" in withTempDir { dir =>
    val subject    = "it-latest"
    val schemaJson = """{"type":"record","name":"ItLatest","namespace":"org.galaxio","fields":[{"name":"v","type":"string"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = Downloader(registryUrl, dir, silentLogger).schemaSubjectToFile(RegistrySubject.latest(subject))

    result shouldBe a[Success[_]]
    val file = dir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe schemaJson
  }

  it should "return Failure for missing subject" in withTempDir { dir =>
    val result = Downloader(registryUrl, dir, silentLogger).schemaSubjectToFile(RegistrySubject("does-not-exist", 1))
    result shouldBe a[Failure[_]]
  }

  it should "return Failure for missing version of existing subject" in withTempDir { dir =>
    val subject    = "it-version-miss"
    val schemaJson =
      """{"type":"record","name":"ItVersionMiss","namespace":"org.galaxio","fields":[{"name":"x","type":"int"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val result = Downloader(registryUrl, dir, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 99))
    result shouldBe a[Failure[_]]
  }

  it should "produce identical output files across two downloads" in withTempDir { dir =>
    val subject    = "it-determinism"
    val schemaJson = """{"type":"record","name":"ItDet","namespace":"org.galaxio","fields":[{"name":"ts","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val dir1 = dir.resolve("run1")
    val dir2 = dir.resolve("run2")
    Files.createDirectories(dir1)
    Files.createDirectories(dir2)

    val r1 = Downloader(registryUrl, dir1, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))
    val r2 = Downloader(registryUrl, dir2, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))

    r1 shouldBe a[Success[_]]
    r2 shouldBe a[Success[_]]
    new String(Files.readAllBytes(r1.get)) shouldBe new String(Files.readAllBytes(r2.get))
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

      result shouldBe a[Success[_]]
      val file = dir.resolve(s"$subject-1.avsc")
      Files.exists(file) shouldBe true
      new String(Files.readAllBytes(file)) shouldBe schemaJson
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
      latestResult shouldBe a[Success[_]]
      val latestFile = dir.resolve(s"$subject-3.avsc")
      Files.exists(latestFile) shouldBe true
      new String(Files.readAllBytes(latestFile)) shouldBe v3Json

      val pinnedResult = downloader.schemaSubjectToFile(RegistrySubject(subject, 2))
      pinnedResult shouldBe a[Success[_]]
      val pinnedFile = dir.resolve(s"$subject-2.avsc")
      Files.exists(pinnedFile) shouldBe true
      new String(Files.readAllBytes(pinnedFile)) shouldBe v2Json
    }

    val v4Json =
      """{"type":"record","name":"Evolve","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":["null","string"],"default":null},{"name":"ts","type":["null","long"],"default":null},{"name":"tag","type":["null","string"],"default":null}]}"""
    registryClient.register(subject, avroSchema(v4Json))

    val dir2 = dir.resolve("latest2")
    Files.createDirectories(dir2)
    withDownloader(Downloader(registryUrl, dir2, silentLogger)) { downloader2 =>
      val latestV4Result = downloader2.schemaSubjectToFile(RegistrySubject.latest(subject))
      latestV4Result shouldBe a[Success[_]]
      val latestV4File = dir2.resolve(s"$subject-4.avsc")
      Files.exists(latestV4File) shouldBe true
      new String(Files.readAllBytes(latestV4File)) shouldBe v4Json
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
    val ref         = new SchemaReference("Base", baseSubject, 1)
    val mainSchema  = new AvroSchema(mainJson, Collections.singletonList(ref), Collections.emptyMap[String, String](), null)
    registryClient.register(mainSubject, mainSchema)

    withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
      val result = downloader.schemaSubjectToFile(RegistrySubject.latest(mainSubject))

      result shouldBe a[Success[_]]
      val file = dir.resolve(s"$mainSubject-1.avsc")
      Files.exists(file) shouldBe true
      new String(Files.readAllBytes(file)) shouldBe mainJson
    }
  }

  it should "auto-create deeply nested output directory" in withTempDir { dir =>
    val subject    = "it-nested-dir"
    val schemaJson =
      """{"type":"record","name":"ItNestedDir","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val deepDir = dir.resolve("nested").resolve("deep").resolve("output")
    Files.exists(deepDir) shouldBe false

    val result = Downloader(registryUrl, deepDir, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))

    result shouldBe a[Success[_]]
    Files.exists(deepDir) shouldBe true
    val file = deepDir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe schemaJson
  }

  it should "overwrite corrupted file on re-download" in withTempDir { dir =>
    val subject    = "it-overwrite"
    val schemaJson =
      """{"type":"record","name":"ItOverwrite","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    withDownloader(Downloader(registryUrl, dir, silentLogger)) { downloader =>
      val first = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))
      first shouldBe a[Success[_]]

      val file = dir.resolve(s"$subject-1.avsc")
      Files.write(file, "corrupted".getBytes())
      new String(Files.readAllBytes(file)) shouldBe "corrupted"

      val second = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))
      second shouldBe a[Success[_]]
      new String(Files.readAllBytes(file)) shouldBe schemaJson
    }
  }
}
