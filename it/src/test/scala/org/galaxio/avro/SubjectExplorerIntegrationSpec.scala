package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.apache.avro.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.util.Try

class SubjectExplorerIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var network: Network                           = _
  private var kafka: ConfluentKafkaContainer             = _
  private var sr: JGenericContainer[_]                   = _
  private var registryUrl: String                        = _
  private var registryClient: CachedSchemaRegistryClient = _

  private val orderSubject = "it.e2e.Order-value"
  private val userSubject  = "it.e2e.User-value"

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

    registerTestSubjects()
  }

  override def afterAll(): Unit = {
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }

  private def avro(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private def registerTestSubjects(): Unit = {
    // Order: two backward-compatible versions (adding a field with a default is backward compatible).
    registryClient.register(
      orderSubject,
      avro("""{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"}]}"""),
    )
    registryClient.register(
      orderSubject,
      avro(
        """{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"},{"name":"note","type":"string","default":""}]}""",
      ),
    )
    // User: single version, no subject-level compatibility override.
    registryClient.register(
      userSubject,
      avro("""{"type":"record","name":"User","namespace":"it.e2e","fields":[{"name":"name","type":"string"}]}"""),
    )
    // Subject-level compatibility override on Order only.
    registryClient.updateCompatibility(orderSubject, "BACKWARD")
  }

  "SubjectExplorer.listAll (integration)" should "list all subjects sorted with correct version ranges" in {
    val result = SubjectExplorer.listAll(registryClient, None)

    result shouldBe a[Right[_, _]]
    val listing = result.right.get
    listing.subjects.map(_.name) shouldBe List(orderSubject, userSubject)

    val order = listing.subjects.find(_.name == orderSubject).get
    order.versions shouldBe List(1, 2)
    order.versionRange shouldBe "1..2"

    val user = listing.subjects.find(_.name == userSubject).get
    user.versionRange shouldBe "1"
  }

  it should "surface a subject-level compatibility override and report None for subjects without one" in {
    val listing = SubjectExplorer.listAll(registryClient, None).right.get
    listing.subjects.find(_.name == orderSubject).get.compatibility shouldBe Some("BACKWARD")
    listing.subjects.find(_.name == userSubject).get.compatibility shouldBe None
  }

  it should "produce the same result with parallelism > 1 as sequential" in {
    val seq = SubjectExplorer.listAll(registryClient, None).right.get
    val par = SubjectExplorer.listAll(registryClient, None, parallelism = 4).right.get
    par.subjects shouldBe seq.subjects
  }

  it should "narrow the listing with a case-insensitive filter" in {
    val listing = SubjectExplorer.listAll(registryClient, Some("order")).right.get
    listing.subjects.map(_.name) shouldBe List(orderSubject)
    listing.size shouldBe 1
  }
}
