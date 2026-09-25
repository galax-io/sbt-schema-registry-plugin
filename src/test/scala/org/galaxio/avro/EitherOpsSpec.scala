package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EitherOpsSpec extends AnyFlatSpec with Matchers {

  "EitherOps.sequence" should "collect all Rights preserving order" in {
    EitherOps.sequence(List(Right(1), Right(2), Right(3))) shouldBe Right(List(1, 2, 3))
  }

  it should "return Right(Nil) for an empty list" in {
    EitherOps.sequence(List.empty[Either[String, Int]]) shouldBe Right(Nil)
  }

  it should "short-circuit to the first Left in input order" in {
    EitherOps.sequence(List(Right(1), Left("a"), Right(2), Left("b"))) shouldBe Left("a")
  }

  "EitherOps.traverse" should "map each element and collect in order" in {
    EitherOps.traverse(List("1", "2", "3"))(s => Right(s.toInt)) shouldBe Right(List(1, 2, 3))
  }

  it should "stop evaluating f after the first Left" in {
    var calls  = 0
    val result = EitherOps.traverse(List(1, 2, 3, 4)) { n =>
      calls += 1
      if (n == 2) Left(s"boom-$n") else Right(n)
    }
    result shouldBe Left("boom-2")
    calls shouldBe 2 // f evaluated for 1 and 2 only, then short-circuits
  }
}
