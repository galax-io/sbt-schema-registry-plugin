import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.apache.avro.Schema
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/** Boots a real Confluent Schema Registry (+ Kafka) once and registers a test schema.
  * Started lazily when `url` is first evaluated during build loading.
  */
object RegistryFixture {
  val subject    = "it.e2e.Order"
  val schemaJson =
    """{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"}]}"""

  lazy val url: String = {
    val network = Network.newNetwork()

    val kafka = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
    kafka.withListener("kafka:19092")
    kafka.withNetwork(network)
    kafka.start()

    val sr = new JGenericContainer(DockerImageName.parse("confluentinc/cp-schema-registry:7.5.0"))
    sr.withNetwork(network)
    sr.withExposedPorts(8081)
    sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
    sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
    sr.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
    sr.start()

    val u      = s"http://${sr.getHost}:${sr.getMappedPort(8081)}"
    val client = new CachedSchemaRegistryClient(u, 10)
    client.register(subject, new AvroSchema(new Schema.Parser().parse(schemaJson)))
    u
  }
}
