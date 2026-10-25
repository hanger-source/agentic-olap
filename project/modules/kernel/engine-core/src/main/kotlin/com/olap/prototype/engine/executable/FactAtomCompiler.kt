package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.engine.catalog.ExecutableDimensionBinding
import com.olap.prototype.expression.RelationalAggregates
import com.olap.prototype.expression.SemanticCalciteTypeSystem
import com.olap.prototype.expression.SemanticNumericRex
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.sql.type.SqlTypeUtil

/** 原子输入、私有参与条件、原生聚合和数量输出，不决定调用人口。 */
internal object FactAtomCompiler {
    data class Atom(val value: RexNode, val predicate: RexNode?, val inputGuarantees: List<MetricValueContract.Arithmetic>)
    data class Column(val input: ExecutableMetricInput, val value: String, val predicate: String?, val aggregate: String,
        val inputGuarantees: List<MetricValueContract.Arithmetic>) {
        fun measure(): MetricDefinition.FactMeasure = input.metric.factMeasure()
    }
    data class Projection(val dimensions: List<String>, val columns: List<Column>) {
        fun names(): List<String> = dimensions + columns.flatMap { listOfNotNull(it.value, it.predicate) }
        companion object {
            fun create(dimensions: List<String>, inputs: List<ExecutableMetricInput>, atoms: List<Atom>): Projection {
                require(inputs.size == atoms.size) { "fact columns require their actual bound input guarantees" }
                var position = dimensions.size
                return Projection(dimensions.toList(), inputs.mapIndexed { index, input ->
                    val value = "atom_input_${position++}"
                    val predicate = if (input.metric.factMeasure().privatePredicate == null) null else "atom_predicate_${position++}"
                    Column(input, value, predicate, "aggregate_$index", atoms[index].inputGuarantees)
                })
            }
        }
    }
    fun input(builder: RelBuilder, rows: SourceRelationCompiler.Rows, input: ExecutableMetricInput, population: RexNode?): Atom {
        val measure = input.metric.factMeasure()
        val position = requireNotNull(rows.source(input).metricExpression)
        var predicate = measure.privatePredicate?.let { rows.compile(it, position) }
        if (population != null) predicate = predicate?.let { builder.and(population, it) } ?: population
        val raw = rows.compile(measure.input, position)
        val guarantees = if (SqlTypeUtil.isNumeric(raw.type)) SemanticNumericRex.inputGuarantees(raw) else emptyList()
        var value = if (measure.aggregation == MetricAggregation.AVG ||
            measure.snapshotRule?.reduction == MetricSnapshotRule.Reduction.MEAN) SemanticNumericRex.averageInput(builder, raw)
            else SemanticNumericRex.factInput(builder, raw, measure.aggregation, measure.valueContract)
        if (value !== raw && predicate != null) value = builder.call(Ops.CASE, predicate, value, builder.rexBuilder.makeNullLiteral(value.type))
        return Atom(value, predicate, guarantees)
    }
    fun aggregate(builder: RelBuilder, measure: MetricDefinition.FactMeasure, input: RexNode, predicate: RexNode?, alias: String,
        inputGuarantees: List<MetricValueContract.Arithmetic>): RelBuilder.AggCall {
        if (measure.aggregation != MetricAggregation.COUNT && measure.aggregation != MetricAggregation.AVG)
            inputGuarantees.forEach { MetricValueContract.requireArithmeticInput(measure.valueContract.arithmetic, it) }
        val value = SemanticNumericRex.factAggregate(builder, measure.aggregation, input, measure.valueContract)
        val support = (builder.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport
        return builder.aggregateCall(RelationalAggregates.factAggregateFunction(measure.aggregation, support), measure.distinct, predicate, alias, value)
    }
    fun requireUnitCode(value: RexNode, binding: ExecutableDimensionBinding, inputs: List<ExecutableMetricInput>) {
        inputs.forEach { input ->
            val unit = input.metric.factMeasure().valueContract.unit as? MetricValueContract.EventDimension
            if (unit != null && unit.bindingId == binding.bindingId && unit.bindingVersion.toString() == binding.bindingVersion && unit.axis.memberType == ScalarValueType.NUMBER)
                SemanticNumericRex.requireExactMemberCode(value)
        }
    }
}
