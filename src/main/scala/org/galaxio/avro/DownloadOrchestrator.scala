package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import sbt.util.Logger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Try

/** Resolved inputs for one `schemaRegistryDownload` run, decoupled from sbt settings so the orchestration can be unit-tested.
  */
final case class DownloadConfig(
    subjects: Seq[RegistrySubject],
    patterns: Seq[String],
    incremental: Boolean,
    parallelism: Int,
    retries: Int,
    resolveReferences: Boolean,
    targetFolder: Path,
    manifestFile: Path,
)

/** Outcome of a download run. `failed` is per-subject and non-fatal here — the caller decides whether to fail the build. */
final case class DownloadSummary(
    succeeded: Int,
    skipped: Int,
    failed: List[(RegistrySubject, DownloadError)],
)

/** Pure-ish core of the download task: pattern resolution → reference expansion → incremental planning → bounded-parallel
  * download → manifest update. The only effects are the injected `client`, manifest file IO and logging, so it is exercisable
  * with a mocked `SchemaRegistryClient` and a temp directory. `Left` is a fatal resolve/expand failure; per-subject download
  * failures are reported in [[DownloadSummary.failed]].
  */
object DownloadOrchestrator {

  def run(
      client: SchemaRegistryClient,
      cfg: DownloadConfig,
      logger: Logger,
  ): Either[DownloadError, DownloadSummary] =
    validate(cfg) match {
      case Some(err) => Left(err)
      case None      =>
        for {
          resolvedSubjects <- resolveSubjects(client, cfg, logger)
          expandedSubjects <- expandReferences(client, cfg, resolvedSubjects, logger)
        } yield download(client, cfg, expandedSubjects, logger)
    }

  /** First range violation in the config, if any — shared with the plugin so it can fail fast before opening a registry client.
    * Keeps the orchestrator self-contained for direct callers.
    */
  def validate(cfg: DownloadConfig): Option[DownloadError] =
    if (cfg.parallelism < 1 || cfg.parallelism > ParallelDownloader.MaxParallelism)
      Some(DownloadError.InvalidParallelism(cfg.parallelism))
    else if (cfg.retries < 0 || cfg.retries > ParallelDownloader.MaxRetries)
      Some(DownloadError.InvalidRetryConfig(cfg.retries))
    else None

  private def resolveSubjects(
      client: SchemaRegistryClient,
      cfg: DownloadConfig,
      logger: Logger,
  ): Either[DownloadError, List[RegistrySubject]] =
    if (cfg.patterns.isEmpty) Right(cfg.subjects.toList)
    else {
      val specs =
        cfg.subjects.map(s => SubjectSpec.Exact(s)).toList ++ cfg.patterns.map(p => SubjectSpec.Pattern(p)).toList
      SubjectResolver.resolve(client, specs).map { plan =>
        logger.info(s"Resolved ${plan.subjects.size} subject(s) from patterns")
        plan.subjects.toList
      }
    }

  private def expandReferences(
      client: SchemaRegistryClient,
      cfg: DownloadConfig,
      resolved: List[RegistrySubject],
      logger: Logger,
  ): Either[DownloadError, List[RegistrySubject]] =
    if (!cfg.resolveReferences) Right(resolved)
    else
      ReferenceResolver.resolve(resolved, Downloader.referenceFetch(client)).map { expanded =>
        val extra = expanded.size - resolved.size
        if (extra > 0) logger.info(s"Resolved $extra referenced schema(s)")
        expanded
      }

  private def download(
      client: SchemaRegistryClient,
      cfg: DownloadConfig,
      expandedSubjects: List[RegistrySubject],
      logger: Logger,
  ): DownloadSummary = {
    val manifest = loadManifest(cfg.manifestFile, cfg.incremental, logger)

    val decisions =
      if (cfg.incremental)
        IncrementalResolver.plan(
          manifest,
          expandedSubjects,
          s =>
            Try(client.getLatestSchemaMetadata(s).getVersion: Int).toEither.left
              .map(DownloadError.SchemaFetchFailed(s, _)),
        )
      else
        expandedSubjects.map(s => DownloadDecision.Download(s, "incremental disabled"))

    val (skips, downloads) = decisions.partition {
      case _: DownloadDecision.Skip => true
      case _                        => false
    }

    skips.foreach {
      case DownloadDecision.Skip(name, v) => logger.info(s"$name v$v → up to date")
      case _                              => ()
    }

    val downloader         =
      Downloader.withExternalClient(client, cfg.targetFolder, logger)
    val retryPolicy        = RetryPolicy(maxRetries = cfg.retries)
    val parallelDownloader = ParallelDownloader(downloader, cfg.parallelism, retryPolicy, logger)

    val toDownload = downloads.collect { case d: DownloadDecision.Download => d }
    toDownload.foreach(d => logger.info(s"${d.subject.name}: ${d.reason}"))

    val downloadResults = parallelDownloader.downloadAll(toDownload.map(_.subject))

    // Key by the full subject (name + version), not just name: reference resolution can yield
    // divergent pinned versions of the same subject, and a name-only map collapses them — wiring a
    // succeeded download to the wrong decision's resolved version in the manifest.
    val decisionsBySubject = toDownload.map(d => d.subject -> d).toMap
    val failures           = downloadResults.collect { case (s, Left(e)) => s -> e }

    val downloaded = downloadResults.collect { case (s, Right(_)) =>
      decisionsBySubject.get(s).flatMap(_.resolvedVersion).map(s.name -> _)
    }.flatten

    if (cfg.incremental && downloaded.nonEmpty) {
      val newManifest = IncrementalResolver.updatedManifest(manifest, downloaded)
      writeManifest(cfg.manifestFile, newManifest, logger)
    }

    DownloadSummary(downloadResults.count(_._2.isRight), skips.size, failures)
  }

  private[avro] def loadManifest(path: Path, incremental: Boolean, logger: Logger): VersionManifest =
    if (!incremental || !Files.exists(path)) VersionManifest.empty
    else
      Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither
        .flatMap(VersionManifest.fromJson) match {
        case Right(m) => m
        case Left(_)  =>
          logger.warn("Version manifest corrupted, re-downloading all schemas")
          VersionManifest.empty
      }

  private[avro] def writeManifest(path: Path, manifest: VersionManifest, logger: Logger): Unit =
    Try {
      Files.createDirectories(path.getParent)
      Files.write(path, manifest.toJson.getBytes(StandardCharsets.UTF_8))
    }.failed.foreach(e => logger.warn(s"Failed to write version manifest: ${e.getMessage}"))
}
