package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

import scala.collection.JavaConverters._
import scala.util.Try

object SubjectResolver {

  def resolve(
      client: SchemaRegistryClient,
      specs: List[SubjectSpec],
  ): Either[DownloadError, DownloadPlan] = {
    val (exacts, patterns) = specs.partition {
      case _: SubjectSpec.Exact   => true
      case _: SubjectSpec.Pattern => false
    }

    for {
      compiled <- compilePatterns(patterns.collect { case SubjectSpec.Pattern(r) => r })
      matched  <- if (compiled.isEmpty) Right(Nil) else matchSubjects(client, compiled)
    } yield {
      val exactSubjects = exacts.collect { case SubjectSpec.Exact(s) => s }
      val allSubjects   = exactSubjects ++ matched
      val deduped       = allSubjects
        .foldLeft((List.empty[RegistrySubject], Set.empty[String])) { case ((acc, seen), s) =>
          if (seen.contains(s.name)) (acc, seen) else (s :: acc, seen + s.name)
        }
        ._1
        .reverse
      DownloadPlan(deduped)
    }
  }

  private def compilePatterns(
      regexes: List[String],
  ): Either[DownloadError, List[scala.util.matching.Regex]] =
    EitherOps.traverse(regexes)(r => Try(r.r).toEither.left.map(e => DownloadError.InvalidPattern(r, e)))

  // Loads all subjects into memory; O(subjects × patterns) filtering
  private def matchSubjects(
      client: SchemaRegistryClient,
      compiled: List[scala.util.matching.Regex],
  ): Either[DownloadError, List[RegistrySubject]] =
    Try(client.getAllSubjects.asScala.toList).toEither.left
      .map(DownloadError.SubjectListFailed)
      .map { allNames =>
        allNames
          .filter(name => compiled.exists(_.pattern.matcher(name).matches()))
          .map(RegistrySubject.latest)
      }
}
