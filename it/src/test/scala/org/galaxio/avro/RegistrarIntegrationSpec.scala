package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import sbt.util.Logger

import java.io.File
import java.nio.file.{Files, Path}
import scala.util.Try

class RegistrarIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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
    Option(registryClient).foreach(c => Try(c.close()))
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }

  private def tempSchemaFile(content: String, suffix: String = ".avsc"): File = {
    val f = File.createTempFile("schema-it-", suffix)
    f.deleteOnExit()
    Files.write(f.toPath, content.getBytes("UTF-8"))
    f
  }

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("registrar-it")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  }

  "Registrar (integration)" should "register an Avro schema and return schema ID" in {
    val schemaJson =
      """{"type":"record","name":"RegTest","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    val file = tempSchemaFile(schemaJson)

    val results = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-register-test", file)),
    )

    results should have size 1
    results.head shouldBe a[Right[_, _]]
    val registered = results.head.toOption.get
    registered.subject shouldBe "it-register-test"
    registered.schemaId should be > 0
  }

  it should "return same schema ID for idempotent registration" in {
    val schemaJson =
      """{"type":"record","name":"IdempotentTest","namespace":"org.galaxio","fields":[{"name":"v","type":"string"}]}"""
    val file = tempSchemaFile(schemaJson)
    val reg  = List(RegistryRegistration("it-idempotent", file))

    val first  = Registrar.registerAll(registryClient, reg)
    val second = Registrar.registerAll(registryClient, reg)

    first.head.toOption.get.schemaId shouldBe second.head.toOption.get.schemaId
  }

  it should "return FileNotFound for missing schema file" in {
    val results = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-missing-file", new File("/nonexistent/schema.avsc"))),
    )

    results should have size 1
    results.head shouldBe a[Left[_, _]]
    results.head.left.get shouldBe a[RegistryError.FileNotFound]
  }

  it should "return RegistrationFailed for invalid schema content" in {
    val file = tempSchemaFile("not valid avro json {{{")

    val results = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-invalid-schema", file)),
    )

    results should have size 1
    results.head shouldBe a[Left[_, _]]
    results.head.left.get shouldBe a[RegistryError.RegistrationFailed]
  }

  it should "register a Protobuf schema with correct type" in {
    val protoContent =
      """syntax = "proto3";
        |
        |message ProtoRegTest {
        |  string value = 1;
        |}""".stripMargin
    val file = tempSchemaFile(protoContent, ".proto")

    val results = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-register-proto", file, SchemaType.Protobuf)),
    )

    results should have size 1
    results.head shouldBe a[Right[_, _]]
    val registered = results.head.toOption.get
    registered.subject shouldBe "it-register-proto"
    registered.schemaId should be > 0

    val meta = registryClient.getLatestSchemaMetadata("it-register-proto")
    meta.getSchemaType shouldBe "PROTOBUF"
  }

  it should "register a JSON Schema with correct type" in {
    val jsonContent =
      """{
        |  "type": "object",
        |  "properties": {
        |    "value": { "type": "string" }
        |  }
        |}""".stripMargin
    val file = tempSchemaFile(jsonContent, ".json")

    val results = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-register-json", file, SchemaType.Json)),
    )

    results should have size 1
    results.head shouldBe a[Right[_, _]]
    val registered = results.head.toOption.get
    registered.subject shouldBe "it-register-json"
    registered.schemaId should be > 0

    val meta = registryClient.getLatestSchemaMetadata("it-register-json")
    meta.getSchemaType shouldBe "JSON"
  }

  it should "register Protobuf schema with references" in {
    val baseContent =
      """syntax = "proto3";
        |message BaseMsg {
        |  string id = 1;
        |}""".stripMargin
    val baseFile = tempSchemaFile(baseContent, ".proto")
    val baseRegs = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-proto-ref-base", baseFile, SchemaType.Protobuf)),
    )
    baseRegs.head shouldBe a[Right[_, _]]

    val depContent =
      """syntax = "proto3";
        |import "BaseMsg.proto";
        |message DepMsg {
        |  string value = 1;
        |}""".stripMargin
    val depFile = tempSchemaFile(depContent, ".proto")
    val depRefs = List(SchemaReference("BaseMsg.proto", "it-proto-ref-base", 1))
    val depRegs = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-proto-ref-dep", depFile, SchemaType.Protobuf, depRefs)),
    )
    depRegs.head shouldBe a[Right[_, _]]
  }

  it should "register JSON Schema with references" in {
    val baseContent = """{"type":"object","properties":{"id":{"type":"integer"}}}"""
    val baseFile    = tempSchemaFile(baseContent, ".json")
    val baseRegs = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-json-ref-base", baseFile, SchemaType.Json)),
    )
    baseRegs.head shouldBe a[Right[_, _]]

    val depContent =
      """{
        |  "type": "object",
        |  "properties": {
        |    "base": { "$ref": "base.json" },
        |    "value": { "type": "string" }
        |  }
        |}""".stripMargin
    val depFile = tempSchemaFile(depContent, ".json")
    val depRefs = List(SchemaReference("base.json", "it-json-ref-base", 1))
    val depRegs = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration("it-json-ref-dep", depFile, SchemaType.Json, depRefs)),
    )
    depRegs.head shouldBe a[Right[_, _]]
  }

  it should "support round-trip: register then download matches" in withTempDir { dir =>
    val schemaJson =
      """{"type":"record","name":"RoundTrip","namespace":"org.galaxio","fields":[{"name":"ts","type":"long"}]}"""
    val file    = tempSchemaFile(schemaJson)
    val subject = "it-round-trip"

    val regResults = Registrar.registerAll(
      registryClient,
      List(RegistryRegistration(subject, file)),
    )
    regResults.head shouldBe a[Right[_, _]]

    val downloader = Downloader(registryUrl, dir, Logger.Null)
    try {
      val dlResult = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))
      dlResult shouldBe a[Right[_, _]]

      val downloadedContent = new String(Files.readAllBytes(dlResult.toOption.get))
      downloadedContent shouldBe schemaJson
    } finally downloader.close()
  }
}
