package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
    // 4 tasks each sleeping 200ms on a pool of 4 finish well under the 800ms sequential sum.
    val start   = System.nanoTime()
    val results = BoundedParallel.traverse(List(1, 2, 3, 4), 4) { i => Thread.sleep(200); i }
    val elapsed = (System.nanoTime() - start) / 1000000
    results shouldBe List(1, 2, 3, 4)
    elapsed should be < 700L
  }
}
