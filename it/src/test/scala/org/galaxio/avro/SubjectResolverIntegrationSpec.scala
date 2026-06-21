package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import org.apache.avro.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubjectResolverIntegrationSpec extends AnyFlatSpec with Matchers with SchemaRegistryContainerSuite {

  override protected def registerSubjects(): Unit = registerTestSubjects()

  private def avroSchema(name: String): AvroSchema =
    new AvroSchema(
      new Schema.Parser().parse(
        s"""{"type":"record","name":"$name","fields":[{"name":"id","type":"int"}]}""",
      ),
    )

  private def registerTestSubjects(): Unit = {
    registryClient.register("com.myorg.User-value", avroSchema("User"))
    registryClient.register("com.myorg.Order-value", avroSchema("Order"))
    registryClient.register("internal.Audit-value", avroSchema("Audit"))
  }

  // --- US1: Pattern matching ---

  "SubjectResolver (integration)" should "resolve pattern matching 2 of 3 subjects" in {
    val specs  = List(SubjectSpec.Pattern("com\\.myorg\\..*-value"))
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    val names = result.right.get.subjects.map(_.name)
    names should contain theSameElementsAs List("com.myorg.User-value", "com.myorg.Order-value")
  }

  // Empty-plan and pattern-resolves-to-Latest are pure regex/mapping logic owned by
  // SubjectResolverSpec; removed here as registry-free duplicates.

  // --- US2: Exact + pattern composition ---

  it should "give precedence to exact Pinned subject over pattern match" in {
    val specs  = List(
      SubjectSpec.Exact(RegistrySubject.Pinned("com.myorg.User-value", 1)),
      SubjectSpec.Pattern("com\\.myorg\\..*-value"),
    )
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    val plan    = result.right.get
    val userSub = plan.subjects.find(_.name == "com.myorg.User-value")
    userSub shouldBe Some(RegistrySubject.Pinned("com.myorg.User-value", 1))
    plan.subjects.count(_.name == "com.myorg.User-value") shouldBe 1
    plan.subjects.map(_.name) should contain("com.myorg.Order-value")
  }

  // Exact-only resolution touches the registry zero times — owned by SubjectResolverSpec
  // ("return exact subjects as-is" / "not call getAllSubjects when only exact specs provided").

  // --- US3: Multiple patterns ---

  it should "union results from multiple patterns across namespaces" in {
    val specs  = List(
      SubjectSpec.Pattern("com\\.myorg\\.User-value"),
      SubjectSpec.Pattern("internal\\..*"),
    )
    val result = SubjectResolver.resolve(registryClient, specs)

    result shouldBe a[Right[_, _]]
    result.right.get.subjects.map(_.name) should contain theSameElementsAs List(
      "com.myorg.User-value",
      "internal.Audit-value",
    )
  }

  // Overlapping-pattern deduplication is pure set logic owned by SubjectResolverSpec
  // ("deduplicate across multiple patterns matching the same subject").
}
