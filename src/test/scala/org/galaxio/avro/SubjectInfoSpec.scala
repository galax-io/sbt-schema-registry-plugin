package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SubjectInfoSpec extends AnyFlatSpec with Matchers {

  "SubjectInfo.versionRange" should "be 'none' when there are no versions" in {
    SubjectInfo("s", Nil, None).versionRange shouldBe "none"
  }

  it should "be the single value when only one version exists" in {
    SubjectInfo("s", List(3), None).versionRange shouldBe "3"
  }

  it should "be 'first..last' for multiple versions" in {
    SubjectInfo("s", List(1, 2, 3, 4, 5), None).versionRange shouldBe "1..5"
  }

  it should "be 'first..last' for exactly two versions" in {
    SubjectInfo("s", List(1, 2), None).versionRange shouldBe "1..2"
  }
}
