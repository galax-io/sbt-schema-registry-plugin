package org.galaxio.avro

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import org.apache.avro.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubjectExplorerIntegrationSpec extends AnyFlatSpec with Matchers with SchemaRegistryContainerSuite {

  private val orderSubject = "it.e2e.Order-value"
  private val userSubject  = "it.e2e.User-value"

  override protected def registerSubjects(): Unit = registerTestSubjects()

  private def avro(json: String): AvroSchema =
    new AvroSchema(new Schema.Parser().parse(json))

  private def registerTestSubjects(): Unit = {
    // Order: two backward-compatible versions (adding a field with a default is backward compatible).
    registryClient.register(
      orderSubject,
      avro("""{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"}]}"""),
    )
    registryClient.register(
      orderSubject,
      avro(
        """{"type":"record","name":"Order","namespace":"it.e2e","fields":[{"name":"id","type":"long"},{"name":"note","type":"string","default":""}]}""",
      ),
    )
    // User: single version, no subject-level compatibility override.
    registryClient.register(
      userSubject,
      avro("""{"type":"record","name":"User","namespace":"it.e2e","fields":[{"name":"name","type":"string"}]}"""),
    )
    // Subject-level compatibility override on Order only.
    registryClient.updateCompatibility(orderSubject, "BACKWARD")
  }

  "SubjectExplorer.listAll (integration)" should "list all subjects sorted with their real versions" in {
    val result = SubjectExplorer.listAll(registryClient, None)

    result shouldBe a[Right[_, _]]
    val listing = result.right.get
    listing.subjects.map(_.name) shouldBe List(orderSubject, userSubject)

    // Real getAllVersions over the two registered Order versions and the single User version.
    // versionRange string formatting is pure and owned by SubjectInfoSpec.
    listing.subjects.find(_.name == orderSubject).get.versions shouldBe List(1, 2)
    listing.subjects.find(_.name == userSubject).get.versions shouldBe List(1)
  }

  it should "surface a subject-level compatibility override and report None for subjects without one" in {
    val listing = SubjectExplorer.listAll(registryClient, None).right.get
    listing.subjects.find(_.name == orderSubject).get.compatibility shouldBe Some("BACKWARD")
    listing.subjects.find(_.name == userSubject).get.compatibility shouldBe None
  }

  // Parallel-equals-sequential and case-insensitive filtering are pure (BoundedParallel preserves
  // order; SubjectListing.nameMatches owns the predicate) and are covered by SubjectExplorerSpec.
  // Removed here as registry-free duplicates.
}
