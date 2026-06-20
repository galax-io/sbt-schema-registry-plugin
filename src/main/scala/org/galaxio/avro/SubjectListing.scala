package org.galaxio.avro

final case class SubjectListing(subjects: List[SubjectInfo]) {
  def size: Int = subjects.size

  /** Keep subjects whose name matches `filter` (case-insensitive substring). Empty filter matches all. */
  def matching(filter: String): SubjectListing =
    copy(subjects = subjects.filter(s => SubjectListing.nameMatches(s.name, filter)))
}

object SubjectListing {

  /** The one definition of the list-subjects filter predicate: case-insensitive substring on the subject name. Used both for
    * pre-fetch name filtering (`SubjectExplorer`) and post-fetch listing filtering (`matching`).
    */
  def nameMatches(name: String, filter: String): Boolean =
    name.toLowerCase.contains(filter.toLowerCase)
}
