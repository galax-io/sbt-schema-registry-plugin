package org.galaxio.avro

object IncrementalResolver {

  def plan(
      manifest: VersionManifest,
      subjects: List[RegistrySubject],
      registryVersions: String => Either[DownloadError, Int],
  ): List[DownloadDecision] =
    subjects.map {
      case s @ RegistrySubject.Pinned(name, version) =>
        manifest.versionOf(name) match {
          case Some(`version`) => DownloadDecision.Skip(name, version)
          case _               => DownloadDecision.Download(s, s"pinned v$version, not cached")
        }

      case s @ RegistrySubject.Latest(name) =>
        registryVersions(name) match {
          case Right(v) if manifest.versionOf(name).contains(v) =>
            DownloadDecision.Skip(name, v)
          case Right(v)                                         =>
            val reason = manifest
              .versionOf(name)
              .fold(s"new, registry v$v")(local => s"local v$local → registry v$v")
            DownloadDecision.Download(s, reason)
          case Left(_)                                          =>
            DownloadDecision.Download(s, "version check failed, re-downloading")
        }
    }

  def updatedManifest(
      manifest: VersionManifest,
      downloaded: List[(String, Int)],
  ): VersionManifest =
    manifest.updatedAll(downloaded)
}
