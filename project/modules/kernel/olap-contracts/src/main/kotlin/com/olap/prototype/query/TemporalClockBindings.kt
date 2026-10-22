package com.olap.prototype.query

import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.time.TemporalWindow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** 查询时钟绑定到精确冻结区间位置，不按名称合并、不继承父scope。 */
object TemporalClockBindings {
    data class Value(val valueType: TemporalWindow.ValueType, val value: String) {
        init { require(value.isNotBlank()) { "temporal clock value must be non-null text" }; TemporalWindow(valueType, TemporalWindow.BOUNDARY, value, null) }
        fun jdbcValue(): Any = requireNotNull(TemporalWindow(valueType, TemporalWindow.BOUNDARY, value, null).startValue())
    }
    fun slot(joinPathId: String, joinPathVersion: String, intervalIndex: Int): String {
        require(joinPathId.isNotBlank() && joinPathVersion.matches(Regex("[1-9][0-9]*")) && intervalIndex >= 0) { "temporal clock slot requires exact join identity, version and interval position" }
        return identify(listOf("EXACT_JOIN", joinPathId, joinPathVersion, intervalIndex.toString()))
    }
    fun slot(role: String, path: List<RelationAccessPath.JoinRef>, intervalIndex: Int): String {
        require(role.isNotBlank() && path.isNotEmpty() && intervalIndex >= 0) { "temporal clock requires an exact relationship role and occurrence path" }
        return identify(listOf("ROLE_PATH", role, path.size.toString()) + path.flatMap { listOf(it.joinPathId, it.joinPathVersion.toString()) } + intervalIndex.toString())
    }
    private fun identify(parts: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(parts.size)
            parts.forEach { part ->
                val value = part.toByteArray(Charsets.UTF_8)
                output.writeInt(value.size)
                output.write(value)
            }
        }
        return "clock_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
    fun contract(): Map<String, Any> = mapOf("field" to "temporalClockBindings", "shape" to "OBJECT_KEYED_BY_EXACT_PUBLISHED_SLOT", "valueFields" to listOf("valueType", "value"), "nullAllowed" to false,
        "scope" to "EACH_INPUT_INDEPENDENT_NO_PARENT_INHERITANCE", "selection" to "ALL_AND_ONLY_SLOTS_USED_BY_SELECTED_FROZEN_JOIN_PATHS", "meaning" to "JOIN_INTERVAL_CLOCK_NOT_FACT_TIME_RANGE_OR_EXECUTION_CONTEXT")
}
