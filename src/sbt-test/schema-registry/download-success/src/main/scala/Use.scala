// References the case class avrohugger generates from the downloaded schema.
// If the downloaded .avsc is not a valid Avro schema, codegen/compile fails here.
object Use {
  val order: it.e2e.Order = it.e2e.Order(1L)
}
