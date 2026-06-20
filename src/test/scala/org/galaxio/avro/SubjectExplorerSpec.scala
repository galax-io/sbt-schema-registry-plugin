package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JArrays}

class SubjectExplorerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def jints(vs: Int*): java.util.List[Integer] =
    JArrays.asList(vs.map(Integer.valueOf): _*)

  // Subjects each with a single version and no subject-level compatibility override.
  private def clientWith(names: String*): SchemaRegistryClient = {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList(names: _*))
    names.foreach { n =>
      when(client.getAllVersions(n)).thenReturn(jints(1))
      when(client.getCompatibility(n)).thenThrow(new RuntimeException("40401"))
    }
    client
  }

  // --- US1: discover all ---

  "SubjectExplorer.listAll" should "return all subjects sorted by name" in {
    val client = clientWith("user-value", "order-value", "payment-value")
    val result = SubjectExplorer.listAll(client, None)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects.map(_.name) shouldBe List("order-value", "payment-value", "user-value")
  }

  it should "extract the version list for each subject" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("a-value"))
    when(client.getAllVersions("a-value")).thenReturn(jints(1, 2, 3))
    when(client.getCompatibility("a-value")).thenThrow(new RuntimeException("40401"))

    val info = SubjectExplorer.listAll(client, None).right.get.subjects.head
    info.versions shouldBe List(1, 2, 3)
    info.versionRange shouldBe "1..3"
  }

  it should "return Left(SubjectListFailed) when getAllSubjects throws" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenThrow(new RuntimeException("connection refused"))

    val result = SubjectExplorer.listAll(client, None)
    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.SubjectListFailed]
  }

  it should "fail-fast with SubjectVersionsFetchFailed naming the subject whose versions cannot be fetched" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("ok-value", "bad-value"))
    when(client.getAllVersions("ok-value")).thenReturn(jints(1))
    when(client.getCompatibility("ok-value")).thenThrow(new RuntimeException("40401"))
    when(client.getAllVersions("bad-value")).thenThrow(new RuntimeException("gone"))

    val result = SubjectExplorer.listAll(client, None)
    result shouldBe a[Left[_, _]]
    val err    = result.left.get
    err shouldBe a[DownloadError.SubjectVersionsFetchFailed]
    err.asInstanceOf[DownloadError.SubjectVersionsFetchFailed].subject shouldBe "bad-value"
  }

  // --- US2: filter ---

  it should "filter case-insensitively by substring" in {
    val client = clientWith("user-value", "order-value", "payment-value")
    SubjectExplorer.listAll(client, Some("ORDER")).right.get.subjects.map(_.name) shouldBe List("order-value")
  }

  it should "treat an empty filter as 'list all'" in {
    val client = clientWith("user-value", "order-value")
    SubjectExplorer.listAll(client, Some("")).right.get.size shouldBe 2
  }

  it should "not fetch or fail on subjects excluded by the filter" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("order-value", "broken-value"))
    when(client.getAllVersions("order-value")).thenReturn(jints(1))
    when(client.getCompatibility("order-value")).thenThrow(new RuntimeException("40401"))
    // broken-value would fail if fetched, but the filter excludes it before any per-subject call.
    when(client.getAllVersions("broken-value")).thenThrow(new RuntimeException("gone"))

    val result = SubjectExplorer.listAll(client, Some("order"))
    result shouldBe a[Right[_, _]]
    result.right.get.subjects.map(_.name) shouldBe List("order-value")
    verify(client, org.mockito.Mockito.never()).getAllVersions("broken-value")
  }

  // --- Parallel fetch (reuses schemaRegistryParallelism) ---

  it should "produce the same sorted result with parallelism > 1" in {
    val client = clientWith("user-value", "order-value", "payment-value")
    SubjectExplorer.listAll(client, None, parallelism = 4).right.get.subjects.map(_.name) shouldBe
      List("order-value", "payment-value", "user-value")
  }

  it should "fail-fast in parallel mode when a subject's versions cannot be fetched" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("ok-value", "bad-value"))
    when(client.getAllVersions("ok-value")).thenReturn(jints(1))
    when(client.getCompatibility("ok-value")).thenThrow(new RuntimeException("40401"))
    when(client.getAllVersions("bad-value")).thenThrow(new RuntimeException("gone"))

    val result = SubjectExplorer.listAll(client, None, parallelism = 4)
    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.SubjectVersionsFetchFailed]
  }

  it should "report the first failing subject in sorted order when several fail (parallel)" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("z-bad", "a-bad"))
    when(client.getAllVersions("a-bad")).thenThrow(new RuntimeException("a fail"))
    when(client.getAllVersions("z-bad")).thenThrow(new RuntimeException("z fail"))

    val result = SubjectExplorer.listAll(client, None, parallelism = 4)
    result shouldBe a[Left[_, _]]
    result.left.get.asInstanceOf[DownloadError.SubjectVersionsFetchFailed].subject shouldBe "a-bad"
  }

  // --- US3: versions + compatibility (debugging) ---

  it should "surface a subject-level compatibility level" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("c-value"))
    when(client.getAllVersions("c-value")).thenReturn(jints(1))
    when(client.getCompatibility("c-value")).thenReturn("BACKWARD")

    SubjectExplorer.listAll(client, None).right.get.subjects.head.compatibility shouldBe Some("BACKWARD")
  }

  it should "report None compatibility (best-effort) when getCompatibility throws, without failing the task" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(JArrays.asList("c-value"))
    when(client.getAllVersions("c-value")).thenReturn(jints(1))
    when(client.getCompatibility("c-value")).thenThrow(new RuntimeException("40401"))

    val result = SubjectExplorer.listAll(client, None)
    result shouldBe a[Right[_, _]]
    result.right.get.subjects.head.compatibility shouldBe None
  }
}
