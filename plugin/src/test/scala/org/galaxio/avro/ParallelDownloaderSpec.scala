package org.galaxio.avro

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.IOException
import java.nio.file.{Path, Paths}

class ParallelDownloaderSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def testLogger: Logger = mock[Logger]

  private val noRetry = RetryPolicy(maxRetries = 0, initialDelayMs = 1, backoffMultiplier = 1.0)

  private val subjects3 = List(
    RegistrySubject.Latest("subject-a"),
    RegistrySubject.Latest("subject-b"),
    RegistrySubject.Latest("subject-c"),
  )

  private def orderedDownloader(
      results: Seq[Either[DownloadError, Path]],
  ): Downloader = {
    val dl        = mock[Downloader]
    var callIndex = 0
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenAnswer((inv: InvocationOnMock) => {
        val idx = synchronized { val i = callIndex; callIndex += 1; i }
        if (idx < results.size) results(idx)
        else Left(DownloadError.SchemaFetchFailed("overflow", new IOException("too many calls")))
      })
    dl
  }

  // --- US1: Parallel Downloads ---

  "ParallelDownloader" should "download all subjects with parallelism > 1" in {
    val dl      = orderedDownloader(Seq(Right(Paths.get("/a")), Right(Paths.get("/b")), Right(Paths.get("/c"))))
    val pd      = ParallelDownloader(dl, parallelism = 2, noRetry, testLogger)
    val results = pd.downloadAll(subjects3)

    results should have size 3
    results.count(_._2.isRight) shouldBe 3
  }

  it should "download all subjects with parallelism exceeding subject count" in {
    val dl      = orderedDownloader(Seq(Right(Paths.get("/a")), Right(Paths.get("/b")), Right(Paths.get("/c"))))
    val pd      = ParallelDownloader(dl, parallelism = 10, noRetry, testLogger)
    val results = pd.downloadAll(subjects3)

    results should have size 3
    results.count(_._2.isRight) shouldBe 3
  }

  it should "handle empty subject list" in {
    val dl      = mock[Downloader]
    val pd      = ParallelDownloader(dl, parallelism = 4, noRetry, testLogger)
    val results = pd.downloadAll(List.empty)

    results shouldBe empty
  }

  it should "handle single subject" in {
    val dl      = mock[Downloader]
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenReturn(Right(Paths.get("/single")))
    val pd      = ParallelDownloader(dl, parallelism = 4, noRetry, testLogger)
    val results = pd.downloadAll(List(RegistrySubject.Latest("only-one")))

    results should have size 1
    results.head._2 shouldBe Right(Paths.get("/single"))
  }

  // --- US3: Partial Failure ---

  it should "attempt all subjects even when some fail (no fail-fast)" in {
    val dl      = orderedDownloader(
      Seq(
        Right(Paths.get("/ok-a")),
        Left(DownloadError.SchemaFetchFailed("subject-b", new IOException("timeout"))),
        Right(Paths.get("/ok-c")),
      ),
    )
    val pd      = ParallelDownloader(dl, parallelism = 2, noRetry, testLogger)
    val results = pd.downloadAll(subjects3)

    results should have size 3
    results.count(_._2.isRight) shouldBe 2
    results.count(_._2.isLeft) shouldBe 1
  }

  it should "collect all errors when multiple subjects fail" in {
    val dl      = orderedDownloader(
      Seq(
        Left(DownloadError.SchemaFetchFailed("subject-a", new IOException("refused"))),
        Left(DownloadError.SchemaFetchFailed("subject-b", new IOException("timeout"))),
        Right(Paths.get("/ok-c")),
      ),
    )
    val pd      = ParallelDownloader(dl, parallelism = 2, noRetry, testLogger)
    val results = pd.downloadAll(subjects3)

    results should have size 3
    results.count(_._2.isLeft) shouldBe 2
    results.count(_._2.isRight) shouldBe 1
  }

  it should "retry SchemaFetchFailed via RetryPolicy before reporting failure" in {
    val retryPolicy = RetryPolicy(maxRetries = 2, initialDelayMs = 1, backoffMultiplier = 1.0)
    var calls       = 0
    val dl          = mock[Downloader]
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenAnswer((_: InvocationOnMock) => {
        calls += 1
        Left(DownloadError.SchemaFetchFailed("always-fail", new IOException("down")))
      })

    val pd      = ParallelDownloader(dl, parallelism = 1, retryPolicy, testLogger)
    val results = pd.downloadAll(List(RegistrySubject.Latest("always-fail")))

    results should have size 1
    results.head._2.isLeft shouldBe true
    calls shouldBe 3
  }

  it should "not retry permanent errors (InvalidSubjectName)" in {
    val retryPolicy = RetryPolicy(maxRetries = 3, initialDelayMs = 1, backoffMultiplier = 1.0)
    var calls       = 0
    val dl          = mock[Downloader]
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenAnswer((_: InvocationOnMock) => {
        calls += 1
        Left(DownloadError.InvalidSubjectName("bad/name"))
      })

    val pd      = ParallelDownloader(dl, parallelism = 1, retryPolicy, testLogger)
    val results = pd.downloadAll(List(RegistrySubject.Latest("bad/name")))

    results should have size 1
    calls shouldBe 1
  }

  // --- US2: Sequential Fallback ---

  it should "execute sequentially when parallelism is 1 preserving order" in {
    val executionOrder = scala.collection.mutable.ListBuffer.empty[String]
    val dl             = mock[Downloader]
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenAnswer((inv: InvocationOnMock) => {
        val name = inv.getArgument[RegistrySubject](0).name
        synchronized { executionOrder += name }
        Thread.sleep(10)
        Right(Paths.get(s"/out/$name"))
      })

    val pd      = ParallelDownloader(dl, parallelism = 1, noRetry, testLogger)
    val results = pd.downloadAll(subjects3)

    results should have size 3
    results.count(_._2.isRight) shouldBe 3
    executionOrder.toList shouldBe List("subject-a", "subject-b", "subject-c")
  }

  it should "still apply retry policy in sequential mode" in {
    val retryPolicy = RetryPolicy(maxRetries = 1, initialDelayMs = 1, backoffMultiplier = 1.0)
    var calls       = 0
    val dl          = mock[Downloader]
    when(dl.schemaSubjectToFile(any[RegistrySubject]()))
      .thenAnswer((_: InvocationOnMock) => {
        calls += 1
        if (calls == 1) Left(DownloadError.SchemaFetchFailed("flaky", new IOException("timeout")))
        else Right(Paths.get("/recovered"))
      })

    val pd      = ParallelDownloader(dl, parallelism = 1, retryPolicy, testLogger)
    val results = pd.downloadAll(List(RegistrySubject.Latest("flaky")))

    results should have size 1
    results.head._2 shouldBe Right(Paths.get("/recovered"))
    calls shouldBe 2
  }

  // --- Validation ---

  it should "reject parallelism below 1" in {
    val error = intercept[IllegalArgumentException] {
      ParallelDownloader(mock[Downloader], parallelism = 0, noRetry, testLogger)
    }
    error.getMessage should include("1")
  }

  it should "reject parallelism above 32" in {
    val error = intercept[IllegalArgumentException] {
      ParallelDownloader(mock[Downloader], parallelism = 33, noRetry, testLogger)
    }
    error.getMessage should include("32")
  }

  it should "accept boundary parallelism values" in {
    noException should be thrownBy ParallelDownloader(mock[Downloader], 1, noRetry, testLogger)
    noException should be thrownBy ParallelDownloader(mock[Downloader], 32, noRetry, testLogger)
  }
}
