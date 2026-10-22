package com.olap.prototype.calculation

import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScopedInputContract

/** 指标版本的执行定义。事件归事实度量，组合归精确计算调用与阶段。 */
sealed interface MetricDefinition {
    val valueContract: MetricValueContract
    class FactMeasure(val event: EventRef, val input: SemanticExpression, val aggregation: MetricAggregation, val distinct: Boolean,
        val privatePredicate: SemanticExpression?, roles: Map<String, RelationAccessPath>, val snapshotRule: MetricSnapshotRule?,
        override val valueContract: MetricValueContract, val aggregationRounding: MetricValueContract.Rounding) : MetricDefinition {
        val roles: Map<String, RelationAccessPath> = java.util.Map.copyOf(roles)
        init {
            MetricAggregation.requireValueContract(aggregation.name, valueContract)
            require(valueContract.unit !is MetricValueContract.SlotDimension && valueContract.unit !is MetricValueContract.AnalysisDimension) { "fact value units must bind an exact event dimension" }
            require(aggregation == MetricAggregation.AVG || aggregationRounding == MetricValueContract.Rounding.NONE) { "only AVG may declare aggregation rounding" }
            require(aggregation != MetricAggregation.COUNT || (valueContract.unit as? MetricValueContract.Fixed)?.identity == MetricValueContract.ONE) { "COUNT is a dimensionless record quantity" }
            if (snapshotRule?.reduction == MetricSnapshotRule.Reduction.MEAN) {
                val mean = requireNotNull(snapshotRule.meanValueContract)
                require(mean.unit == valueContract.unit) { "snapshot mean and final aggregation must retain the same measurement unit" }
                MetricValueContract.requireArithmeticInput(valueContract.arithmetic, mean.arithmetic)
            }
            this.roles.keys.forEach { ScopedInputContract.requireId(it) }
            require(!distinct || aggregation == MetricAggregation.COUNT) { "distinct fact measures require COUNT" }
        }
        override fun equals(other: Any?): Boolean = other is FactMeasure && event == other.event && input == other.input && aggregation == other.aggregation && distinct == other.distinct && privatePredicate == other.privatePredicate && roles == other.roles && snapshotRule == other.snapshotRule && valueContract == other.valueContract && aggregationRounding == other.aggregationRounding
        override fun hashCode(): Int = listOf(event, input, aggregation, distinct, privatePredicate, roles, snapshotRule, valueContract, aggregationRounding).hashCode()
    }
    companion object {
        fun text(value: String?, field: String): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
        fun version(value: Long): Long = value.also { require(it > 0) { "exact positive version is required" } }
    }
}
