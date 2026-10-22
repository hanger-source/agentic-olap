package com.olap.prototype.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** 原生技术容量；容器保留元素/字段顺序，不携带业务形态或物理字段名。 */
class NativeValueType(
    val name: String,
    val precision: Int?,
    val scale: Int?,
    @param:JsonProperty(value = "nullable", required = true) val nullable: Boolean,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val elementType: NativeValueType? = null,
    fields: List<NativeValueType>? = null,
) {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val fields: List<NativeValueType>? = fields?.let { java.util.List.copyOf(it) }
    init {
        ProducerContracts.requireText(name, "native value type")
        if (precision != null && precision < 0 || scale != null && scale < 0) throw ProducerContractException("native type capacity must not be negative")
        val collection = name == "ARRAY" || name == "MULTISET"
        if (collection != (elementType != null) || (name == "ROW") != (fields != null))
            throw ProducerContractException("native containers require their complete element or ordinal field types")
        if ((collection || fields != null) && (precision != null || scale != null))
            throw ProducerContractException("native container cannot declare scalar capacity")
    }
    override fun equals(other: Any?): Boolean = other is NativeValueType && name == other.name && precision == other.precision && scale == other.scale && nullable == other.nullable && elementType == other.elementType && fields == other.fields
    override fun hashCode(): Int = listOf(name, precision, scale, nullable, elementType, fields).hashCode()
}
