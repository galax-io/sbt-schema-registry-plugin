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

class ParallelDownloaderIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

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

    (1 to 5).foreach { i =>
      val schema = new AvroSchema(new Schema.Parser().parse(
        s"""{"type":"record","name":"Test$i","fields":[{"name":"id","type":"int"}]}""",
      ))
      registryClient.register(s"test-subject-$i", schema)
    }
  }

  override def afterAll(): Unit = {
    Option(sr).foreach(c => Try(c.stop()))
    Option(kafka).foreach(c => Try(c.stop()))
    Option(network).foreach(c => Try(c.close()))
  }

  private val silentLogger: Logger = Logger.Null

  private def tmpDir: Path = Files.createTempDirectory("parallel-it")

  private val noRetry = RetryPolicy(maxRetries = 0, initialDelayMs = 1, backoffMultiplier = 1.0)

  "ParallelDownloader integration" should "download 5 subjects concurrently" in {
    val outDir     = tmpDir
    val downloader = Downloader.withExternalClient(registryClient, outDir, silentLogger)
    val subjects   = (1 to 5).map(i => RegistrySubject.Latest(s"test-subject-$i")).toList

    val pd      = ParallelDownloader(downloader, parallelism = 4, noRetry, silentLogger)
    val results = pd.downloadAll(subjects)

    results should have size 5
    results.count(_._2.isRight) shouldBe 5

    val files = outDir.toFile.listFiles()
    files should have length 5
  }

  it should "handle partial failure with mix of valid and invalid subjects" in {
    val outDir     = tmpDir
    val downloader = Downloader.withExternalClient(registryClient, outDir, silentLogger)
    val subjects = List(
      RegistrySubject.Latest("test-subject-1"),
      RegistrySubject.Latest("nonexistent-subject"),
      RegistrySubject.Latest("test-subject-3"),
    )

    val pd      = ParallelDownloader(downloader, parallelism = 2, noRetry, silentLogger)
    val results = pd.downloadAll(subjects)

    results should have size 3
    results.count(_._2.isRight) shouldBe 2
    results.count(_._2.isLeft) shouldBe 1

    val files = outDir.toFile.listFiles()
    files should have length 2
  }

  it should "download sequentially with parallelism 1" in {
    val outDir     = tmpDir
    val downloader = Downloader.withExternalClient(registryClient, outDir, silentLogger)
    val subjects   = (1 to 3).map(i => RegistrySubject.Latest(s"test-subject-$i")).toList

    val pd      = ParallelDownloader(downloader, parallelism = 1, noRetry, silentLogger)
    val results = pd.downloadAll(subjects)

    results should have size 3
    results.count(_._2.isRight) shouldBe 3
  }

  it should "compose correctly with incremental resolver" in {
    val outDir     = tmpDir
    val downloader = Downloader.withExternalClient(registryClient, outDir, silentLogger)
    val subjects   = (1 to 5).map(i => RegistrySubject.Latest(s"test-subject-$i")).toList

    val manifest  = VersionManifest.empty
    val decisions = IncrementalResolver.plan(
      manifest,
      subjects,
      s =>
        Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
          .map(DownloadError.SchemaFetchFailed(s, _)),
    )

    val toDownload = decisions.collect { case d: DownloadDecision.Download => d.subject }
    toDownload should have size 5

    val pd      = ParallelDownloader(downloader, parallelism = 4, noRetry, silentLogger)
    val results = pd.downloadAll(toDownload)

    results should have size 5
    results.count(_._2.isRight) shouldBe 5

    val downloaded = results.collect { case (s, Right(_)) => s.name -> 1 }
    val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)

    val decisions2  = IncrementalResolver.plan(
      newManifest,
      subjects,
      s =>
        Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
          .map(DownloadError.SchemaFetchFailed(s, _)),
    )
    val toDownload2 = decisions2.collect { case d: DownloadDecision.Download => d.subject }
    toDownload2 shouldBe empty
  }
}
