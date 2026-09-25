package org.galaxio.avro

final case class CompatibilityReport(results: List[CompatibilityResult]) {

  lazy val compatible: List[CompatibilityResult.Compatible] =
    results.collect { case c: CompatibilityResult.Compatible => c }

  lazy val incompatible: List[CompatibilityResult.Incompatible] =
    results.collect { case i: CompatibilityResult.Incompatible => i }

  lazy val failed: List[CompatibilityResult.Failed] =
    results.collect { case f: CompatibilityResult.Failed => f }

  def isSuccess: Boolean = incompatible.isEmpty && failed.isEmpty
}
