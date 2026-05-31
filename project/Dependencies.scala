import sbt.*

object Dependencies {
  private object Versions {
    val avro           = "1.12.0"
    val schReqClient   = "8.0.0"
    val scalatest      = "3.2.19"
    val mockito        = "2.0.0"
    val testcontainers = "1.19.3"
  }

  lazy val avroCompiler: ModuleID = "org.apache.avro" % "avro-compiler" % Versions.avro
  lazy val avroCore: ModuleID     = "org.apache.avro" % "avro"          % Versions.avro

  lazy val schemaRegistryClient: ModuleID = "io.confluent" % "kafka-schema-registry-client" % Versions.schReqClient

  lazy val scalatest: ModuleID    = "org.scalatest" %% "scalatest"               % Versions.scalatest % Test
  lazy val mockitoScala: ModuleID = "org.mockito"   %% "mockito-scala-scalatest" % Versions.mockito   % Test

  lazy val testcontainersKafka: ModuleID =
    "org.testcontainers" % "kafka" % Versions.testcontainers % IntegrationTest
}
