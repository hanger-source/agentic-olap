package com.olap.prototype.contract


/** 原生技术容量；容器保留元素/字段顺序，不携带业务形态或物理字段名。 */
class NativeValueType(
    val name: String,
    val precision: Int?,
    val scale: Int?,
    val nullable: Boolean,
    val elementType: NativeValueType? = null,
    fields: List<NativeValueType>? = null,
) {

    val fields: List<NativeValueType>? = fields?.let { java.util.List.copyOf(it) }
    init {
        ProducerContracts.requireText(name, "native value type")
        if (precision != null && precision < 0 || scale != null && scale < 0) throw ProducerContractException("native type capacity must not be negative")
        if (name == "FINITE_DECIMAL" && (precision != null || scale != null))
            throw ProducerContractException("finite decimal native type cannot declare a common precision or scale")
        if (name == "DECIMAL" && (precision == null || precision <= 0 || scale == null || scale !in 0..precision))
            throw ProducerContractException("decimal native type requires a complete precision and scale")
        val collection = name == "ARRAY" || name == "MULTISET"
        if (collection != (elementType != null) || (name == "ROW") != (fields != null))
            throw ProducerContractException("native containers require their complete element or ordinal field types")
        if ((collection || fields != null) && (precision != null || scale != null))
            throw ProducerContractException("native container cannot declare scalar capacity")
    }
    override fun equals(other: Any?): Boolean = other is NativeValueType && name == other.name && precision == other.precision && scale == other.scale && nullable == other.nullable && elementType == other.elementType && fields == other.fields
    override fun hashCode(): Int = listOf(name, precision, scale, nullable, elementType, fields).hashCode()
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "finiteDecimal" to "EXPLICIT_FINITE_DECIMAL_NAME_WITH_NULL_PRECISION_AND_SCALE_NO_INFERRED_CAPACITY",
            "fixedDecimal" to "DECIMAL_REQUIRES_COMPLETE_POSITIVE_PRECISION_AND_SCALE_WITHIN_PRECISION",
            "arithmetic" to "TECHNICAL_REPRESENTATION_DOES_NOT_ASSIGN_EXACTNESS_OR_AGGREGATE_WORK_GUARANTEES",
        )
    }
}
