package com.olap.prototype.query

import java.math.BigDecimal

/** 已有结果行的窗口范围，不推断缺失日期或业务完整性。 */
object WindowFrameContract {
    fun contract(): Map<String, Any> = mapOf("fields" to listOf("mode", "lower", "upper"), "modes" to listOf("ROWS", "RANGE"),
        "bounds" to mapOf("UNBOUNDED_PRECEDING" to listOf("kind"), "CURRENT_ROW" to listOf("kind"), "UNBOUNDED_FOLLOWING" to listOf("kind"), "PRECEDING" to listOf("kind", "offset", "unit"), "FOLLOWING" to listOf("kind", "offset", "unit")),
        "offset" to "POSITIVE_INTEGER_UP_TO_2147483647", "units" to mapOf("ROWS" to listOf("ROW"), "RANGE" to listOf("NUMBER", "DAY", "HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND")),
        "ordering" to "ROWS_REQUIRES_EXPLICIT_TOTAL_ORDER_RANGE_REQUIRES_ONE_NUMERIC_OR_TEMPORAL_KEY", "boundaries" to "INCLUSIVE_SQL_WINDOW_BOUNDS_RANGE_CURRENT_ROW_INCLUDES_PEERS",
        "coverage" to "EXISTING_ROWS_ONLY_NO_CALENDAR_COMPLETION_OR_SOURCE_COMPLETENESS_PROOF")
    fun require(raw: Any?, field: String): Frame {
        require(raw is Map<*, *> && raw.keys == setOf("mode", "lower", "upper")) { "$field requires exactly mode, lower and upper" }
        val mode = raw["mode"] as? String ?: throw IllegalArgumentException("$field.mode is required")
        return Frame(mode, bound(raw["lower"], "$field.lower"), bound(raw["upper"], "$field.upper"))
    }
    private fun bound(raw: Any?, field: String): Bound {
        require(raw is Map<*, *>) { "$field.kind is required" }
        val kind = raw["kind"] as? String ?: throw IllegalArgumentException("$field.kind is required")
        if (kind in setOf("UNBOUNDED_PRECEDING", "CURRENT_ROW", "UNBOUNDED_FOLLOWING")) {
            require(raw.keys == setOf("kind")) { "$field has unknown fields" }
            return Bound(kind, null, null)
        }
        require(raw.keys == setOf("kind", "offset", "unit")) { "$field requires exactly kind, offset and unit" }
        return Bound(kind, positiveInteger(raw["offset"], "$field.offset"), raw["unit"] as? String ?: throw IllegalArgumentException("$field.unit is required"))
    }
    fun positiveInteger(raw: Any?, field: String): Int {
        require(raw is Number) { "$field must be a positive integer" }
        try { BigDecimal(raw.toString()).intValueExact().let { if (it > 0) return it } } catch (_: ArithmeticException) {} catch (_: NumberFormatException) {}
        throw IllegalArgumentException("$field must be a positive integer <= 2147483647")
    }
    fun normalizedOffset(bound: Bound): BigDecimal {
        val factor = when (bound.unit) { "DAY" -> BigDecimal(86_400_000); "HOUR" -> BigDecimal(3_600_000); "MINUTE" -> BigDecimal(60_000); "SECOND" -> BigDecimal(1_000); "MICROSECOND" -> BigDecimal("0.001"); "MILLISECOND", "NUMBER", "ROW" -> BigDecimal.ONE; else -> throw IllegalArgumentException("unsupported window offset unit") }
        return factor.multiply(BigDecimal(requireNotNull(bound.offset)))
    }
    private fun position(bound: Bound): BigDecimal = if (bound.offset == null) BigDecimal.ZERO else normalizedOffset(bound).let { if (bound.kind == "PRECEDING") it.negate() else it }
    data class Frame(val mode: String, val lower: Bound, val upper: Bound) {
        init {
            require(mode in setOf("ROWS", "RANGE")) { "window mode must be ROWS or RANGE" }
            val units = if (mode == "ROWS") setOf("ROW") else setOf("NUMBER", "DAY", "HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND")
            listOf(lower, upper).forEach { require(it.offset == null || it.unit in units) { "window offset unit is not valid for $mode" } }
            require(lower.kind != "UNBOUNDED_FOLLOWING" && upper.kind != "UNBOUNDED_PRECEDING") { "window has an invalid unbounded endpoint" }
            require(lower.offset == null || upper.offset == null || (lower.unit == "NUMBER") == (upper.unit == "NUMBER")) { "window cannot mix numeric and temporal offsets" }
            require(lower.kind == "UNBOUNDED_PRECEDING" || upper.kind == "UNBOUNDED_FOLLOWING" || position(lower) <= position(upper)) { "window lower bound must not follow upper bound" }
        }
        fun requireOrderKeys(count: Int) { require(count >= 1 && (mode != "RANGE" || count == 1)) { "RANGE requires exactly one order key; ROWS requires an explicit order" } }
    }
    data class Bound(val kind: String, val offset: Int?, val unit: String?) {
        init {
            if (kind in setOf("UNBOUNDED_PRECEDING", "CURRENT_ROW", "UNBOUNDED_FOLLOWING")) require(offset == null && unit == null) { "unbounded and current row bounds have no offset or unit" }
            else require(kind in setOf("PRECEDING", "FOLLOWING") && offset != null && offset > 0 && unit != null) { "window bound requires a supported kind and positive offset with an explicit unit" }
        }
    }
}
