package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.{SchemaReference => ConfluentSchemaReference}
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
import scala.collection.JavaConverters._
import scala.util.Try

/** End-to-end reference resolution against a real Confluent Schema Registry.
  *
  * Validates the parts the pure unit suite stubs: the real `getReferences` mapping (boxed
  * `Integer`, nullable list) and composition of the resolver output with the existing
  * incremental and parallel download stages (FR-009).
  */
class ReferenceResolutionIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var network: Network                           = _
  private var kafka: ConfluentKafkaContainer             = _
  private var sr: JGenericContainer[_]                   = _
  private var registryUrl: String                        = _
  private var registryClient: CachedSchemaRegistryClient = _

  private val silentLogger: Logger = Logger.Null

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
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private def registerWithRefs(subject: String, json: String, refs: List[ConfluentSchemaReference]): Unit = {
    val schema = new AvroSchema(json, refs.asJava, Collections.emptyMap[String, String](), null)
    registryClient.register(subject, schema)
    ()
  }

  /** The exact fetch the plugin wires — reads references from a real registry.
    * `lazy` so `registryClient` (set in `beforeAll`) is captured after init, not at construction.
    */
  private lazy val fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema] =
    Downloader.referenceFetch(registryClient)

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("reference-resolution-it")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  }

  private def readString(p: Path): String = new String(Files.readAllBytes(p))

  private val baseJson =
    """{"type":"record","name":"Base","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""
  private val depJson  =
    """{"type":"record","name":"Dependent","namespace":"org.galaxio","fields":[{"name":"base_id","type":"long"},{"name":"value","type":"string"}]}"""

  "ReferenceResolver (integration)" should
    "resolve a referenced schema and download both files (PS-1)" in withTempDir { dir =>
      val base = "it-rr-base"
      val dep  = "it-rr-dep"
      registryClient.register(base, avroSchema(baseJson))
      registerWithRefs(dep, depJson, List(new ConfluentSchemaReference("Base", base, 1)))

      val expanded      = ReferenceResolver.resolve(List(RegistrySubject.latest(dep)), fetch).getOrElse(fail())
      val expandedNamed = expanded.map(s => s.name -> s).toMap
      // Exact expansion (not a subset), each resolved to pinned v1 (root first, then its reference).
      expanded.map(_.name) should contain theSameElementsAs List(dep, base)
      expandedNamed(dep)  shouldBe RegistrySubject.Pinned(dep, 1)
      expandedNamed(base) shouldBe RegistrySubject.Pinned(base, 1)

      val downloader = Downloader(registryUrl, dir, silentLogger)
      try expanded.foreach(s => downloader.schemaSubjectToFile(s) shouldBe a[Right[_, _]])
      finally downloader.close()

      // Both bodies are actually written and parseable — proves the refs were resolvable, not just that files exist.
      new Schema.Parser().parse(readString(dir.resolve(s"$dep-1.avsc"))).getName  shouldBe "Dependent"
      new Schema.Parser().parse(readString(dir.resolve(s"$base-1.avsc"))).getName shouldBe "Base"
    }

  it should "fail fast with the failing subject when a fetch fails (PS-4)" in {
    val result = ReferenceResolver.resolve(List(RegistrySubject.latest("it-rr-missing")), fetch)
    result shouldBe a[Left[_, _]]
    val err = result.swap.getOrElse(fail())
    err                                                       shouldBe a[DownloadError.SchemaFetchFailed]
    err.asInstanceOf[DownloadError.SchemaFetchFailed].subject shouldBe "it-rr-missing"
  }

  it should "compose with incremental skip of an already-current reference (PS-5)" in {
    val base = "it-rr-incr-base"
    val dep  = "it-rr-incr-dep"
    registryClient.register(base, avroSchema(baseJson))
    registerWithRefs(dep, depJson, List(new ConfluentSchemaReference("Base", base, 1)))

    val expanded = ReferenceResolver.resolve(List(RegistrySubject.latest(dep)), fetch).getOrElse(fail())

    val manifest  = VersionManifest(Map(base -> 1))
    val noLookup: String => Either[DownloadError, Int] =
      s => Left(DownloadError.SubjectListFailed(new RuntimeException(s"lookup not expected: $s")))
    val decisions = IncrementalResolver.plan(manifest, expanded, noLookup)

    decisions                                                          should contain(DownloadDecision.Skip(base, 1))
    decisions.collect { case d: DownloadDecision.Download => d.subject.name } should contain(dep)
  }

  it should "compose with bounded-parallel download of the expanded list (PS-6)" in withTempDir { dir =>
    val base = "it-rr-par-base"
    val dep  = "it-rr-par-dep"
    registryClient.register(base, avroSchema(baseJson))
    registerWithRefs(dep, depJson, List(new ConfluentSchemaReference("Base", base, 1)))

    val expanded = ReferenceResolver.resolve(List(RegistrySubject.latest(dep)), fetch).getOrElse(fail())
    expanded should have size 2

    // Resolved references are Pinned, so the parallel download fetches each via getByVersion; gate
    // that on a 2-wide barrier to prove the two downloads actually overlap (not just both succeed).
    val probe      = ConcurrencyProbe.gating(registryClient, 2, Set("getByVersion"))
    val downloader = Downloader.withExternalClient(probe.client, dir, silentLogger)
    val results =
      try {
        val parallel = ParallelDownloader(downloader, 2, RetryPolicy(maxRetries = 0), silentLogger)
        parallel.downloadAll(expanded)
      } finally downloader.close()

    val bySubject = results.map { case (s, r) => s.name -> r }.toMap
    bySubject(dep)  shouldBe a[Right[_, _]]
    bySubject(base) shouldBe a[Right[_, _]]
    probe.maxConcurrent.get shouldBe 2

    Files.exists(dir.resolve(s"$dep-1.avsc"))  shouldBe true
    Files.exists(dir.resolve(s"$base-1.avsc")) shouldBe true
  }
}
