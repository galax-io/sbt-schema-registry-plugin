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

class SubjectResolverIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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

    registerTestSubjects()
  }

  override def afterAll(): Unit = {
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }

  private def avroSchema(name: String): AvroSchema =
    new AvroSchema(
      new Schema.Parser().parse(
        s"""{"type":"record","name":"$name","fields":[{"name":"id","type":"int"}]}""",
      ),
    )

  private def registerTestSubjects(): Unit = {
    registryClient.register("com.myorg.User-value", avroSchema("User"))
    registryClient.register("com.myorg.Order-value", avroSchema("Order"))
    // Second registration creates version 2 so pinned-version test can assert version 1 vs latest
    registryClient.register("com.myorg.User-value", avroSchema("User"))
    registryClient.register("internal.Audit-value", avroSchema("Audit"))
  }

  // --- US1: Pattern matching ---

  "SubjectResolver (integration)" should "resolve pattern matching 2 of 3 subjects" in {
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\..*-value"))
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    val names = result.right.get.subjects.map(_.name)
    names should contain theSameElementsAs List("com.myorg.User-value", "com.myorg.Order-value")
  }

  it should "return empty plan when pattern matches zero subjects" in {
    val specs  = List(SubjectSpec.Pattern("nonexistent\\..*"))
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe Right(DownloadPlan(Nil))
  }

  it should "resolve pattern-matched subjects as Latest" in {
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\.User-value"))
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects.foreach {
      case _: RegistrySubject.Latest => succeed
      case other                     => fail(s"Expected Latest, got $other")
    }
  }

  // --- US2: Exact + pattern composition ---

  it should "give precedence to exact Pinned subject over pattern match" in {
    val specs = List(
      SubjectSpec.Exact(RegistrySubject.Pinned("com.myorg.User-value", 1)),
      SubjectSpec.Pattern("com\\.myorg\\..*-value"),
    )
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    val plan    = result.right.get
    val userSub = plan.subjects.find(_.name == "com.myorg.User-value")
    userSub shouldBe Some(RegistrySubject.Pinned("com.myorg.User-value", 1))
    plan.subjects.count(_.name == "com.myorg.User-value") shouldBe 1
    plan.subjects.map(_.name) should contain("com.myorg.Order-value")
  }

  it should "work with only exact specs (no pattern resolution)" in {
    val specs  = List(SubjectSpec.Exact(RegistrySubject.Latest("com.myorg.User-value")))
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe Right(DownloadPlan(List(RegistrySubject.Latest("com.myorg.User-value"))))
  }

  // --- US3: Multiple patterns ---

  it should "union results from multiple patterns across namespaces" in {
    val specs = List(
      SubjectSpec.Pattern("com\\.myorg\\.User-value"),
      SubjectSpec.Pattern("internal\\..*"),
    )
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects.map(_.name) should contain theSameElementsAs List(
      "com.myorg.User-value",
      "internal.Audit-value",
    )
  }

  it should "deduplicate across overlapping patterns" in {
    val specs = List(
      SubjectSpec.Pattern("com\\.myorg\\..*"),
      SubjectSpec.Pattern(".*User.*"),
    )
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    val names = result.right.get.subjects.map(_.name)
    names.count(_ == "com.myorg.User-value") shouldBe 1
  }
}
