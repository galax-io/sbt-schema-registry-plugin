package org.galaxio.avro

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Bounded-concurrency `traverse`: apply `f` to every item with at most `parallelism` concurrent evaluations, preserving input
  * order. Shared by `ParallelDownloader` (per-subject download) and `SubjectExplorer` (per-subject metadata fetch) so the
  * thread-pool lifecycle lives in one place.
  */
object BoundedParallel {

  private val AwaitTimeout = 30.minutes

  /** @param parallelism
    *   `<= 1` runs sequentially with no thread pool; otherwise a fixed pool sized to `min(parallelism, items.size)` runs the
    *   work concurrently. The pool is always shut down. Result order matches `items`.
    */
  def traverse[A, B](items: List[A], parallelism: Int)(f: A => B): List[B] =
    if (items.isEmpty) Nil
    else if (parallelism <= 1) items.map(f)
    else {
      val pool = Executors.newFixedThreadPool(math.min(parallelism, items.size))
      try {
        implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
        Await.result(Future.traverse(items)(a => Future(f(a))), AwaitTimeout)
      } finally {
        pool.shutdownNow()
      }
    }
}
