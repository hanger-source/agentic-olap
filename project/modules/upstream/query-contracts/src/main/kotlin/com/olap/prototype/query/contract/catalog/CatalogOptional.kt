package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer

/** 字段出现事实：缺失、显式 NULL 与显式空集合分别保留。 */
@JsonSerialize(using = CatalogOptional.Serializer::class)
@JsonDeserialize(using = CatalogOptional.Deserializer::class)
data class CatalogOptional<T>(val present: Boolean, val value: T?) {
    init {
        require(present || value == null) { "absent catalog field cannot have a value" }
    }

    companion object {
        @JvmStatic fun <T> absent(): CatalogOptional<T> = CatalogOptional(false, null)
        @JvmStatic fun <T> of(value: T?): CatalogOptional<T> = CatalogOptional(true, value)
    }

    class Missing {
        override fun equals(other: Any?): Boolean = other is CatalogOptional<*> && !other.present
        override fun hashCode(): Int = 0
    }

    class Serializer : JsonSerializer<CatalogOptional<*>>() {
        override fun serialize(value: CatalogOptional<*>, output: JsonGenerator, provider: SerializerProvider) {
            require(value.present) { "absent catalog field must be omitted" }
            provider.defaultSerializeValue(value.value, output)
        }
    }

    class Deserializer(private val valueType: JavaType? = null) : JsonDeserializer<CatalogOptional<*>>(), ContextualDeserializer {
        override fun createContextual(context: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
            val type = if (property == null) context.contextualType else property.type
            return Deserializer(type.containedTypeOrUnknown(0))
        }

        override fun deserialize(input: JsonParser, context: DeserializationContext): CatalogOptional<*> =
            of(context.readValue<Any?>(input, requireNotNull(valueType)))

        override fun getNullValue(context: DeserializationContext): CatalogOptional<*> = of<Any?>(null)
        override fun getAbsentValue(context: DeserializationContext): CatalogOptional<*> = absent<Any?>()
    }
}
