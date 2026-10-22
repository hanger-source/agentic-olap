package com.olap.prototype.time

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** 查询窗的唯一值与边界解释，不依赖JVM默认时区。 */
data class TemporalWindow(val valueType: ValueType, val boundary: String, val start: String?, val end: String?) {
    enum class ValueType { DATE, LOCAL_TIMESTAMP, OFFSET_TIMESTAMP }
    init {
        require(boundary == BOUNDARY) { "timeRange.boundary must be $BOUNDARY" }
        start?.let { parse(valueType, it) }; end?.let { parse(valueType, it) }
        if (start != null && end != null) {
            val ordered = when (valueType) {
                ValueType.DATE -> LocalDate.parse(start).isBefore(LocalDate.parse(end))
                ValueType.LOCAL_TIMESTAMP -> LocalDateTime.parse(start).isBefore(LocalDateTime.parse(end))
                ValueType.OFFSET_TIMESTAMP -> OffsetDateTime.parse(start).toInstant().isBefore(OffsetDateTime.parse(end).toInstant())
            }
            require(ordered) { "timeRange.start must precede exclusive end" }
        }
    }
    fun startValue(): Any? = start?.let { parse(valueType, it) }
    fun endValue(): Any? = end?.let { parse(valueType, it) }
    companion object {
        const val BOUNDARY = "LEFT_CLOSED_RIGHT_OPEN"
        private fun parse(type: ValueType, value: String): Any = when (type) { ValueType.DATE -> LocalDate.parse(value); ValueType.LOCAL_TIMESTAMP -> LocalDateTime.parse(value); ValueType.OFFSET_TIMESTAMP -> OffsetDateTime.parse(value) }
        fun fromJson(range: JsonNode?): TemporalWindow {
            require(range != null && range.isObject) { "timeRange must be an object" }
            return TemporalWindow(ValueType.valueOf(requireNotNull(text(range, "valueType", false))), requireNotNull(text(range, "boundary", false)), text(range, "start", true), text(range, "end", true))
        }
        private fun text(range: JsonNode, field: String, nullable: Boolean): String? {
            val value = requireNotNull(range[field]) { "timeRange.$field must be explicit" }
            if (nullable && value.isNull) return null
            require(value.isTextual && value.textValue().isNotBlank()) { "timeRange.$field must be ${if (nullable) "text or explicit null" else "text"}" }
            return value.textValue()
        }
        fun contract(): Map<String, Any> = mapOf("boundary" to BOUNDARY, "requiredFields" to listOf("valueType", "boundary", "start", "end"),
            "explicitNullRangeMeaning" to "NO_TIME_INTERPRETATION_OR_TIME_RANGE_PREDICATE_OTHER_INPUT_FILTERS_AND_CLOCKS_STILL_APPLY", "nullableFields" to listOf("start", "end"),
            "nullBoundaryMeaning" to "UNBOUNDED_AT_THIS_END_NO_INVENTED_TIMESTAMP", "fullyUnboundedMeaning" to "NO_TIME_RANGE_PREDICATE_INCLUDES_UNKNOWN_ANCHOR_VALUES_EVENT_FILTERS_STILL_APPLY",
            "valueTypes" to listOf(mapOf("valueType" to "DATE", "interpretation" to "RECORDED_CALENDAR_DATE", "format" to "ISO_LOCAL_DATE"),
                mapOf("valueType" to "LOCAL_TIMESTAMP", "interpretation" to "RECORDED_WALL_CLOCK_NO_TIMEZONE_CONVERSION", "format" to "ISO_LOCAL_DATE_TIME"),
                mapOf("valueType" to "OFFSET_TIMESTAMP", "interpretation" to "EXPLICIT_OFFSET_INSTANT", "format" to "ISO_OFFSET_DATE_TIME")))
    }
}
