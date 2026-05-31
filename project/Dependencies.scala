import sbt.*

object Dependencies {
  private object Versions {
    val schReqClient   = "8.2.1"
    val scalatest      = "3.2.19"
    val mockito        = "2.0.0"
    val testcontainers = "1.21.3"
  }

  lazy val schemaRegistryClient: ModuleID = "io.confluent" % "kafka-schema-registry-client" % Versions.schReqClient

  lazy val scalatest: ModuleID    = "org.scalatest" %% "scalatest"               % Versions.scalatest
  lazy val mockitoScala: ModuleID = "org.mockito"   %% "mockito-scala-scalatest" % Versions.mockito

  lazy val testcontainersKafka: ModuleID = "org.testcontainers" % "kafka" % Versions.testcontainers
}
