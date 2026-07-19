import sbt.*

object Dependencies {
  private object Versions {
    val schReqClient     = "8.3.0"
    val scalatest        = "3.2.20"
    val mockito          = "2.2.3"
    val testcontainers   = "1.21.4"
    val collectionCompat = "2.14.0"
  }

  lazy val schemaRegistryClient: ModuleID = "io.confluent" % "kafka-schema-registry-client" % Versions.schReqClient

  // Backports `scala.jdk.CollectionConverters` to Scala 2.12 (no-op on Scala 3, which has it in stdlib),
  // so a single cross-compatible import compiles on both the sbt-1 (2.12) and sbt-2 (3) axes.
  lazy val collectionCompat: ModuleID =
    "org.scala-lang.modules" %% "scala-collection-compat" % Versions.collectionCompat

  lazy val scalatest: ModuleID    = "org.scalatest" %% "scalatest"               % Versions.scalatest
  lazy val mockitoScala: ModuleID = "org.mockito"   %% "mockito-scala-scalatest" % Versions.mockito

  lazy val testcontainersKafka: ModuleID = "org.testcontainers" % "kafka" % Versions.testcontainers

  lazy val protobufProvider: ModuleID   = "io.confluent" % "kafka-protobuf-provider"    % Versions.schReqClient
  lazy val jsonSchemaProvider: ModuleID = "io.confluent" % "kafka-json-schema-provider" % Versions.schReqClient
}
