package org.galaxio.avro

import sbt.util.Logger

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

final class ParallelDownloader private (
    downloader: Downloader,
    parallelism: Int,
    retryPolicy: RetryPolicy,
    logger: Logger,
) {

  def downloadAll(
      subjects: List[RegistrySubject],
  ): List[(RegistrySubject, Either[DownloadError, Path])] =
    if (subjects.isEmpty) List.empty
    else if (parallelism == 1) downloadSequential(subjects)
    else downloadParallel(subjects)

  private def downloadSequential(
      subjects: List[RegistrySubject],
  ): List[(RegistrySubject, Either[DownloadError, Path])] = {
    val total     = subjects.size
    val completed = new AtomicInteger(0)
    subjects.map { subject =>
      val result = retryPolicy.execute(downloader.schemaSubjectToFile(subject), subject.name, logger)
      val n      = completed.incrementAndGet()
      logProgress(subject.name, n, total, result)
      subject -> result
    }
  }

  private def downloadParallel(
      subjects: List[RegistrySubject],
  ): List[(RegistrySubject, Either[DownloadError, Path])] = {
    val total     = subjects.size
    val completed = new AtomicInteger(0)
    val pool      = Executors.newFixedThreadPool(parallelism)
    try {
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      val futures                       = subjects.map { subject =>
        Future {
          val result = retryPolicy.execute(downloader.schemaSubjectToFile(subject), subject.name, logger)
          val n      = completed.incrementAndGet()
          logProgress(subject.name, n, total, result)
          subject -> result
        }
      }
      Await.result(Future.sequence(futures), 30.minutes)
    } finally {
      pool.shutdownNow()
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
