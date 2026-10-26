package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.engine.executable.ExecutableBusinessResult.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult.ValueKind.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import java.math.BigInteger

/** 已物化业务值的数量合同，不执行计算阶段。 */
internal object ExecutableQuantityValues {
    fun require(columns: List<Column>, values: List<Value>) {
        columns.forEachIndexed { index, column ->
            val origin = column.symbol.origin
            val value = values[index]
            if (origin is QueryStageAnalysis.GroupingOrigin) check(value is ScalarValue && value.type == INTEGER &&
                origin.definition.levels.any { BigInteger.valueOf(it.code) == BigInteger(value.value) }) { "result row has an undeclared or null grouping level" }
            if (origin is QueryStageAnalysis.OmittedDimensionOrigin) check(value is NullValue) { "omitted subtotal dimension must be null" }
        }
        val context = columns.indices.associateTo(hashMapOf()) { ValuePosition.column(columns[it].symbol.id) to values[it] }
        columns.indices.forEach { collect(columns[it].symbol.value, values[it], context) }
        columns.indices.forEach { require(columns[it].symbol.value, values[it], context) }
    }
    private fun collect(shape: QueryValue, value: Value, context: MutableMap<ValuePosition, Value>) {
        if (value is NullValue) return
        when (shape) {
            is QueryValue.Plain -> shape.positions.forEach { position -> val prior = context.putIfAbsent(position, value); check(prior == null || prior == value) { "one proved value coordinate has contradictory values" } }
            is QueryValue.Row -> {
                check(value is RowValue && value.value.size == shape.fields.size) { "quantity row meaning differs from native result type" }
                shape.fields.indices.forEach { collect(shape.fields[it], value.value[it], context) }
            }
            else -> Unit
        }
    }
    private fun require(shape: QueryValue, value: Value, context: Map<ValuePosition, Value>) {
        if (value is NullValue) return
        when (shape) {
            is QueryValue.Row -> {
                check(value is RowValue && value.value.size == shape.fields.size) { "quantity row meaning differs from native result type" }
                val local = context.toMutableMap(); collect(shape, value, local)
                shape.fields.indices.forEach { require(shape.fields[it], value.value[it], local) }
            }
            is QueryValue.Array -> { check(value is ArrayValue) { "quantity array meaning differs from native result type" }; value.value.forEach { require(shape.element, it, context) } }
            is QueryValue.Measure -> requireQuantity(shape.quantity, value, context)
            else -> check(value !is RowValue && value !is ArrayValue) { "categorical meaning cannot hide a result container" }
        }
    }
    private fun requireQuantity(quantity: QueryQuantity, value: Value, context: Map<ValuePosition, Value>) {
        check(value is ScalarValue && value.type in setOf(INTEGER, DECIMAL, FLOAT)) { "quantity has a nonnumeric result encoding" }
        check(quantity.declaration.arithmetic !is MetricValueContract.Exact || value.type != FLOAT) { "floating result violates exact quantity arithmetic" }
        check(quantity.declaration.result != MetricValueContract.FiniteDecimal || value.type in setOf(INTEGER, DECIMAL)) { "finite decimal quantity requires an exact numeric result encoding" }
        quantity.declaration.requireScalar(Calculation.Scalar(ScalarValueType.NUMBER, value.value))
        val unit = quantity.unit as? QueryQuantity.Dimension ?: return
        val identities = unit.keys.mapNotNull { key ->
            val member = context[key.position] ?: return@mapNotNull null
            check(member !is NullValue) { "nonnull quantity has a null measurement-unit key" }
            check(member is ScalarValue && (if (unit.axis.memberType == ScalarValueType.NUMBER) member.type in setOf(INTEGER, DECIMAL) else member.type == TEXT)) { "measurement-unit member has a different lossless scalar encoding" }
            val literal = Calculation.Scalar(unit.axis.memberType, member.value)
            val matches = unit.axis.members.filter { it.member.sameValue(literal) }
            check(matches.size == 1) { "quantity unit member has no declared identity" }
            matches.single().unit
        }
        check(identities.isNotEmpty() && identities.distinct().size == 1) { "quantity requires one consistent retained measurement unit" }
    }
}
