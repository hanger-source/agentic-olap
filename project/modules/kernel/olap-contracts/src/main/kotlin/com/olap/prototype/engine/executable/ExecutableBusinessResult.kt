package com.olap.prototype.engine.executable

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.query.QueryStageAnalysis
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*

/** 精确语义身份和无损值，不包含SQL、物理列或执行绑定。 */
data class ExecutableBusinessResult(val inputColumns: List<Column>, val scopeInputColumns: Map<String, List<Column>>,
    val columns: List<Column>, val sourceSymbols: List<QueryStageAnalysis.Symbol>, val rows: List<List<Value>>) {
    data class Column(val columnId: String, val symbol: QueryStageAnalysis.Symbol, val type: ResultValueType, val displayName: String?)
    enum class ValueKind { NULL, INTEGER, DECIMAL, FLOAT, BOOLEAN, TEXT, DATE, LOCAL_TIME, OFFSET_TIME, LOCAL_TIMESTAMP, INSTANT, OFFSET_TIMESTAMP, ARRAY, ROW }
    sealed interface Value { val type: ValueKind }
    class NullValue : Value {
        override val type = ValueKind.NULL
        val value: String? = null
        override fun equals(other: Any?): Boolean = other is NullValue
        override fun hashCode(): Int = 0
    }
    data class ScalarValue(override val type: ValueKind, val value: String) : Value {
        init { when (type) {
            ValueKind.INTEGER -> { require(value.matches(Regex("-?[0-9]+"))) { "invalid lossless integer" }; BigInteger(value) }
            ValueKind.DECIMAL -> { require(value.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))) { "invalid plain decimal" }; BigDecimal(value) }
            ValueKind.FLOAT -> require(value.toDouble().isFinite()) { "non-finite result scalar" }
            ValueKind.BOOLEAN -> require(value == "true" || value == "false") { "invalid result boolean" }
            ValueKind.TEXT -> Unit
            ValueKind.DATE -> LocalDate.parse(value)
            ValueKind.LOCAL_TIME -> LocalTime.parse(value)
            ValueKind.OFFSET_TIME -> OffsetTime.parse(value)
            ValueKind.LOCAL_TIMESTAMP -> LocalDateTime.parse(value)
            ValueKind.INSTANT -> Instant.parse(value)
            ValueKind.OFFSET_TIMESTAMP -> OffsetDateTime.parse(value)
            ValueKind.NULL, ValueKind.ARRAY, ValueKind.ROW -> throw IllegalArgumentException("container and null values are not scalars")
        } }
    }
    data class ArrayValue(val value: List<Value>) : Value { override val type = ValueKind.ARRAY }
    /** ROW只按编译合同序号保存，不由字段名或Map迭代顺序恢复。 */
    data class RowValue(val value: List<Value>) : Value { override val type = ValueKind.ROW }
}
