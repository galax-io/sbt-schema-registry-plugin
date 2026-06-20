package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

import scala.collection.JavaConverters._
import scala.util.Try

/** Pure core for the list-subjects task: fetch + transform, no logging or side effects beyond the injected client. Presentation
  * lives in `SchemaDownloaderPlugin`.
  */
object SubjectExplorer {

  /** @param parallelism
    *   number of concurrent per-subject fetches (1 = sequential). Reuses the plugin's `schemaRegistryParallelism` budget.
    */
  def listAll(
      client: SchemaRegistryClient,
      filter: Option[String],
      parallelism: Int = 1,
  ): Either[DownloadError, SubjectListing] =
    for {
      names <- Try(client.getAllSubjects.asScala.toList.sorted).toEither.left
                 .map(DownloadError.SubjectListFailed)
      // Filter by name BEFORE per-subject fetch: avoids fetching versions/compatibility for subjects
      // the user excluded, and scopes fail-fast to the subjects actually requested.
      infos <- fetchInfos(client, filterNames(names, filter), parallelism)
    } yield SubjectListing(infos)

  // Empty/absent filter keeps all; otherwise the shared case-insensitive substring predicate.
  private def filterNames(names: List[String], filter: Option[String]): List[String] =
    filter.fold(names)(f => names.filter(SubjectListing.nameMatches(_, f)))

  // Fetch each kept subject (bounded concurrency), then fail-fast: the first Left (in subject order) wins.
  // The Try guards the parallel path so the core never throws (e.g. an Await timeout/interrupt) — FR-010.
  private def fetchInfos(
      client: SchemaRegistryClient,
      names: List[String],
      parallelism: Int,
  ): Either[DownloadError, List[SubjectInfo]] =
    Try(BoundedParallel.traverse(names, parallelism)(fetchInfo(client, _))).toEither.left
      .map(DownloadError.SubjectListFailed)
      .flatMap(EitherOps.sequence)

  private def fetchInfo(
      client: SchemaRegistryClient,
      subject: String,
  ): Either[DownloadError, SubjectInfo] =
    Try(client.getAllVersions(subject).asScala.map(_.intValue).toList.sorted).toEither.left
      .map(e => DownloadError.SubjectVersionsFetchFailed(subject, e))
      .map { versions =>
        // Best-effort: a missing or unreadable subject-level compatibility is reported as None.
        val compat = Try(Option(client.getCompatibility(subject))).toOption.flatten
        SubjectInfo(subject, versions, compat)
      }
}
