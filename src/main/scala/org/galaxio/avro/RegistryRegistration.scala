package org.galaxio.avro

import java.io.File

final case class RegistryRegistration(
    subject: String,
    file: File,
    schemaType: SchemaType = SchemaType.Avro,
    references: List[SchemaReference] = Nil,
)
