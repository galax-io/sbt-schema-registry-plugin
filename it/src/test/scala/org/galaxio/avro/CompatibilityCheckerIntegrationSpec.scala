package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

import java.io.File
import java.nio.file.Files
import scala.util.Try

class CompatibilityCheckerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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
    val f = File.createTempFile("compat-it-", suffix)
    f.deleteOnExit()
    Files.write(f.toPath, content.getBytes("UTF-8"))
    f
  }

  "CompatibilityChecker (integration)" should "return Compatible for a backward-compatible change" in {
    val v1 =
      """{"type":"record","name":"CompatV1","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    val v2 =
      """{"type":"record","name":"CompatV1","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":"string","default":""}]}"""

    registryClient.register("compat-check-pass", new AvroSchema(v1))

    val file   = tempSchemaFile(v2)
    val result = CompatibilityChecker.checkOne(registryClient, RegistryRegistration("compat-check-pass", file))
    result shouldBe CompatibilityResult.Compatible("compat-check-pass")
  }

  it should "return Incompatible with verbose messages for a breaking change" in {
    val v1 =
      """{"type":"record","name":"CompatV2","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    val v2breaking =
      """{"type":"record","name":"CompatV2","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"name","type":"string"}]}"""

    registryClient.register("compat-check-fail", new AvroSchema(v1))

    val file   = tempSchemaFile(v2breaking)
    val result = CompatibilityChecker.checkOne(registryClient, RegistryRegistration("compat-check-fail", file))
    result shouldBe a[CompatibilityResult.Incompatible]
    val incompatible = result.asInstanceOf[CompatibilityResult.Incompatible]
    incompatible.subject shouldBe "compat-check-fail"
    incompatible.messages should not be empty
    // The break is a new required field with no default — the verbose verdict must say so.
    incompatible.messages.exists(_.toLowerCase.contains("default")) shouldBe true
  }

  it should "return Compatible for a brand-new subject with no prior versions" in {
    val schema =
      """{"type":"record","name":"BrandNew","namespace":"org.galaxio","fields":[{"name":"x","type":"int"}]}"""
    val file   = tempSchemaFile(schema)
    val result = CompatibilityChecker.checkOne(registryClient, RegistryRegistration("compat-new-subject", file))
    result shouldBe CompatibilityResult.Compatible("compat-new-subject")
  }

  // Pure parse-failure / missing-file paths fail before any registry call — owned by
  // CompatibilityCheckerSpec ("return Failed when schema content is invalid" / "...when file does
  // not exist"). Removed from the IT layer to avoid duplicating registry-free logic.

  it should "return Compatible for backward-compatible Protobuf change" in {
    val v1 =
      """syntax = "proto3";
        |message ProtoCompat {
        |  string id = 1;
        |}""".stripMargin
    val v1File = tempSchemaFile(v1, ".proto")
    val v1Reg  = RegistryRegistration("compat-proto-pass", v1File, SchemaType.Protobuf)
    Registrar.registerAll(registryClient, List(v1Reg))

    val v2 =
      """syntax = "proto3";
        |message ProtoCompat {
        |  string id = 1;
        |  string name = 2;
        |}""".stripMargin
    val v2File = tempSchemaFile(v2, ".proto")
    val result = CompatibilityChecker.checkOne(
      registryClient,
      RegistryRegistration("compat-proto-pass", v2File, SchemaType.Protobuf),
    )
    result shouldBe CompatibilityResult.Compatible("compat-proto-pass")
  }

  it should "return Compatible for backward-compatible JSON Schema change" in {
    val v1 = """{"type":"object","properties":{"id":{"type":"integer"}},"additionalProperties":false}"""
    val v1File = tempSchemaFile(v1, ".json")
    val v1Reg  = RegistryRegistration("compat-json-pass", v1File, SchemaType.Json)
    Registrar.registerAll(registryClient, List(v1Reg))

    val v2     = """{"type":"object","properties":{"id":{"type":"integer"},"name":{"type":"string"}},"additionalProperties":false}"""
    val v2File = tempSchemaFile(v2, ".json")
    val result = CompatibilityChecker.checkOne(
      registryClient,
      RegistryRegistration("compat-json-pass", v2File, SchemaType.Json),
    )
    result shouldBe CompatibilityResult.Compatible("compat-json-pass")
  }

  it should "check all subjects independently in a batch" in {
    val v1 =
      """{"type":"record","name":"BatchTest","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
    registryClient.register("compat-batch", new AvroSchema(v1))

    val compatible =
      """{"type":"record","name":"BatchTest","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"extra","type":"string","default":""}]}"""
    val incompatible =
      """{"type":"record","name":"BatchTest","namespace":"org.galaxio","fields":[{"name":"id","type":"long"},{"name":"required_field","type":"string"}]}"""

    val report = CompatibilityChecker.checkAll(
      registryClient,
      List(
        RegistryRegistration("compat-batch", tempSchemaFile(compatible)),
        RegistryRegistration("compat-batch", tempSchemaFile(incompatible)),
      ),
    )

    report.compatible should have size 1
    report.incompatible should have size 1
    report.failed shouldBe empty
    // Counts alone don't prove WHICH registration landed where, nor that the verdict carries detail.
    report.compatible.head.subject shouldBe "compat-batch"
    report.incompatible.head.subject shouldBe "compat-batch"
    report.incompatible.head.messages should not be empty
    report.isSuccess shouldBe false
  }
}
