package org.galaxio.avro

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.file.{Files, Path}
import scala.util.{Failure, Success}

/** Integration tests for [[Downloader]] exercising the real [[io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient]]
  * against a WireMock HTTP server that mimics the Confluent Schema Registry REST API.
  *
  * These tests verify:
  *   - Happy path: schema retrieval by specific version and by "latest"
  *   - Failure path: missing subject / missing version returns a Failure
  *   - Determinism: downloading the same schema twice produces identical file contents
  */
class DownloaderIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val wireMockServer = new WireMockServer(wireMockConfig().dynamicPort())

  override def beforeAll(): Unit = wireMockServer.start()
  override def afterAll(): Unit  = wireMockServer.stop()

  private def registryUrl: String = s"http://localhost:${wireMockServer.port()}"

  private def silentLogger: Logger = Logger.Null

  private def withTempDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("downloader-it")
    try test(dir)
    finally {
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers to stub Schema Registry REST endpoints
  // ---------------------------------------------------------------------------

  /** Stub GET /subjects/{subject}/versions/{version} */
  private def stubVersion(subject: String, version: Int, schemaJson: String): Unit = {
    val body = schemaRegistryResponse(subject, version, schemaJson)
    wireMockServer.stubFor(
      get(urlEqualTo(s"/subjects/$subject/versions/$version"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/vnd.schemaregistry.v1+json")
            .withBody(body),
        ),
    )
  }

  /** Stub GET /subjects/{subject}/versions/latest */
  private def stubLatest(subject: String, version: Int, schemaJson: String): Unit = {
    val body = schemaRegistryResponse(subject, version, schemaJson)
    wireMockServer.stubFor(
      get(urlEqualTo(s"/subjects/$subject/versions/latest"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/vnd.schemaregistry.v1+json")
            .withBody(body),
        ),
    )
  }

  /** Stub a 404 Subject Not Found for GET /subjects/{subject}/versions/{version} */
  private def stubNotFound(subject: String, version: String): Unit =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/subjects/$subject/versions/$version"))
        .willReturn(
          aResponse()
            .withStatus(404)
            .withHeader("Content-Type", "application/vnd.schemaregistry.v1+json")
            .withBody("""{"error_code":40401,"message":"Subject not found."}"""),
        ),
    )

  private def schemaRegistryResponse(subject: String, version: Int, schemaJson: String): String = {
    // The schema field value must be a JSON-encoded string (i.e. the schema JSON escaped inside a string)
    val escaped = schemaJson.replace("\\", "\\\\").replace("\"", "\\\"")
    s"""{"subject":"$subject","version":$version,"id":1,"schemaType":"AVRO","schema":"$escaped"}"""
  }

  // ---------------------------------------------------------------------------
  // Happy path: specific version
  // ---------------------------------------------------------------------------

  "Downloader (integration)" should "download schema by specific version and write it to a file" in withTempDir { dir =>
    val subject   = "integration-test-subject"
    val version   = 3
    val schemaStr = """{"type":"record","name":"IntegrationTest","namespace":"org.galaxio","fields":[{"name":"id","type":"long"}]}"""

    stubVersion(subject, version, schemaStr)

    val downloader = Downloader(registryUrl, dir, silentLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject(subject, version))

    result shouldBe a[Success[_]]
    val file = dir.resolve(s"$subject-$version.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe schemaStr
  }

  // ---------------------------------------------------------------------------
  // Happy path: latest version
  // ---------------------------------------------------------------------------

  it should "download the latest schema version and write it to a file" in withTempDir { dir =>
    val subject   = "latest-test-subject"
    val version   = 7
    val schemaStr = """{"type":"record","name":"LatestTest","namespace":"org.galaxio","fields":[{"name":"value","type":"string"}]}"""

    stubLatest(subject, version, schemaStr)

    val downloader = Downloader(registryUrl, dir, silentLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject.latest(subject))

    result shouldBe a[Success[_]]
    val file = dir.resolve(s"$subject-$version.avsc")
    Files.exists(file) shouldBe true
    new String(Files.readAllBytes(file)) shouldBe schemaStr
  }

  // ---------------------------------------------------------------------------
  // Failure path: subject not found
  // ---------------------------------------------------------------------------

  it should "return Failure when the subject does not exist in the registry" in withTempDir { dir =>
    val subject = "nonexistent-subject"
    stubNotFound(subject, "1")

    val downloader = Downloader(registryUrl, dir, silentLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject(subject, 1))

    result shouldBe a[Failure[_]]
  }

  // ---------------------------------------------------------------------------
  // Failure path: version not found for an existing subject
  // ---------------------------------------------------------------------------

  it should "return Failure when the requested version does not exist" in withTempDir { dir =>
    val subject = "versioned-subject"
    stubNotFound(subject, "99")

    val downloader = Downloader(registryUrl, dir, silentLogger)
    val result     = downloader.schemaSubjectToFile(RegistrySubject(subject, 99))

    result shouldBe a[Failure[_]]
  }

  // ---------------------------------------------------------------------------
  // Determinism: two downloads of the same schema produce identical files
  // ---------------------------------------------------------------------------

  it should "produce identical output files when downloading the same schema twice" in withTempDir { dir =>
    val subject   = "determinism-subject"
    val version   = 2
    val schemaStr = """{"type":"record","name":"Determinism","namespace":"org.galaxio","fields":[{"name":"ts","type":"long"}]}"""

    stubVersion(subject, version, schemaStr)

    val downloader = Downloader(registryUrl, dir, silentLogger)

    val dir1 = dir.resolve("run1")
    val dir2 = dir.resolve("run2")
    Files.createDirectories(dir1)
    Files.createDirectories(dir2)

    val dl1 = Downloader(registryUrl, dir1, silentLogger)
    val dl2 = Downloader(registryUrl, dir2, silentLogger)

    val r1 = dl1.schemaSubjectToFile(RegistrySubject(subject, version))
    val r2 = dl2.schemaSubjectToFile(RegistrySubject(subject, version))

    r1 shouldBe a[Success[_]]
    r2 shouldBe a[Success[_]]

    val content1 = new String(Files.readAllBytes(r1.get))
    val content2 = new String(Files.readAllBytes(r2.get))
    content1 shouldBe content2
    content1 shouldBe schemaStr
  }

}
