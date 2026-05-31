import sbt.*

object Dependencies {
  private object Versions {
    val avro         = "1.12.0"
    val schReqClient = "8.0.0"
    val scalatest    = "3.2.19"
    val mockito      = "1.17.37"
    val wireMock     = "2.35.0"
  }

  lazy val avroCompiler: ModuleID = "org.apache.avro" % "avro-compiler" % Versions.avro
  lazy val avroCore: ModuleID     = "org.apache.avro" % "avro"          % Versions.avro

  lazy val schemaRegistryClient: ModuleID = "io.confluent" % "kafka-schema-registry-client" % Versions.schReqClient

  lazy val scalatest: ModuleID    = "org.scalatest" %% "scalatest"               % Versions.scalatest % Test
  lazy val mockitoScala: ModuleID = "org.mockito"   %% "mockito-scala-scalatest" % Versions.mockito   % Test
  lazy val wireMock: ModuleID     = "com.github.tomakehurst" % "wiremock-jre8" % Versions.wireMock % Test
}
