package com.olap.prototype.expression

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

class SemanticExpressionJsonSerializer : JsonSerializer<SemanticExpression>() {
    override fun serialize(value: SemanticExpression, generator: JsonGenerator, serializers: SerializerProvider) = generator.writeTree(SemanticExpressionCodec.toJsonNode(value))
}
class SemanticExpressionJsonDeserializer : JsonDeserializer<SemanticExpression>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): SemanticExpression = SemanticExpressionCodec.parse(parser.codec.readTree(parser), "semanticExpression")
}
