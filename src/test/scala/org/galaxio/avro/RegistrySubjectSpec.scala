package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RegistrySubjectSpec extends AnyFlatSpec with Matchers {

  "RegistrySubject" should "create with specific version" in {
    val subject = RegistrySubject("test-schema", 3)
    subject.name shouldBe "test-schema"
    subject.version shouldBe Some(3)
  }

  it should "create latest version via factory method" in {
    val subject = RegistrySubject.latest("test-schema")
    subject.name shouldBe "test-schema"
    subject.version shouldBe None
  }

  it should "default version to None" in {
    val subject = RegistrySubject("test-schema")
    subject.version shouldBe None
  }

}
