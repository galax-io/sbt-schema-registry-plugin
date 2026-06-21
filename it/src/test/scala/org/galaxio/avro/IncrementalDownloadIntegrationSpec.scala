package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import org.apache.avro.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.file.{Files, Path}
import scala.util.Try

class IncrementalDownloadIntegrationSpec extends AnyFlatSpec with Matchers with SchemaRegistryContainerSuite {

  private def avroSchema(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private val silentLogger: Logger = Logger.Null

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("incremental-test")
    try test(dir)
    finally {
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
      finally stream.close()
    }
  }

  private val orderSchemaV1 =
    """{"type":"record","name":"Order","namespace":"incr.test","fields":[{"name":"id","type":"long"}]}"""

  private val orderSchemaV2 =
    """{"type":"record","name":"Order","namespace":"incr.test","fields":[{"name":"id","type":"long"},{"name":"qty","type":"int","default":0}]}"""

  private val userSchema =
    """{"type":"record","name":"User","namespace":"incr.test","fields":[{"name":"name","type":"string"}]}"""

  "IncrementalResolver with real registry" should "download all on first run and create manifest" in withTempDir { outputDir =>
    val orderSubject = "incr.test.Order"
    val userSubject  = "incr.test.User"

    registryClient.register(orderSubject, avroSchema(orderSchemaV1))
    registryClient.register(userSubject, avroSchema(userSchema))

    val subjects = List(
      RegistrySubject.Latest(orderSubject),
      RegistrySubject.Latest(userSubject),
    )

    val manifest  = VersionManifest.empty
    val decisions = IncrementalResolver.plan(
      manifest,
      subjects,
      s =>
        Try(registryClient.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
          .map(DownloadError.SchemaFetchFailed(s, _)),
    )

    decisions.collect { case d: DownloadDecision.Download => d } should have size 2
    decisions.collect { case s: DownloadDecision.Skip => s } shouldBe empty

    val downloader = Downloader.withExternalClient(registryClient, outputDir, silentLogger)
    val downloaded = decisions.collect { case d @ DownloadDecision.Download(subject, _, _) =>
      downloader.schemaSubjectToFile(subject) shouldBe a[Right[_, _]]
      subject.name -> d.resolvedVersion.get
    }

    // The download actually wrote both files (its Either was previously discarded).
    Files.exists(outputDir.resolve(s"$orderSubject-1.avsc")) shouldBe true
    Files.exists(outputDir.resolve(s"$userSubject-1.avsc")) shouldBe true

    val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)
    newManifest.versions should have size 2
    newManifest.versionOf(orderSubject) shouldBe Some(1)
    newManifest.versionOf(userSubject) shouldBe Some(1)
  }

  // "skip all when unchanged" is pure plan logic (owned by IncrementalResolverSpec "skip Latest
  // subject when manifest version matches registry") and was also order-coupled to the first
  // scenario's registrations. The live version-match path is exercised by the cache-bypass test below.

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
      val skips     = decisions.collect { case s: DownloadDecision.Skip => s }

      downloads should have size 1
      downloads.head.subject.name shouldBe orderSubject

      skips should have size 1
      skips.head.name shouldBe userSubject
    } finally freshClient.close()
  }

  // "download all when manifest empty" duplicates the first-run scenario above and
  // IncrementalResolverSpec ("download all subjects when manifest is empty"); the JSON round-trip is
  // pure and owned by VersionManifestSpec. Both removed as registry-free duplicates.
}
