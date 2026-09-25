package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IncrementalResolverSpec extends AnyFlatSpec with Matchers {

  private val alwaysFail: String => Either[DownloadError, Int] =
    _ => Left(DownloadError.SubjectListFailed(new RuntimeException("should not be called")))

  private def registryVersions(versions: (String, Int)*): String => Either[DownloadError, Int] = {
    val map = versions.toMap
    name => map.get(name).toRight(DownloadError.SubjectListFailed(new RuntimeException(s"unknown: $name")))
  }

  // --- US1: Latest subjects ---

  "plan" should "download Latest subject not in manifest" in {
    val manifest = VersionManifest.empty
    val subjects = List(RegistrySubject.Latest("user-events"))
    val lookup   = registryVersions("user-events" -> 3)

    val result = IncrementalResolver.plan(manifest, subjects, lookup)

    result should have size 1
    result.head shouldBe a[DownloadDecision.Download]
    val d = result.head.asInstanceOf[DownloadDecision.Download]
    d.subject shouldBe RegistrySubject.Latest("user-events")
    d.reason should include("new")
    d.reason should include("v3")
  }

  it should "skip Latest subject when manifest version matches registry" in {
    val manifest = VersionManifest(Map("user-events" -> 3))
    val subjects = List(RegistrySubject.Latest("user-events"))
    val lookup   = registryVersions("user-events" -> 3)

    val result = IncrementalResolver.plan(manifest, subjects, lookup)

    result should have size 1
    result.head shouldBe DownloadDecision.Skip("user-events", 3)
  }

  it should "download Latest subject when registry version is newer" in {
    val manifest = VersionManifest(Map("user-events" -> 3))
    val subjects = List(RegistrySubject.Latest("user-events"))
    val lookup   = registryVersions("user-events" -> 5)

    val result = IncrementalResolver.plan(manifest, subjects, lookup)

    result should have size 1
    result.head shouldBe a[DownloadDecision.Download]
    val d = result.head.asInstanceOf[DownloadDecision.Download]
    d.reason should include("v3")
    d.reason should include("v5")
  }

  // --- US2: Pinned subjects ---

  it should "download Pinned subject not in manifest" in {
    val manifest = VersionManifest.empty
    val subjects = List(RegistrySubject.Pinned("order-events", 2))

    val result = IncrementalResolver.plan(manifest, subjects, alwaysFail)

    result should have size 1
    result.head shouldBe a[DownloadDecision.Download]
    val d = result.head.asInstanceOf[DownloadDecision.Download]
    d.subject shouldBe RegistrySubject.Pinned("order-events", 2)
    d.reason should include("pinned")
  }

  it should "skip Pinned subject when manifest version matches" in {
    val manifest = VersionManifest(Map("order-events" -> 2))
    val subjects = List(RegistrySubject.Pinned("order-events", 2))

    val result = IncrementalResolver.plan(manifest, subjects, alwaysFail)

    result should have size 1
    result.head shouldBe DownloadDecision.Skip("order-events", 2)
  }

  it should "download Pinned subject when manifest version differs" in {
    val manifest = VersionManifest(Map("order-events" -> 1))
    val subjects = List(RegistrySubject.Pinned("order-events", 3))

    val result = IncrementalResolver.plan(manifest, subjects, alwaysFail)

    result should have size 1
    result.head shouldBe a[DownloadDecision.Download]
  }

  it should "never call version lookup for Pinned subjects" in {
    val bomb: String => Either[DownloadError, Int] =
      _ => throw new AssertionError("version lookup called for Pinned subject")

    val manifest = VersionManifest(Map("a" -> 1))
    val subjects = List(
      RegistrySubject.Pinned("a", 1),
      RegistrySubject.Pinned("b", 2),
    )

    noException should be thrownBy IncrementalResolver.plan(manifest, subjects, bomb)
  }

  // --- US5: Failure fallback ---

  it should "download when version lookup fails" in {
    val manifest                                      = VersionManifest(Map("user-events" -> 3))
    val subjects                                      = List(RegistrySubject.Latest("user-events"))
    val failing: String => Either[DownloadError, Int] =
      _ => Left(DownloadError.SubjectListFailed(new RuntimeException("connection refused")))

    val result = IncrementalResolver.plan(manifest, subjects, failing)

    result should have size 1
    result.head shouldBe a[DownloadDecision.Download]
    val d = result.head.asInstanceOf[DownloadDecision.Download]
    d.reason should include("version check failed")
  }

  it should "handle mixed success and failure lookups" in {
    val manifest                                     = VersionManifest(Map("a" -> 1, "b" -> 2))
    val subjects                                     = List(
      RegistrySubject.Latest("a"),
      RegistrySubject.Latest("b"),
    )
    val lookup: String => Either[DownloadError, Int] = {
      case "a" => Right(1)
      case "b" => Left(DownloadError.SubjectListFailed(new RuntimeException("timeout")))
    }

    val result = IncrementalResolver.plan(manifest, subjects, lookup)

    result should have size 2
    result.head shouldBe DownloadDecision.Skip("a", 1)
    result(1) shouldBe a[DownloadDecision.Download]
  }

  // --- Empty manifest ---

  it should "download all subjects when manifest is empty" in {
    val subjects = List(
      RegistrySubject.Latest("a"),
      RegistrySubject.Pinned("b", 1),
    )
    val lookup   = registryVersions("a" -> 5)

    val result = IncrementalResolver.plan(VersionManifest.empty, subjects, lookup)

    result.collect { case d: DownloadDecision.Download => d } should have size 2
  }

  // --- updatedManifest ---

  "updatedManifest" should "merge downloaded entries into manifest" in {
    val manifest   = VersionManifest(Map("a" -> 1))
    val downloaded = List("b" -> 2, "c" -> 3)

    val result = IncrementalResolver.updatedManifest(manifest, downloaded)

    result.versions shouldBe Map("a" -> 1, "b" -> 2, "c" -> 3)
  }

  it should "overwrite existing entries" in {
    val manifest   = VersionManifest(Map("a" -> 1))
    val downloaded = List("a" -> 5)

    val result = IncrementalResolver.updatedManifest(manifest, downloaded)

    result.versionOf("a") shouldBe Some(5)
  }
}
