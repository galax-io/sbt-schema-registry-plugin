package org.galaxio.avro

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class ReferenceResolverSpec extends AnyFlatSpec with Matchers {

  private type Key   = (String, Option[Int])
  private type Fetch = (String, Option[Int]) => Either[DownloadError, ResolvedSchema]

  private def k(s: String, v: Option[Int] = None): Key = (s, v)

  private def ref(subject: String, version: Int): SchemaReference =
    SchemaReference(subject, subject, version)

  private def schema(subject: String, version: Int, refs: SchemaReference*): ResolvedSchema =
    ResolvedSchema(subject, version, refs.toList)

  private def stub(graph: Map[Key, ResolvedSchema]): Fetch =
    (s, v) => graph.get((s, v)).toRight(DownloadError.SchemaFetchFailed(s, new RuntimeException(s"not found: $s@$v")))

  /** Wrap a fetch to record how many times each (subject, requestedVersion) was requested. */
  private def counting(underlying: Fetch): (Fetch, mutable.Map[Key, Int]) = {
    val counts         = mutable.Map.empty[Key, Int]
    val counted: Fetch = (s, v) => {
      counts.update((s, v), counts.getOrElse((s, v), 0) + 1)
      underlying(s, v)
    }
    (counted, counts)
  }

  private def pinned(subject: String, version: Int): RegistrySubject =
    RegistrySubject.Pinned(subject, version)

  // --- RR-0: empty roots ---

  "resolve" should "return an empty list for empty roots" in {
    ReferenceResolver.resolve(Nil, stub(Map.empty)) shouldBe Right(Nil)
  }

  // --- RR-1: no references ---

  it should "return a single pinned entry for a root with no references (RR-1)" in {
    val g = Map(k("A") -> schema("A", 1))
    ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g)) shouldBe
      Right(List(pinned("A", 1)))
  }

  // --- RR-2: transitive A -> B -> C, BFS roots-first ---

  it should "resolve a transitive chain A->B->C in roots-first order (RR-2)" in {
    val g = Map(
      k("A")          -> schema("A", 1, ref("B", 1)),
      k("B", Some(1)) -> schema("B", 1, ref("C", 1)),
      k("C", Some(1)) -> schema("C", 1),
    )
    ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g)) shouldBe
      Right(List(pinned("A", 1), pinned("B", 1), pinned("C", 1)))
  }

  // --- RR-8: latest root also referenced at its resolved version -> emitted once ---

  it should "emit a subject once when a latest root is also referenced at its resolved version (RR-8)" in {
    val g   = Map(
      k("A")          -> schema("A", 1, ref("B", 1)),
      k("B", Some(1)) -> schema("B", 1, ref("A", 1)),
      k("A", Some(1)) -> schema("A", 1, ref("B", 1)),
    )
    val out = ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g)).getOrElse(fail())
    out.count(_ == pinned("A", 1)) shouldBe 1
    out should contain(pinned("B", 1))
  }

  // --- RR-3: cycle A<->B terminates, each once, A fetched once, no SOE ---

  it should "terminate on a cycle A<->B with each subject once and A fetched once (RR-3)" in {
    val g           = Map(
      k("A")          -> schema("A", 1, ref("B", 1)),
      k("B", Some(1)) -> schema("B", 1, ref("A", 1)),
      k("A", Some(1)) -> schema("A", 1, ref("B", 1)),
    )
    val (f, counts) = counting(stub(g))
    val out         = ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), f).getOrElse(fail())

    out.map(_.name) shouldBe List("A", "B")
    out.distinct shouldBe out
    counts.getOrElse(k("A"), 0) shouldBe 1
    counts.getOrElse(k("A", Some(1)), 0) shouldBe 0 // back-edge must not refetch A
  }

  // --- RR-4: shared dependency D resolved once, BFS order ---

  it should "resolve a shared dependency exactly once in BFS order (RR-4)" in {
    val g   = Map(
      k("A")          -> schema("A", 1, ref("B", 1), ref("C", 1)),
      k("B", Some(1)) -> schema("B", 1, ref("D", 1)),
      k("C", Some(1)) -> schema("C", 1, ref("D", 1)),
      k("D", Some(1)) -> schema("D", 1),
    )
    val out = ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g)).getOrElse(fail())
    out.map(_.name) shouldBe List("A", "B", "C", "D")
    out.count(_.name == "D") shouldBe 1
  }

  // --- RR-5: divergent-version diamond keeps both B@1 and B@2 ---

  it should "keep both versions in a divergent-version diamond (RR-5)" in {
    val g   = Map(
      k("A")          -> schema("A", 1, ref("B", 1), ref("C", 1)),
      k("B", Some(1)) -> schema("B", 1),
      k("C", Some(1)) -> schema("C", 1, ref("B", 2)),
      k("B", Some(2)) -> schema("B", 2),
    )
    val out = ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g)).getOrElse(fail())
    out.toSet shouldBe Set(pinned("A", 1), pinned("B", 1), pinned("C", 1), pinned("B", 2))
  }

  // --- RR-7: deep chain is stack-safe ---

  it should "resolve a deep reference chain without StackOverflow (RR-7)" in {
    val depth = 10000
    val g     = (0 until depth).map { i =>
      val key: Key                   = if (i == 0) k("s0") else k(s"s$i", Some(1))
      val refs: Seq[SchemaReference] = if (i < depth - 1) Seq(ref(s"s${i + 1}", 1)) else Nil
      key -> ResolvedSchema(s"s$i", 1, refs.toList)
    }.toMap
    ReferenceResolver.resolve(List(RegistrySubject.Latest("s0")), stub(g)).map(_.size) shouldBe Right(depth)
  }

  // --- RR-6: fail-fast on first fetch error ---

  it should "fail fast with the failing subject on a fetch error (RR-6)" in {
    val g   = Map(k("A") -> schema("A", 1, ref("MISSING", 9)))
    val out = ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), stub(g))
    out shouldBe a[Left[_, _]]
    val err = out.swap.getOrElse(fail())
    err shouldBe a[DownloadError.SchemaFetchFailed]
    err.asInstanceOf[DownloadError.SchemaFetchFailed].subject shouldBe "MISSING"
  }

  // --- RR-9: dedup prevents refetch (count proof) ---

  it should "fetch each subject at most once for a shared-dependency graph (RR-9)" in {
    val g           = Map(
      k("A")          -> schema("A", 1, ref("B", 1), ref("C", 1)),
      k("B", Some(1)) -> schema("B", 1, ref("D", 1)),
      k("C", Some(1)) -> schema("C", 1, ref("D", 1)),
      k("D", Some(1)) -> schema("D", 1),
    )
    val (f, counts) = counting(stub(g))
    ReferenceResolver.resolve(List(RegistrySubject.Latest("A")), f).getOrElse(fail())

    counts.getOrElse(k("D", Some(1)), 0) shouldBe 1
    counts.values.sum shouldBe 4 // A, B, C, D each exactly once
  }

  // --- Pinned roots pass through their own version ---

  it should "resolve a pinned root at its pinned version (RR-2 variant)" in {
    val g = Map(
      k("A", Some(2)) -> schema("A", 2, ref("B", 1)),
      k("B", Some(1)) -> schema("B", 1),
    )
    ReferenceResolver.resolve(List(RegistrySubject.Pinned("A", 2)), stub(g)) shouldBe
      Right(List(pinned("A", 2), pinned("B", 1)))
  }
}
