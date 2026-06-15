package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VersionManifestSpec extends AnyFlatSpec with Matchers {

  "VersionManifest.empty" should "have no versions" in {
    VersionManifest.empty.versions shouldBe empty
  }

  "versionOf" should "return Some for existing subject" in {
    val m = VersionManifest(Map("user-events" -> 3))
    m.versionOf("user-events") shouldBe Some(3)
  }

  it should "return None for missing subject" in {
    val m = VersionManifest(Map("user-events" -> 3))
    m.versionOf("order-events") shouldBe None
  }

  "updated" should "add a new entry" in {
    val m = VersionManifest.empty.updated("user-events", 1)
    m.versionOf("user-events") shouldBe Some(1)
  }

  it should "overwrite an existing entry" in {
    val m = VersionManifest(Map("user-events" -> 1)).updated("user-events", 5)
    m.versionOf("user-events") shouldBe Some(5)
  }

  "updatedAll" should "merge multiple entries" in {
    val m = VersionManifest(Map("a" -> 1))
      .updatedAll(List("b" -> 2, "c" -> 3))
    m.versions shouldBe Map("a" -> 1, "b" -> 2, "c" -> 3)
  }

  it should "overwrite existing entries" in {
    val m = VersionManifest(Map("a" -> 1, "b" -> 2))
      .updatedAll(List("b" -> 99))
    m.versions shouldBe Map("a" -> 1, "b" -> 99)
  }

  "toJson and fromJson" should "round-trip empty manifest" in {
    val json   = VersionManifest.empty.toJson
    val parsed = VersionManifest.fromJson(json)
    parsed shouldBe Right(VersionManifest.empty)
  }

  it should "round-trip single entry" in {
    val m      = VersionManifest(Map("user-events" -> 3))
    val json   = m.toJson
    val parsed = VersionManifest.fromJson(json)
    parsed shouldBe Right(m)
  }

  it should "round-trip multiple entries" in {
    val m      = VersionManifest(Map("user-events" -> 3, "order-events" -> 7, "payment-value" -> 1))
    val json   = m.toJson
    val parsed = VersionManifest.fromJson(json)
    parsed shouldBe Right(m)
  }

  "fromJson" should "return Left for invalid JSON" in {
    val result = VersionManifest.fromJson("not json")
    result.isLeft shouldBe true
    result.left.get shouldBe a[DownloadError.ManifestParseError]
  }

  it should "return Left for empty string" in {
    val result = VersionManifest.fromJson("")
    result.isLeft shouldBe true
  }

  it should "return Left for JSON array" in {
    val result = VersionManifest.fromJson("[1, 2, 3]")
    result.isLeft shouldBe true
  }

  it should "ignore non-integer values" in {
    val result = VersionManifest.fromJson("""{"a": 1, "b": "not-int", "c": 3}""")
    result shouldBe Right(VersionManifest(Map("a" -> 1, "c" -> 3)))
  }

  it should "parse JSON with extra whitespace" in {
    val json   = """  {  "x" : 42  }  """
    val result = VersionManifest.fromJson(json)
    result shouldBe Right(VersionManifest(Map("x" -> 42)))
  }
}
