package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.util.Try

/** Boots one Kafka + Confluent Schema Registry (and a `CachedSchemaRegistryClient`) per spec that
  * mixes this in — the boot/teardown block previously duplicated verbatim in every IT spec.
  *
  * DECISION: one registry PER spec, not a module-wide singleton. Specs such as
  * `SubjectExplorerIntegrationSpec` assert the EXACT global subject list via `getAllSubjects`, and
  * `SubjectResolverIntegrationSpec` registers `*User*`/`*-value` subjects that would leak across a
  * shared registry and break those assertions; a singleton would also force
  * `Test / parallelExecution := false` plus per-spec namespace isolation. Per-spec boot keeps every
  * existing assertion valid at the cost of an unchanged boot count.
  *
  * Mix in, then use `registryUrl` / `registryClient`. Override `registerSubjects()` to seed fixtures
  * after the client is ready. The client and containers are torn down in `afterAll`.
  */
trait SchemaRegistryContainerSuite extends BeforeAndAfterAll { self: Suite =>

  /** Image tags, identical to the original inline boot blocks. */
  protected def kafkaImage: String          = "confluentinc/cp-kafka:7.5.0"
  protected def schemaRegistryImage: String = "confluentinc/cp-schema-registry:7.5.0"

  /** Identity-cache capacity for the spec-facing client (was hard-coded to 100 in every spec). */
  protected def clientCacheSize: Int = 100

  @volatile private var network: Network               = _
  @volatile private var kafka: ConfluentKafkaContainer = _
  @volatile private var sr: JGenericContainer[_]       = _

  /** Base URL of the booted Schema Registry, e.g. `http://localhost:32785`. */
  protected var registryUrl: String = _

  /** A client connected to [[registryUrl]]; usable from `registerSubjects()` and the tests. */
  protected var registryClient: CachedSchemaRegistryClient = _

  /** Override to seed subjects/fixtures once the registry and client are up. No-op by default. */
  protected def registerSubjects(): Unit = ()

  override def beforeAll(): Unit = {
    super.beforeAll()
    try {
      network = Network.newNetwork()

      kafka = new ConfluentKafkaContainer(DockerImageName.parse(kafkaImage))
      kafka.withListener("kafka:19092")
      kafka.withNetwork(network)
      kafka.start()

      sr = new JGenericContainer(DockerImageName.parse(schemaRegistryImage))
      sr.withNetwork(network)
      sr.withExposedPorts(8081)
      sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
      sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
      sr.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
      sr.start()

      registryUrl = s"http://${sr.getHost}:${sr.getMappedPort(8081)}"
      registryClient = new CachedSchemaRegistryClient(registryUrl, clientCacheSize)

      registerSubjects()
    } catch {
      case e: Throwable =>
        // ScalaTest does NOT call afterAll when beforeAll throws — tear down here so a failed
        // container start or registerSubjects() never leaks the partially-started containers.
        stopQuietly()
        throw e
    }
  }

  override def afterAll(): Unit =
    try stopQuietly()
    finally super.afterAll()

  /** Idempotent teardown: close the client first (was inconsistently closed across specs), then the
    * containers and network — each guarded so one failure never masks the rest.
    */
  private def stopQuietly(): Unit = {
    Option(registryClient).foreach(c => Try(c.close()))
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }
}
