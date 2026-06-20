package org.galaxio.avro

/** Either combinators shared by the resolver/explorer pipelines. */
private[avro] object EitherOps {

  /** Collapse a list of results into a result of a list, short-circuiting to the FIRST `Left` in input order. */
  def sequence[E, A](results: List[Either[E, A]]): Either[E, List[A]] =
    traverse(results)(identity)

  /** Map each element to an `Either` and collect, short-circuiting to the FIRST `Left` in input order. Once a `Left` is hit,
    * `f` is not evaluated for the remaining elements.
    */
  def traverse[A, E, B](items: List[A])(f: A => Either[E, B]): Either[E, List[B]] =
    items
      .foldLeft(Right(Nil): Either[E, List[B]]) { (acc, a) =>
        for {
          xs <- acc
          x  <- f(a)
        } yield x :: xs
      }
      .map(_.reverse)
}
