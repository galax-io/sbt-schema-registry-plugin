package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.apache.avro.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, KafkaContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import sbt.util.Logger

import java.nio.file.{Files, Path}
import scala.util.{Failure, Success}

/** Integration tests for [[Downloader]] using real Confluent Schema Registry and Kafka containers.
  *
  * Run with: sbt IntegrationTest/test (requires Docker)
  */
class DownloaderIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var network: Network                           = _
  private var kafka: KafkaContainer                      = _
  private var sr: JGenericContainer[_]                   = _
  private var registryUrl: String                        = _
  private var registryClient: CachedSchemaRegistryClient = _

  override def beforeAll(): Unit = {
    network = Network.newNetwork()

    kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
    kafka.withNetwork(network)
    kafka.withNetworkAliases("kafka")
    kafka.start()

    sr = new JGenericContainer(DockerImageName.parse("confluentinc/cp-schema-registry:7.5.0"))
    sr.withNetwork(network)
    sr.withExposedPorts(8081)
    sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
    sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
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
}
