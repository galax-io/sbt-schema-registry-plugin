package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import org.apache.avro.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.file.{Files, Path}
import scala.util.Try

class ParallelDownloaderIntegrationSpec extends AnyFlatSpec with Matchers with SchemaRegistryContainerSuite {

  override protected def registerSubjects(): Unit =
    (1 to 5).foreach { i =>
      val schema = new AvroSchema(new Schema.Parser().parse(
        s"""{"type":"record","name":"Test$i","fields":[{"name":"id","type":"int"}]}""",
      ))
      registryClient.register(s"test-subject-$i", schema)
    }

  private val silentLogger: Logger = Logger.Null

  private def tmpDir: Path = Files.createTempDirectory("parallel-it")

  private val noRetry = RetryPolicy(maxRetries = 0, initialDelayMs = 1, backoffMultiplier = 1.0)

  "ParallelDownloader integration" should "download subjects concurrently (proven, not just counted)" in {
    val outDir      = tmpDir
    val parallelism = 4
    // Gate getLatestSchemaMetadata on a barrier sized to parallelism: a sequential run can never
    // get all `parallelism` calls in flight at once, so maxConcurrent would stay below it.
    val probe       = ConcurrencyProbe.gating(registryClient, parallelism, Set("getLatestSchemaMetadata"))
    val downloader  = Downloader.withExternalClient(probe.client, outDir, silentLogger)
    val subjects    = (1 to parallelism).map(i => RegistrySubject.Latest(s"test-subject-$i")).toList

    val pd      = ParallelDownloader(downloader, parallelism = parallelism, noRetry, silentLogger)
    val results = pd.downloadAll(subjects)

    results should have size parallelism.toLong
    results.count(_._2.isRight) shouldBe parallelism
    probe.maxConcurrent.get shouldBe parallelism // all tasks were in flight simultaneously

    results.map(_._1.name).toSet shouldBe (1 to parallelism).map(i => s"test-subject-$i").toSet
    outDir.toFile.listFiles().map(_.getName).toSet shouldBe
      (1 to parallelism).map(i => s"test-subject-$i-1.avsc").toSet
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

    val bySubject = results.map { case (s, r) => s.name -> r }.toMap
    bySubject("nonexistent-subject") shouldBe a[Left[_, _]]
    bySubject("nonexistent-subject").left.get shouldBe a[DownloadError.SchemaFetchFailed]
    bySubject("test-subject-1") shouldBe a[Right[_, _]]
    bySubject("test-subject-3") shouldBe a[Right[_, _]]

    // Exactly the two good subjects landed on disk — the failed one wrote nothing.
    outDir.toFile.listFiles().map(_.getName).toSet shouldBe
      Set("test-subject-1-1.avsc", "test-subject-3-1.avsc")
  }

  // Sequential (parallelism = 1) execution + order preservation is owned by ParallelDownloaderSpec
  // ("execute sequentially when parallelism is 1 preserving order"), which asserts the actual order
  // this IT never checked. Removed here as a registry-free duplicate.

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

    // Build the manifest from the REAL registry version, not a hard-coded 1, so the second-pass
    // skip is a genuine version match rather than a coincidence.
    val downloaded  = results.collect { case (s, Right(_)) =>
      s.name -> registryClient.getLatestSchemaMetadata(s.name).getVersion.intValue
    }
    val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)
    newManifest.versionOf("test-subject-1") shouldBe Some(1)

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
