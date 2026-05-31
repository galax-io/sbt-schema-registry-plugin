package org.galaxio.avro

import org.apache.avro.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, KafkaContainer}
import org.testcontainers.utility.DockerImageName
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import sbt.util.Logger

import java.nio.file.Files
import scala.util.{Failure, Success}

/** Integration tests for [[Downloader]] using real Confluent Schema Registry and Kafka containers.
  *
  * These tests verify:
  *   - Happy path: schema retrieval by specific version
  *   - Happy path: schema retrieval by "latest"
  *   - Failure path: missing subject returns a Failure
  *   - Determinism: downloading the same schema twice produces identical file contents
  *
  * Run with: sbt IntegrationTest/test  (requires Docker)
  */
class DownloaderIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var kafka: KafkaContainer           = _
  private var sr: JGenericContainer[_]        = _
  private var registryUrl: String             = _
  private var registryClient: CachedSchemaRegistryClient = _

  override def beforeAll(): Unit = {
    kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
    kafka.start()

    sr = new JGenericContainer(DockerImageName.parse("confluentinc/cp-schema-registry:7.5.0"))
    sr.withExposedPorts(8081)
    sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
    sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", kafka.getBootstrapServers)
    sr.start()

    registryUrl    = s"http://${sr.getHost}:${sr.getMappedPort(8081)}"
    registryClient = new CachedSchemaRegistryClient(registryUrl, 100)
  }

  override def afterAll(): Unit = {
    if (sr    != null) sr.stop()
    if (kafka != null) kafka.stop()
  }

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private def silentLogger: Logger = Logger.Null

  "Downloader (integration)" should "download schema by specific version" in {
    val subject    = "it-specific"
    val schemaJson = """{"type":"record","name":"ItSpecific","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val dir    = Files.createTempDirectory("it-specific")
    val dl     = Downloader(registryUrl, dir, silentLogger)
    val result = dl.schemaSubjectToFile(RegistrySubject(subject, 1))

    result shouldBe a[Success[_]]
    val file = dir.resolve(s"$subject-1.avsc")
    Files.exists(file) shouldBe true
  }

  it should "download latest schema version" in {
    val subject    = "it-latest"
    val schemaJson = """{"type":"record","name":"ItLatest","namespace":"org.galaxio","fields":[{"name":"v","type":"string"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val dir    = Files.createTempDirectory("it-latest")
    val dl     = Downloader(registryUrl, dir, silentLogger)
    val result = dl.schemaSubjectToFile(RegistrySubject.latest(subject))

    result shouldBe a[Success[_]]
  }

  it should "return Failure for missing subject" in {
    val dir    = Files.createTempDirectory("it-missing")
    val dl     = Downloader(registryUrl, dir, silentLogger)
    val result = dl.schemaSubjectToFile(RegistrySubject("does-not-exist", 1))

    result shouldBe a[Failure[_]]
  }

  it should "produce identical files across two downloads" in {
    val subject    = "it-determinism"
    val schemaJson = """{"type":"record","name":"ItDet","namespace":"org.galaxio","fields":[{"name":"ts","type":"long"}]}"""
    registryClient.register(subject, avroSchema(schemaJson))

    val dir1 = Files.createTempDirectory("it-det-1")
    val dir2 = Files.createTempDirectory("it-det-2")

    val r1 = Downloader(registryUrl, dir1, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))
    val r2 = Downloader(registryUrl, dir2, silentLogger).schemaSubjectToFile(RegistrySubject(subject, 1))

    r1 shouldBe a[Success[_]]
    r2 shouldBe a[Success[_]]
    new String(Files.readAllBytes(r1.get)) shouldBe new String(Files.readAllBytes(r2.get))
  }
}
