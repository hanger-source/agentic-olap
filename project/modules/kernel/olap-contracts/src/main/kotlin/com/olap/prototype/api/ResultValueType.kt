package com.olap.prototype.api


/** 编译结果的递归技术类型，不通过 JDBC 类型字符串猜容器结构。 */
sealed interface ResultValueType {
    val nullable: Boolean

    data class Scalar(val name: String, val precision: Int?, val scale: Int?, override val nullable: Boolean) : ResultValueType {
        init {
            require((precision == null || precision >= 0) && (scale == null || scale >= 0)) { "result type capacity must use null when unspecified" }
            require(name != "DECIMAL" || precision != null && scale != null && precision > 0 && scale in 0..precision) { "decimal result requires a complete precision and scale" }
            require(name != "FINITE_DECIMAL" || precision == null && scale == null) { "finite decimal result cannot declare a common precision or scale" }
        }
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "finiteDecimal" to "EXPLICIT_FINITE_DECIMAL_NAME_WITH_NULL_PRECISION_AND_SCALE_VALUES_USE_LOSSLESS_DECIMAL_ENCODING",
                "fixedDecimal" to "DECIMAL_REQUIRES_COMPLETE_PRECISION_AND_SCALE",
                "guarantee" to "TECHNICAL_REPRESENTATION_DOES_NOT_ASSIGN_EXACT_ARITHMETIC_OR_IMPLICIT_QUANTIZATION",
            )
        }
    }
    data class Array(val element: ResultValueType, override val nullable: Boolean) : ResultValueType
    class Row(fields: List<Field>, override val nullable: Boolean) : ResultValueType {
        val fields: List<Field> = java.util.List.copyOf(fields)
        override fun equals(other: Any?): Boolean = other is Row && fields == other.fields && nullable == other.nullable
        override fun hashCode(): Int = listOf(fields, nullable).hashCode()
    }
    data class Field(val name: String, val type: ResultValueType)
}
