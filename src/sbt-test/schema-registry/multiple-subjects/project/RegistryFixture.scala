import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.apache.avro.Schema
import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

object RegistryFixture {
  val subjectA   = "it.e2e.Order"
  val schemaJsonA =
    """{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"}]}"""

  val subjectB   = "it.e2e.User"
  val schemaJsonB =
    """{"type":"record","name":"User","namespace":"it.e2e","fields":[{"name":"name","type":"string"}]}"""

  private val confluentTag = "7.5.0"

  lazy val url: String = {
    val network = Network.newNetwork()

    val kafka = new ConfluentKafkaContainer(DockerImageName.parse(s"confluentinc/cp-kafka:$confluentTag"))
    kafka.withListener("kafka:19092")
    kafka.withNetwork(network)
    kafka.start()

    val sr = new JGenericContainer(DockerImageName.parse(s"confluentinc/cp-schema-registry:$confluentTag"))
    sr.withNetwork(network)
    sr.withExposedPorts(8081)
    sr.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
    sr.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
    sr.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
    sr.start()

    val u      = s"http://${sr.getHost}:${sr.getMappedPort(8081)}"
    val client = new CachedSchemaRegistryClient(u, 10)
    client.register(subjectA, new AvroSchema(new Schema.Parser().parse(schemaJsonA)))
    client.register(subjectB, new AvroSchema(new Schema.Parser().parse(schemaJsonB)))
    client.close()
    u
  }
}
