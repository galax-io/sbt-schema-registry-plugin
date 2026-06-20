package org.galaxio.avro

final case class SubjectListing(subjects: List[SubjectInfo]) {
  def size: Int = subjects.size
}

object SubjectListing {

  /** The one definition of the list-subjects filter predicate: case-insensitive substring on the subject name. Used for
    * pre-fetch name filtering in `SubjectExplorer`.
    */
  def nameMatches(name: String, filter: String): Boolean =
    name.toLowerCase.contains(filter.toLowerCase)
}
