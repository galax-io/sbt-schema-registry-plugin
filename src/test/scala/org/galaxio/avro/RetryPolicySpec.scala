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

  "RetryPolicy.delayMs" should "grow exponentially with the backoff multiplier" in {
    val policy = RetryPolicy(initialDelayMs = 100, backoffMultiplier = 2.0)
    policy.delayMs(1) shouldBe 100L // 100 * 2^0
    policy.delayMs(2) shouldBe 200L // 100 * 2^1
    policy.delayMs(3) shouldBe 400L // 100 * 2^2
    policy.delayMs(4) shouldBe 800L // 100 * 2^3
  }

  it should "stay constant when the multiplier is 1.0" in {
    val policy = RetryPolicy(initialDelayMs = 50, backoffMultiplier = 1.0)
    policy.delayMs(1) shouldBe 50L
    policy.delayMs(2) shouldBe 50L
    policy.delayMs(3) shouldBe 50L
  }

  it should "support fractional multipliers, truncating to whole millis" in {
    val policy = RetryPolicy(initialDelayMs = 100, backoffMultiplier = 1.5)
    policy.delayMs(1) shouldBe 100L // 100 * 1.5^0
    policy.delayMs(2) shouldBe 150L // 100 * 1.5^1
    policy.delayMs(3) shouldBe 225L // 100 * 1.5^2
  }

  it should "actually back off between retries (observable elapsed time)" in {
    // initial=20, mult=2 → delays 20+40+80 = 140ms; exponential, not linear (which would be 60ms).
    val policy    = RetryPolicy(maxRetries = 3, initialDelayMs = 20, backoffMultiplier = 2.0)
    val started   = System.nanoTime()
    policy.execute(Left(fetchFailed), "backoff-subject", testLogger)
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    elapsedMs should be >= 140L
  }
}
