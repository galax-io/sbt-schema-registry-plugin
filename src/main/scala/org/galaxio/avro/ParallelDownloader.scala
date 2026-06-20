package org.galaxio.avro

import sbt.util.Logger

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

final class ParallelDownloader private (
    downloader: Downloader,
    parallelism: Int,
    retryPolicy: RetryPolicy,
    logger: Logger,
) {

  def downloadAll(
      subjects: List[RegistrySubject],
  ): List[(RegistrySubject, Either[DownloadError, Path])] = {
    val total     = subjects.size
    val completed = new AtomicInteger(0)
    BoundedParallel.traverse(subjects, parallelism) { subject =>
      val result = retryPolicy.execute(downloader.schemaSubjectToFile(subject), subject.name, logger)
      val n      = completed.incrementAndGet()
      logProgress(subject.name, n, total, result)
      subject -> result
    }
  }

  private def logProgress(
      name: String,
      n: Int,
      total: Int,
      result: Either[DownloadError, Path],
  ): Unit =
    result match {
      case Right(_) => logger.info(s"Downloaded $name ($n/$total)")
      case Left(e)  => logger.error(s"Failed $name ($n/$total): ${e.message}")
    }
}

object ParallelDownloader {

  val MaxParallelism = 32
  val MaxRetries     = 10

  def apply(
      downloader: Downloader,
      parallelism: Int,
      retryPolicy: RetryPolicy,
      logger: Logger,
  ): ParallelDownloader = {
    require(
      parallelism >= 1 && parallelism <= MaxParallelism,
      s"Parallelism must be between 1 and $MaxParallelism, got $parallelism",
    )
    new ParallelDownloader(downloader, parallelism, retryPolicy, logger)
  }
}
