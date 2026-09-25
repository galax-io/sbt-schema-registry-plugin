package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RegistrySubjectSpec extends AnyFlatSpec with Matchers {

  "RegistrySubject" should "create Pinned with specific version" in {
    val subject = RegistrySubject("test-schema", 3)
    subject shouldBe RegistrySubject.Pinned("test-schema", 3)
    subject.name shouldBe "test-schema"
  }

  it should "create Latest via factory method" in {
    val subject = RegistrySubject.latest("test-schema")
    subject shouldBe RegistrySubject.Latest("test-schema")
    subject.name shouldBe "test-schema"
  }

  it should "default to Latest when no version given" in {
    val subject = RegistrySubject("test-schema")
    subject shouldBe RegistrySubject.Latest("test-schema")
  }

  it should "create Pinned from Option[Int] with Some" in {
    val subject = RegistrySubject("test-schema", Some(5))
    subject shouldBe RegistrySubject.Pinned("test-schema", 5)
  }

  it should "create Latest from Option[Int] with None" in {
    val subject = RegistrySubject("test-schema", None)
    subject shouldBe RegistrySubject.Latest("test-schema")
  }
}
