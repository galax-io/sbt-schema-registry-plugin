package org.galaxio.avro

import sbt.util.Logger

final case class RetryPolicy(
    maxRetries: Int = 3,
    initialDelayMs: Long = 100,
    backoffMultiplier: Double = 2.0,
) {

  def execute[A](
      op: => Either[DownloadError, A],
      subjectName: String,
      logger: Logger,
  ): Either[DownloadError, A] = {
    var lastResult = op
    var attempt    = 0
    while (attempt < maxRetries && isRetryable(lastResult)) {
      attempt += 1
      val delayMs = (initialDelayMs * math.pow(backoffMultiplier, attempt - 1)).toLong
      lastResult.left.foreach { err =>
        logger.warn(s"Retry $attempt/$maxRetries for $subjectName: ${err.message}")
      }
      Thread.sleep(delayMs)
      lastResult = op
    }
    lastResult
  }

  private def isRetryable[A](result: Either[DownloadError, A]): Boolean =
    result match {
      case Left(_: DownloadError.SchemaFetchFailed) => true
      case _                                        => false
    }
}
