package com.olap.prototype.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** 编译结果的递归技术类型，不通过 JDBC 类型字符串猜容器结构。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ResultValueType.Scalar::class, name = "SCALAR"),
    JsonSubTypes.Type(value = ResultValueType.Array::class, name = "ARRAY"),
    JsonSubTypes.Type(value = ResultValueType.Row::class, name = "ROW"),
)
sealed interface ResultValueType {
    val nullable: Boolean

    data class Scalar(val name: String, val precision: Int?, val scale: Int?, override val nullable: Boolean) : ResultValueType {
        init {
            require((precision == null || precision >= 0) && (scale == null || scale >= 0)) { "result type capacity must use null when unspecified" }
            require(name != "DECIMAL" || precision != null && scale != null && precision > 0 && scale in 0..precision) { "decimal result requires a complete precision and scale" }
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
