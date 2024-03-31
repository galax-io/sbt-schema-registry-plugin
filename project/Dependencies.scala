import sbt._

object Dependencies {
  private object Versions {
    val avro         = "1.11.1"
    val schReqClient = "7.3.1"
  }

  lazy val avroCompiler: ModuleID = "org.apache.avro" % "avro-compiler" % Versions.avro
  lazy val avroCore: ModuleID     = "org.apache.avro" % "avro"          % Versions.avro

  lazy val schemaRegistryClient: ModuleID = "io.confluent" % "kafka-schema-registry-client" % Versions.schReqClient

}
