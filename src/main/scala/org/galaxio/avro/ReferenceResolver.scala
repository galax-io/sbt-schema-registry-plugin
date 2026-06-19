package org.galaxio.avro

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** A schema fetched during reference resolution.
  *
  * Carries only what the graph walk needs: its concrete (resolved) version and the references it declares. Bodies are written
  * later by [[Downloader]].
  */
final case class ResolvedSchema(
    subject: String,
    version: Int,
    references: List[SchemaReference],
)

/** Pure, stack-safe transitive resolver for schema references.
  *
  * Given root subjects and an injected `fetch`, walks the reference graph breadth-first and returns every reachable schema as a
  * pinned subject (roots first, then transitive refs).
  *
  * Identity is `(subject, version)` (spec 007 clarification): the walk is cycle-safe and keeps divergent versions of the same
  * subject. The resolver performs no IO — `fetch` is the only effect, so it is fully testable with a `Map`-based stub.
  */
object ReferenceResolver {

  /** @param roots
    *   requested subjects (Latest or Pinned)
    * @param fetch
    *   `(subject, Some(version)=pinned | None=latest) => resolved schema`
    * @return
    *   roots-first, deduped list of `RegistrySubject.Pinned`, or the first fetch error
    */
  def resolve(
      roots: List[RegistrySubject],
      fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema],
  ): Either[DownloadError, List[RegistrySubject]] = {

    @tailrec
    def loop(
        queue: Queue[(String, Option[Int])],
        enqueued: Set[(String, Option[Int])],
        visited: Set[(String, Int)],
        acc: Vector[RegistrySubject],
    ): Either[DownloadError, List[RegistrySubject]] =
      queue.dequeueOption match {
        case None                             => Right(acc.toList)
        case Some(((subject, version), rest)) =>
          fetch(subject, version) match {
            case Left(err)       => Left(err) // fail-fast (FR-008)
            case Right(resolved) =>
              val resolvedKey = (resolved.subject, resolved.version)
              if (visited.contains(resolvedKey)) {
                // shared dependency or cycle already materialised — skip
                loop(rest, enqueued, visited, acc)
              } else {
                // Skip a child if it is already queued, or if its pinned version is already
                // resolved (prevents cyclic back-edges from refetching). Divergent versions
                // of the same subject survive because their pinned versions differ.
                val newChildren: List[(String, Option[Int])] =
                  resolved.references
                    .map(r => (r.subject, Some(r.version)))
                    .filterNot { case (s, v) =>
                      enqueued.contains((s, v)) || v.exists(ver => visited.contains((s, ver)))
                    }
                loop(
                  newChildren.foldLeft(rest)((q, c) => q.enqueue(c)),
                  enqueued ++ newChildren,
                  visited + resolvedKey,
                  acc :+ RegistrySubject.Pinned(resolved.subject, resolved.version),
                )
              }
          }
      }

    val requested            = roots.map {
      case RegistrySubject.Pinned(name, v) => (name, Some(v))
      case RegistrySubject.Latest(name)    => (name, None)
    }
    val (seedQueue, seedSet) =
      requested.foldLeft((Queue.empty[(String, Option[Int])], Set.empty[(String, Option[Int])])) { case ((q, seen), elem) =>
        if (seen.contains(elem)) (q, seen) else (q.enqueue(elem), seen + elem)
      }
    loop(seedQueue, seedSet, Set.empty, Vector.empty)
  }
}
