package org.galaxio.avro

/** sbt-version compatibility seam — sbt 2.x / Scala 3 axis.
  *
  * `Def.uncached` is native in sbt 2.x, so no enrichment is defined here. The shared sources wildcard-import this object; on
  * this axis it intentionally provides nothing.
  */
private[avro] object PluginCompat
