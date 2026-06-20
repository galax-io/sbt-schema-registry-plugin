package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubjectListingSpec extends AnyFlatSpec with Matchers {

  private def info(name: String): SubjectInfo = SubjectInfo(name, List(1), None)

  private val listing =
    SubjectListing(List(info("user-value"), info("order-value"), info("payment-value")))

  "SubjectListing.matching" should "keep case-insensitive substring matches" in {
    listing.matching("ORDER").subjects.map(_.name) shouldBe List("order-value")
  }

  it should "match a substring appearing anywhere in the name" in {
    listing.matching("value").subjects should have size 3
  }

  it should "return all subjects for an empty filter" in {
    listing.matching("").subjects should have size 3
  }

  it should "return empty when nothing matches" in {
    listing.matching("nope").subjects shouldBe empty
  }

  "SubjectListing.size" should "report the number of subjects" in {
    listing.size shouldBe 3
  }
}
