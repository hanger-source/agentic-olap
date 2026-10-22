package com.olap.prototype.query

import java.math.BigDecimal
import java.time.*

/** 发布标量合同；不从名字、维度角色或值文本猜类型。 */
enum class ScalarValueType {
    TEXT, NUMBER, DATE, LOCAL_TIMESTAMP, OFFSET_TIMESTAMP, BOOLEAN, ENUM;
    fun parameter(value: Any?): Any = try {
        requireNotNull(value) { "scalar value must not be null" }
        when (this) {
            TEXT, ENUM -> value as? String ?: throw invalid()
            NUMBER -> {
                if (value !is Number || value is Float || value is Double) throw invalid()
                BigDecimal(value.toString()).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
            }
            BOOLEAN -> value as? Boolean ?: throw invalid()
            DATE -> text(value).also { if (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) throw invalid() }.let(LocalDate::parse)
            LOCAL_TIMESTAMP -> timestampText(value).let(LocalDateTime::parse)
            OFFSET_TIMESTAMP -> timestampText(value).let(OffsetDateTime::parse)
        }
    } catch (failure: DateTimeException) { throw IllegalArgumentException("value does not match published scalar type $name", failure) }

    fun candidate(value: Any?): Any {
        requireNotNull(value) { "candidate value must not be null" }
        return when (this) {
        NUMBER -> if (value is Number) BigDecimal(value.toString()).toPlainString() else throw invalid()
        DATE -> (value as? LocalDate)?.toString() ?: throw invalid()
        LOCAL_TIMESTAMP -> (value as? LocalDateTime)?.toString() ?: throw invalid()
        OFFSET_TIMESTAMP -> (value as? OffsetDateTime)?.toString() ?: throw invalid()
        ENUM -> value.toString()
        else -> parameter(value)
        }
    }
    private fun text(value: Any): String = value as? String ?: throw invalid()
    private fun timestampText(value: Any): String = text(value).also { if (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}.*"))) throw invalid() }
    private fun invalid(): IllegalArgumentException = IllegalArgumentException("value does not match published scalar type $name")

    companion object {
        fun fromLogicType(type: String): ScalarValueType = when (type) {
            "STRING" -> TEXT
            "ID", "ENUM" -> ENUM
            "NUMBER" -> NUMBER
            "DATE" -> DATE
            "LOCAL_TIMESTAMP" -> LOCAL_TIMESTAMP
            "OFFSET_TIMESTAMP" -> OFFSET_TIMESTAMP
            "BOOLEAN" -> BOOLEAN
            else -> throw IllegalArgumentException("unsupported exact logicType: $type")
        }
        fun contract(): Map<String, Any> = mapOf("types" to listOf(
            mapOf("valueType" to "DATE", "jsonType" to "STRING", "format" to "ISO_LOCAL_DATE", "interpretation" to "RECORDED_CALENDAR_DATE"),
            mapOf("valueType" to "LOCAL_TIMESTAMP", "jsonType" to "STRING", "format" to "ISO_LOCAL_DATE_TIME", "interpretation" to "RECORDED_WALL_CLOCK_NO_TIMEZONE_CONVERSION", "maxFractionDigits" to 9),
            mapOf("valueType" to "OFFSET_TIMESTAMP", "jsonType" to "STRING", "format" to "ISO_OFFSET_DATE_TIME", "interpretation" to "EXPLICIT_OFFSET_REQUIRED", "maxFractionDigits" to 9),
            mapOf("valueType" to "NUMBER", "jsonType" to "STRING", "encoding" to "EXACT_DECIMAL_TEXT"),
            mapOf("valueType" to "BOOLEAN", "jsonType" to "BOOLEAN"), mapOf("valueType" to "TEXT", "jsonType" to "STRING"), mapOf("valueType" to "ENUM", "jsonType" to "STRING")),
            "nullValues" to "FORBIDDEN_USE_IS_NULL_OR_IS_NOT_NULL", "comparison" to "USE_EXACT_PUBLISHED_OPERATOR_NO_TIME_TRUNCATION")
    }
}
