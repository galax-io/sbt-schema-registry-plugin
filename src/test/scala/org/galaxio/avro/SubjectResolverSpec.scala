package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{Arrays => JArrays}

class SubjectResolverSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val allSubjects =
    JArrays.asList("com.myorg.User-value", "com.myorg.Order-value", "internal.Audit-value")

  private def clientWithSubjects: SchemaRegistryClient = {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenReturn(allSubjects)
    client
  }

  // --- Pattern compilation & full-match semantics ---

  "SubjectResolver" should "match subjects using full-match regex semantics" in {
    val client = clientWithSubjects
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\..*-value"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    val plan = result.right.get
    plan.subjects.map(_.name) should contain theSameElementsAs List(
      "com.myorg.User-value",
      "com.myorg.Order-value",
    )
  }

  it should "not match partial patterns (full-match required)" in {
    val client = clientWithSubjects
    val specs  = List(SubjectSpec.Pattern("User"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects shouldBe empty
  }

  it should "return Left(InvalidPattern) for invalid regex" in {
    val client = mock[SchemaRegistryClient]
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\.(*"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.InvalidPattern]
  }

  it should "fail-fast on invalid regex without calling getAllSubjects" in {
    val client = mock[SchemaRegistryClient]
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\.(*"))
    SubjectResolver.resolve(client, specs)

    verify(client, org.mockito.Mockito.never()).getAllSubjects
  }

  it should "return empty plan when pattern matches zero subjects" in {
    val client = clientWithSubjects
    val specs  = List(SubjectSpec.Pattern("nonexistent\\..*"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe Right(DownloadPlan(Nil))
  }

  it should "resolve pattern-matched subjects to Latest" in {
    val client = clientWithSubjects
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\.User-value"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects shouldBe List(RegistrySubject.Latest("com.myorg.User-value"))
  }

  // --- Deduplication ---

  it should "deduplicate by name, keeping exact (first) over pattern match" in {
    val client = clientWithSubjects
    val exact  = SubjectSpec.Exact(RegistrySubject.Pinned("com.myorg.User-value", 3))
    val pat    = SubjectSpec.Pattern("com\\.myorg\\..*-value")
    val specs  = List(exact, pat)
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    val plan = result.right.get
    plan.subjects should contain(RegistrySubject.Pinned("com.myorg.User-value", 3))
    plan.subjects.count(_.name == "com.myorg.User-value") shouldBe 1
  }

  it should "include non-overlapping pattern matches alongside exact subjects" in {
    val client = clientWithSubjects
    val exact  = SubjectSpec.Exact(RegistrySubject.Pinned("com.myorg.User-value", 3))
    val pat    = SubjectSpec.Pattern("com\\.myorg\\..*-value")
    val specs  = List(exact, pat)
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    val names = result.right.get.subjects.map(_.name)
    names should contain("com.myorg.Order-value")
  }

  // --- Multiple patterns ---

  it should "union results from multiple patterns" in {
    val client = clientWithSubjects
    val specs  = List(
      SubjectSpec.Pattern("com\\.myorg\\.User-value"),
      SubjectSpec.Pattern("internal\\..*"),
    )
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects.map(_.name) should contain theSameElementsAs List(
      "com.myorg.User-value",
      "internal.Audit-value",
    )
  }

  it should "deduplicate across multiple patterns matching the same subject" in {
    val client = clientWithSubjects
    val specs  = List(
      SubjectSpec.Pattern("com\\.myorg\\..*"),
      SubjectSpec.Pattern(".*User.*"),
    )
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Right[_, _]]
    val names = result.right.get.subjects.map(_.name)
    names.count(_ == "com.myorg.User-value") shouldBe 1
  }

  // --- No patterns configured ---

  it should "not call getAllSubjects when only exact specs provided" in {
    val client = mock[SchemaRegistryClient]
    val specs  = List(SubjectSpec.Exact(RegistrySubject.Latest("my-subject")))
    SubjectResolver.resolve(client, specs)

    verify(client, org.mockito.Mockito.never()).getAllSubjects
  }

  it should "return exact subjects as-is when no patterns provided" in {
    val client  = mock[SchemaRegistryClient]
    val subject = RegistrySubject.Pinned("my-subject", 5)
    val specs   = List(SubjectSpec.Exact(subject))
    val result  = SubjectResolver.resolve(client, specs)

    result shouldBe Right(DownloadPlan(List(subject)))
  }

  // --- Error handling ---

  it should "return Left(SubjectListFailed) when getAllSubjects throws" in {
    val client = mock[SchemaRegistryClient]
    when(client.getAllSubjects).thenThrow(new RuntimeException("connection refused"))
    val specs  = List(SubjectSpec.Pattern(".*"))
    val result = SubjectResolver.resolve(client, specs)

    result shouldBe a[Left[_, _]]
    result.left.get shouldBe a[DownloadError.SubjectListFailed]
  }
}
