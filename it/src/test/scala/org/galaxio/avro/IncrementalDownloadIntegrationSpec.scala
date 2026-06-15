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
import sbt.util.Logger

import java.nio.file.{Files, Path}
import scala.util.Try

class IncrementalDownloadIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private val silentLogger: Logger = Logger.Null

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("incremental-test")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
  }

  private val orderSchemaV1 =
    """{"type":"record","name":"Order","namespace":"incr.test","fields":[{"name":"id","type":"long"}]}"""

  private val orderSchemaV2 =
    """{"type":"record","name":"Order","namespace":"incr.test","fields":[{"name":"id","type":"long"},{"name":"qty","type":"int","default":0}]}"""

  private val userSchema =
    """{"type":"record","name":"User","namespace":"incr.test","fields":[{"name":"name","type":"string"}]}"""

  "IncrementalResolver with real registry" should "download all on first run and create manifest" in withTempDir {
    outputDir =>
      val orderSubject = "incr.test.Order"
      val userSubject  = "incr.test.User"

      registryClient.register(orderSubject, avroSchema(orderSchemaV1))
      registryClient.register(userSubject, avroSchema(userSchema))

      val subjects = List(
        RegistrySubject.Latest(orderSubject),
        RegistrySubject.Latest(userSubject),
      )

      val manifest = VersionManifest.empty
      val decisions = IncrementalResolver.plan(
        manifest,
        subjects,
        s =>
          Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
            .map(DownloadError.SchemaFetchFailed(s, _)),
      )

      decisions.collect { case d: DownloadDecision.Download => d } should have size 2
      decisions.collect { case s: DownloadDecision.Skip     => s } shouldBe empty

      val downloader = Downloader.withExternalClient(registryClient, outputDir, silentLogger)
      val downloaded = decisions.collect { case DownloadDecision.Download(subject, _) =>
        downloader.schemaSubjectToFile(subject)
        val v = registryClient.getLatestSchemaMetadata(subject.name).getVersion
        subject.name -> v
      }

      val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)
      newManifest.versions should have size 2
      newManifest.versionOf(orderSubject) shouldBe Some(1)
      newManifest.versionOf(userSubject) shouldBe Some(1)
  }

  it should "skip all subjects when versions unchanged" in {
    val orderSubject = "incr.test.Order"
    val userSubject  = "incr.test.User"

    val manifest = VersionManifest(Map(orderSubject -> 1, userSubject -> 1))
    val subjects = List(
      RegistrySubject.Latest(orderSubject),
      RegistrySubject.Latest(userSubject),
    )

    val decisions = IncrementalResolver.plan(
      manifest,
      subjects,
      s =>
        Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
          .map(DownloadError.SchemaFetchFailed(s, _)),
    )

    decisions.collect { case s: DownloadDecision.Skip     => s } should have size 2
    decisions.collect { case d: DownloadDecision.Download => d } shouldBe empty
  }

  it should "download only changed subject after new version registered" in {
    val orderSubject = "incr.test.Order"
    val userSubject  = "incr.test.User"

    // Use fresh client to bypass metadata cache after V2 registration
    val freshClient = new CachedSchemaRegistryClient(registryUrl, 100)
    try {
      freshClient.register(orderSubject, avroSchema(orderSchemaV2))

      val manifest = VersionManifest(Map(orderSubject -> 1, userSubject -> 1))
      val subjects = List(
        RegistrySubject.Latest(orderSubject),
        RegistrySubject.Latest(userSubject),
      )

      val decisions = IncrementalResolver.plan(
        manifest,
        subjects,
        s =>
          Try(freshClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
            .map(DownloadError.SchemaFetchFailed(s, _)),
      )

      val downloads = decisions.collect { case d: DownloadDecision.Download => d }
      val skips     = decisions.collect { case s: DownloadDecision.Skip     => s }

      downloads should have size 1
      downloads.head.subject.name shouldBe orderSubject

      skips should have size 1
      skips.head.name shouldBe userSubject
    } finally freshClient.close()
  }

  it should "download all subjects when manifest is empty (simulating sbt clean)" in {
    val orderSubject = "incr.test.Order"
    val userSubject  = "incr.test.User"

    val subjects = List(
      RegistrySubject.Latest(orderSubject),
      RegistrySubject.Latest(userSubject),
    )

    val decisions = IncrementalResolver.plan(
      VersionManifest.empty,
      subjects,
      s =>
        Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
          .map(DownloadError.SchemaFetchFailed(s, _)),
    )

    decisions.collect { case d: DownloadDecision.Download => d } should have size 2
  }

  "VersionManifest" should "survive JSON round-trip with real data" in {
    val manifest = VersionManifest(Map("incr.test.Order" -> 2, "incr.test.User" -> 1))
    val json     = manifest.toJson
    val parsed   = VersionManifest.fromJson(json)
    parsed shouldBe Right(manifest)
  }
}
