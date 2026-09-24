package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

class BoundedParallelSpec extends AnyFlatSpec with Matchers {

  "BoundedParallel.traverse" should "return Nil for empty input" in {
    BoundedParallel.traverse(List.empty[Int], 4)(_ * 2) shouldBe Nil
  }

  it should "preserve input order when sequential" in {
    BoundedParallel.traverse(List(1, 2, 3, 4), 1)(_ * 2) shouldBe List(2, 4, 6, 8)
  }

  it should "preserve input order when parallel" in {
    BoundedParallel.traverse((1 to 20).toList, 4)(_ + 1) shouldBe (2 to 21).toList
  }

  it should "apply f to every element exactly once when parallel" in {
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[Int]()
    val out  = BoundedParallel.traverse((1 to 50).toList, 8) { i => seen.add(i); i }
    out shouldBe (1 to 50).toList
    seen.size shouldBe 50
  }

  it should "treat parallelism <= 1 as sequential without error" in {
    BoundedParallel.traverse(List(1, 2, 3), 0)(_ + 10) shouldBe List(11, 12, 13)
  }

  it should "actually run elements concurrently when parallelism > 1" in {
    // Deterministic proof (no timing margins): every task blocks on a shared latch that only
    // reaches zero once ALL `parallelism` tasks are running at once. If traverse ran sequentially
    // the first task would wait forever and time out → await returns false and the test fails.
    val parallelism = 4
    val latch       = new CountDownLatch(parallelism)
    val current     = new AtomicInteger(0)
    val maxObserved = new AtomicInteger(0)

    val results = BoundedParallel.traverse((1 to parallelism).toList, parallelism) { i =>
      val running    = current.incrementAndGet()
      maxObserved.getAndUpdate(prev => math.max(prev, running))
      latch.countDown()
      val allStarted = latch.await(10, TimeUnit.SECONDS)
      current.decrementAndGet()
      allStarted shouldBe true
      i
    }

    results shouldBe (1 to parallelism).toList
    maxObserved.get shouldBe parallelism // all `parallelism` tasks were in flight simultaneously
  }

  it should "never exceed the parallelism bound" in {
    // 12 overlapping tasks (each briefly sleeping) on a pool of 3: a correct bound keeps the live
    // count <= 3 at every instant. A pool that over-subscribes would record a violation.
    val parallelism = 3
    val current     = new AtomicInteger(0)
    val violations  = new AtomicInteger(0)

    val results = BoundedParallel.traverse((1 to 12).toList, parallelism) { i =>
      if (current.incrementAndGet() > parallelism) violations.incrementAndGet()
      Thread.sleep(20)
      current.decrementAndGet()
      i
    }

    results shouldBe (1 to 12).toList
    violations.get shouldBe 0
  }
}
