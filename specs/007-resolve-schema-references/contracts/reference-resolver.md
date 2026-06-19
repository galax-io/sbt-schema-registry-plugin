# Contract: `ReferenceResolver` (pure core)

New file `src/main/scala/org/galaxio/avro/ReferenceResolver.scala`. Pure, no IO, no client,
no filesystem. The only effect is the injected `fetch` function.

## Public API

```scala
object ReferenceResolver {

  /** Transitively resolve all references reachable from `roots`.
    *
    * Pure, @tailrec, stack-safe BFS. Roots appear first in the result, followed by
    * transitively-discovered references (FR-010). Fail-fast: the first Left from `fetch`
    * short-circuits and is returned unchanged (FR-008).
    *
    * Identity is (subject, version): cycle-safe (FR-004) and divergent versions of the
    * same subject are both kept (spec clarification 2026-06-19).
    *
    * @param roots requested subjects (Latest or Pinned) from SubjectResolver
    * @param fetch (subject, Some(version)=pinned | None=latest) => resolved schema
    * @return roots-first, deduped list of Pinned subjects, or the first fetch error
    */
  def resolve(
      roots: List[RegistrySubject],
      fetch: (String, Option[Int]) => Either[DownloadError, ResolvedSchema],
  ): Either[DownloadError, List[RegistrySubject]]
}

final case class ResolvedSchema(
    subject: String,
    version: Int,
    references: List[SchemaReference],
)
```

## Behavioral contract

| ID | Given | When | Then |
|----|-------|------|------|
| RR-1 | `roots = [A]`, `A` has no refs | resolve | `Right([Pinned(A, vA)])` — single entry, roots-first |
| RR-2 | `A→B→C` (each pinned) | resolve `[A]` | `Right([Pinned(A,_), Pinned(B,1), Pinned(C,1)])` in BFS order |
| RR-3 | cycle `A→B→A` | resolve `[A]` | terminates; `A` and `B` each once; no `StackOverflow`; `A` fetched once |
| RR-4 | shared dep `A→B`, `A→C`, `B→D`, `C→D` | resolve `[A]` | `D` appears exactly once |
| RR-5 | divergent diamond `A→B@1`, `A→C→B@2` | resolve `[A]` | **both** `Pinned(B,1)` and `Pinned(B,2)` present |
| RR-6 | `A→MISSING@9`, fetch returns `Left` for MISSING | resolve `[A]` | `Left(SchemaFetchFailed("MISSING", _))`; no further fetches |
| RR-7 | deep chain depth N (e.g. 50 000) | resolve | completes without `StackOverflow` |
| RR-8 | root requested `Latest(A)` resolving to vA, also referenced as `A@vA` | resolve | `A` written once (visited key = resolved `(A, vA)`) |
| RR-9 | `fetch` invocation counter wrapped around stub | resolve cyclic/shared graph | each `(subject, requestedVersion)` fetched at most once (dedup proven, not just output-deduped) |

## Invariants

- **Purity**: same `(roots, fetch)` ⇒ same result. No global state, no clock, no IO.
- **Stack safety**: recursion is in tail position (`@tailrec` annotation present and compiles).
- **Ordering**: FIFO `Queue` ⇒ roots before transitive refs (RR-2).
- **Two-level dedup**: enqueue key `(subject, Option[Int])` (pre-fetch, cycle safety + divergent
  versions); visited key `(subject, Int)` (post-fetch, result identity). See data-model.md.
- **Error**: returns existing `DownloadError.SchemaFetchFailed` — no new error type.

## Test obligations (write first — Constitution III)

`src/test/scala/org/galaxio/avro/ReferenceResolverSpec.scala`, `AnyFlatSpec with Matchers`,
Map-based stub `fetch` (no registry, no mocks):

```scala
type Key = (String, Option[Int])
def stub(graph: Map[Key, ResolvedSchema]): (String, Option[Int]) => Either[DownloadError, ResolvedSchema] =
  (s, v) => graph.get((s, v)).toRight(DownloadError.SchemaFetchFailed(s, new RuntimeException("not found")))
```

Cover RR-1…RR-9. RR-9 wraps the stub in a call counter to prove cycle/shared-dep dedup prevents
refetch (not just output dedup).
