package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import io.confluent.kafka.schemaregistry.client.{SchemaMetadata, SchemaRegistryClient}
import org.mockito.ArgumentMatchers.{anyBoolean, anyInt, anyString}
import org.mockito.Mockito.{verify, when}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Collections

class DownloadOrchestratorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def testLogger: Logger = mock[Logger]

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("orchestrator-test")
    try test(dir)
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
  }

  private def schemaEntity(subject: String, version: Int, body: String): Schema =
    new Schema(subject, version, 1, "AVRO", Collections.emptyList(), body)

  private def config(dir: Path, subjects: Seq[RegistrySubject]): DownloadConfig =
    DownloadConfig(
      subjects = subjects,
      patterns = Seq.empty,
      incremental = true,
      parallelism = 1,
      retries = 0,
      resolveReferences = false,
      targetFolder = dir.resolve("avro"),
      manifestFile = dir.resolve("cache").resolve(".schema-versions.json"),
    )

  private def readManifest(path: Path): VersionManifest =
    VersionManifest.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toOption.get

  "DownloadOrchestrator.run" should "update the manifest only for subjects that downloaded successfully" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion("good", 1, false)).thenReturn(schemaEntity("good", 1, """{"type":"string"}"""))
    when(client.getByVersion("bad", 1, false)).thenThrow(new IOException("boom"))

    val cfg     = config(dir, Seq(RegistrySubject("good", 1), RegistrySubject("bad", 1)))
    val summary = DownloadOrchestrator.run(client, cfg, testLogger)

    summary.map(_.succeeded) shouldBe Right(1)
    summary.map(_.skipped) shouldBe Right(0)
    summary.map(_.failed.map(_._1.name)) shouldBe Right(List("bad"))

    Files.exists(cfg.targetFolder.resolve("good-1.avsc")) shouldBe true
    Files.exists(cfg.targetFolder.resolve("bad-1.avsc")) shouldBe false
    readManifest(cfg.manifestFile).versions shouldBe Map("good" -> 1)
  }

  it should "skip a pinned subject already recorded at that version and never fetch it" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    // Any fetch of a subject that should have been skipped surfaces as a failure, not a skip.
    when(client.getByVersion(anyString(), anyInt(), anyBoolean())).thenThrow(new IllegalStateException("must not fetch"))
    val cfg    = config(dir, Seq(RegistrySubject("cached", 2)))
    Files.createDirectories(cfg.manifestFile.getParent)
    Files.write(cfg.manifestFile, VersionManifest(Map("cached" -> 2)).toJson.getBytes(StandardCharsets.UTF_8))

    val summary = DownloadOrchestrator.run(client, cfg, testLogger)

    summary.map(_.skipped) shouldBe Right(1)
    summary.map(_.succeeded) shouldBe Right(0)
    summary.map(_.failed) shouldBe Right(Nil)
  }

  it should "re-download everything and warn when the manifest is corrupted" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    when(client.getByVersion("svc", 1, false)).thenReturn(schemaEntity("svc", 1, """{"type":"int"}"""))

    val cfg = config(dir, Seq(RegistrySubject("svc", 1)))
    Files.createDirectories(cfg.manifestFile.getParent)
    Files.write(cfg.manifestFile, "not json at all".getBytes(StandardCharsets.UTF_8))

    val logger  = testLogger
    val summary = DownloadOrchestrator.run(client, cfg, logger)

    summary.map(_.succeeded) shouldBe Right(1)
    verify(logger).warn("Version manifest corrupted, re-downloading all schemas")
    readManifest(cfg.manifestFile).versions shouldBe Map("svc" -> 1)
  }

  it should "fail fast with Left when a subject pattern is an invalid regex" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val cfg    = config(dir, Seq.empty).copy(patterns = Seq("["))

    val result = DownloadOrchestrator.run(client, cfg, testLogger)

    result.isLeft shouldBe true
    result.left.get shouldBe a[DownloadError.InvalidPattern]
  }

  it should "download all subjects without touching the manifest when incremental is disabled" in withTempDir { dir =>
    val client = mock[SchemaRegistryClient]
    val meta   = new SchemaMetadata(1, 9, "AVRO", Collections.emptyList(), """{"type":"long"}""")
    when(client.getLatestSchemaMetadata("svc")).thenReturn(meta)

    val cfg     = config(dir, Seq(RegistrySubject.latest("svc"))).copy(incremental = false)
    val summary = DownloadOrchestrator.run(client, cfg, testLogger)

    summary.map(_.succeeded) shouldBe Right(1)
    Files.exists(cfg.targetFolder.resolve("svc-9.avsc")) shouldBe true
    Files.exists(cfg.manifestFile) shouldBe false
  }
}
