package org.galaxio.avro

import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.IOException
import java.nio.file.Paths

class RetryPolicySpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def testLogger: Logger = mock[Logger]

  private val fetchFailed: DownloadError =
    DownloadError.SchemaFetchFailed("test-subject", new IOException("connection refused"))

  private val permanentError: DownloadError =
    DownloadError.InvalidSubjectName("bad/name")

  private val writeError: DownloadError =
    DownloadError.WriteError(Paths.get("/tmp"), new IOException("disk full"))

  private val unsupportedType: DownloadError =
    DownloadError.UnsupportedSchemaType("GRAPHQL", "test-subject")

  "RetryPolicy" should "return success on first attempt without retrying" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 2.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Right(Paths.get("/ok")) },
      "subject-a",
      testLogger,
    )
    result shouldBe Right(Paths.get("/ok"))
    calls shouldBe 1
  }

  it should "retry SchemaFetchFailed up to maxRetries then return last error" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 1.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Left(fetchFailed) },
      "subject-b",
      testLogger,
    )
    result shouldBe Left(fetchFailed)
    calls shouldBe 4 // 1 initial + 3 retries
  }

  it should "succeed after transient failures followed by success" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 1.0)
    var calls  = 0
    val result = policy.execute(
      {
        calls += 1
        if (calls < 3) Left(fetchFailed)
        else Right(Paths.get("/recovered"))
      },
      "subject-c",
      testLogger,
    )
    result shouldBe Right(Paths.get("/recovered"))
    calls shouldBe 3
  }

  it should "not retry InvalidSubjectName (permanent error)" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 2.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Left(permanentError) },
      "bad-subject",
      testLogger,
    )
    result shouldBe Left(permanentError)
    calls shouldBe 1
  }

  it should "not retry WriteError (permanent error)" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 2.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Left(writeError) },
      "write-fail",
      testLogger,
    )
    result shouldBe Left(writeError)
    calls shouldBe 1
  }

  it should "not retry UnsupportedSchemaType (permanent error)" in {
    val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 2.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Left(unsupportedType) },
      "unsupported",
      testLogger,
    )
    result shouldBe Left(unsupportedType)
    calls shouldBe 1
  }

  it should "not retry when maxRetries is 0" in {
    val policy = RetryPolicy(maxRetries = 0, initialDelayMs = 1, backoffMultiplier = 2.0)
    var calls  = 0
    val result = policy.execute(
      { calls += 1; Left(fetchFailed) },
      "no-retry",
      testLogger,
    )
    result shouldBe Left(fetchFailed)
    calls shouldBe 1
  }
}
