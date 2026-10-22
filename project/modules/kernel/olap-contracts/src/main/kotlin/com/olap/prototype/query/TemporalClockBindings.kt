package com.olap.prototype.query

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat

/** 查询时钟绑定到精确冻结区间位置，不按名称合并、不继承父scope。 */
object TemporalClockBindings {
    data class Value(val valueType: TemporalWindow.ValueType, val value: String) {
        init { require(value.isNotBlank()) { "temporal clock value must be non-null text" }; TemporalWindow(valueType, TemporalWindow.BOUNDARY, value, null) }
        fun jdbcValue(): Any = requireNotNull(TemporalWindow(valueType, TemporalWindow.BOUNDARY, value, null).startValue())
    }
    fun slot(joinPathId: String, joinPathVersion: String, intervalIndex: Int): String {
        require(joinPathId.isNotBlank() && joinPathVersion.matches(Regex("[1-9][0-9]*")) && intervalIndex >= 0) { "temporal clock slot requires exact join identity, version and interval position" }
        return identify(listOf(joinPathId, joinPathVersion, intervalIndex))
    }
    fun slot(role: String, path: List<RelationAccessPath.JoinRef>, intervalIndex: Int): String {
        require(role.isNotBlank() && path.isNotEmpty() && intervalIndex >= 0) { "temporal clock requires an exact relationship role and occurrence path" }
        return identify(listOf(role, path, intervalIndex))
    }
    private fun identify(value: Any): String = "clock_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(value)))
    fun parse(node: JsonNode?): Map<String, Value> {
        if (node == null) return emptyMap()
        require(node.isObject) { "temporalClockBindings must be an object" }
        return Collections.unmodifiableMap(linkedMapOf<String, Value>().apply {
            node.fields().forEachRemaining { (key, value) ->
                require(key.matches(Regex("clock_[0-9a-f]{64}"))) { "invalid temporal clock slot reference" }
                require(value.isObject && value.size() == 2 && value["valueType"]?.isTextual == true && value["value"]?.isTextual == true) { "temporal clock binding requires exactly valueType and non-null value" }
                put(key, Value(TemporalWindow.ValueType.valueOf(value["valueType"].textValue()), value["value"].textValue()))
            }
        })
    }
    fun contract(): Map<String, Any> = mapOf("field" to "temporalClockBindings", "shape" to "OBJECT_KEYED_BY_EXACT_PUBLISHED_SLOT", "valueFields" to listOf("valueType", "value"), "nullAllowed" to false,
        "scope" to "EACH_INPUT_INDEPENDENT_NO_PARENT_INHERITANCE", "selection" to "ALL_AND_ONLY_SLOTS_USED_BY_SELECTED_FROZEN_JOIN_PATHS", "meaning" to "JOIN_INTERVAL_CLOCK_NOT_FACT_TIME_RANGE_OR_EXECUTION_CONTEXT")
}
