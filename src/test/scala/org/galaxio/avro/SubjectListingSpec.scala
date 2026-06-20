package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubjectListingSpec extends AnyFlatSpec with Matchers {

  private def info(name: String): SubjectInfo = SubjectInfo(name, List(1), None)

  private val listing =
    SubjectListing(List(info("user-value"), info("order-value"), info("payment-value")))

  "SubjectListing.nameMatches" should "match case-insensitively" in {
    SubjectListing.nameMatches("order-value", "ORDER") shouldBe true
  }

  it should "match a substring appearing anywhere in the name" in {
    SubjectListing.nameMatches("payment-value", "value") shouldBe true
  }

  it should "match everything for an empty filter" in {
    SubjectListing.nameMatches("anything", "") shouldBe true
  }

  it should "not match when the substring is absent" in {
    SubjectListing.nameMatches("user-value", "nope") shouldBe false
  }

  "SubjectListing.size" should "report the number of subjects" in {
    listing.size shouldBe 3
  }
}
