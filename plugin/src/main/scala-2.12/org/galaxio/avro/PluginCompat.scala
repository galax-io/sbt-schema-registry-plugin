package org.galaxio.avro

import sbt.Def

/** sbt-version compatibility seam — sbt 1.x / Scala 2.12 axis.
  *
  * `Def.uncached` is provided here as a no-op enrichment (mirroring the sbt2-compat shim) so the shared task bodies can call
  * `Def.uncached { ... }` uniformly on both axes; on the sbt 1.x axis it is a fallback — there is no
  * all-tasks-cached-by-default behaviour to opt out of. On the sbt 2.x axis (`src/main/scala-3`) `Def.uncached` is native and
  * forces side-effecting tasks to re-run every invocation instead of being served from the task cache.
  *
  * Shared sources wildcard-import this object; do not import members by name (the scala-3 variant is intentionally empty).
  */
private[avro] object PluginCompat {
  implicit class DefOps(private val d: Def.type) extends AnyVal {
    def uncached[A](a: A): A = a
  }
}
