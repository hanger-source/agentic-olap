package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.warehouse.WarehouseExecutionControl
import org.apache.calcite.sql.type.SqlTypeUtil
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

/** 正式 REGROUP 的核心执行；工作和与独立非 NULL 人口不受最终输出容量约束。 */
internal class CoreRegroup private constructor(
    private val grouping: PostOperationContract.ResultGrouping,
    private val inputTypes: Map<String, CoreScalarType>,
    outputTypes: Map<String, CoreScalarType>,
    private val aggregates: List<Aggregate>,
) {
    val outputTypes: Map<String, CoreScalarType> = Collections.unmodifiableMap(LinkedHashMap(outputTypes))

    fun execute(rows: List<Map<String, CoreValue>>, control: WarehouseExecutionControl): List<Map<String, CoreValue>> {
        val groups = linkedMapOf<List<CoreValueKeys.Key>, Group>()
        if (grouping.groupBy.isEmpty()) groups[emptyList()] = Group(emptyMap())
        rows.forEach { row ->
            control.checkCancellation()
            val identity = grouping.groupBy.map { name -> CoreValueKeys.key(row.getValue(name), inputTypes.getValue(name)) }
            val group = groups.getOrPut(identity) { Group(grouping.groupBy.associateTo(LinkedHashMap()) { it to row.getValue(it) }) }
            group.states.forEach { state -> state.accept(row.getValue(state.aggregate.input)) }
        }
        return groups.values.map { group ->
            control.checkCancellation()
            LinkedHashMap(group.keys).also { output ->
                group.states.forEach { state ->
                    val aggregate = state.aggregate
                    val value = state.value()
                    output[aggregate.alias] = aggregate.quantize?.evaluate(mapOf(aggregate.alias to value), control) ?: value
                }
            }
        }
    }

    private inner class Group(val keys: Map<String, CoreValue>) {
        val states = aggregates.map(::State)
    }

    private class Aggregate(val input: String, val alias: String, val aggregation: ResultAggregation,
        val inputType: CoreScalarType, val quantize: CoreExpression?)

    private class State(val aggregate: Aggregate) {
        private var sawNull = false
        private var count = BigInteger.ZERO
        private var sum = zero
        private var extremum: CoreExactNumber? = null
        private val distinct = if ((aggregate.aggregation as? ResultAggregation.Count)?.distinct == true) hashSetOf<CoreValueKeys.Key>() else null

        fun accept(value: CoreValue) {
            if (value == CoreValue.Missing) { sawNull = true; return }
            when (val aggregation = aggregate.aggregation) {
                is ResultAggregation.Count -> {
                    if (distinct == null || distinct.add(CoreValueKeys.key(value, aggregate.inputType))) count += BigInteger.ONE
                }
                is ResultAggregation.Sum, is ResultAggregation.Average -> {
                    sum += (value as CoreValue.Numeric).value
                    count += BigInteger.ONE
                }
                is ResultAggregation.Minimum, is ResultAggregation.Maximum -> {
                    val number = (value as CoreValue.Numeric).value
                    val prior = extremum
                    if (prior == null || if (aggregation is ResultAggregation.Minimum) number < prior else number > prior) extremum = number
                }
                is ResultAggregation.SampleDeviation, is ResultAggregation.Collect -> error("core regroup aggregation was not admitted")
            }
        }

        fun value(): CoreValue {
            val aggregation = aggregate.aggregation as ResultAggregation.Scalar
            if (sawNull && aggregation.nullPolicy == ResultAggregation.NullPolicy.PROPAGATE) return CoreValue.Missing
            return when (aggregation) {
                is ResultAggregation.Count -> CoreValue.Numeric(CoreExactNumber.decimal(BigDecimal(count)))
                is ResultAggregation.Sum -> if (count.signum() == 0) CoreValue.Missing else CoreValue.Numeric(sum)
                is ResultAggregation.Average -> if (count.signum() == 0) CoreValue.Missing else
                    CoreValue.Numeric(sum / CoreExactNumber.decimal(BigDecimal(count)))
                is ResultAggregation.Minimum, is ResultAggregation.Maximum -> extremum?.let { CoreValue.Numeric(it) } ?: CoreValue.Missing
                is ResultAggregation.SampleDeviation -> error("core regroup aggregation was not admitted")
            }
        }
    }

    companion object {
        private val zero = CoreExactNumber.decimal(BigDecimal.ZERO)

        fun compile(grouping: PostOperationContract.ResultGrouping, stage: QueryStageAnalysis.Stage,
            inputTypes: Map<String, CoreScalarType>, path: List<Any>): CoreRegroup {
            if (grouping.groupingLevels != null) CoreFinalizationExpressions.unsupported(path + listOf("grouping", "groupingLevels"),
                "core regroup does not support grouping levels")
            val outputs = linkedMapOf<String, CoreScalarType>()
            grouping.groupBy.forEachIndexed { index, name ->
                val type = inputTypes.getValue(name)
                CoreValueKeys.requireSupported(type, path + listOf("grouping", "groupBy", index))
                outputs[name] = type
            }
            val aggregates = grouping.aggregates.mapIndexed { index, aggregate ->
                val at = path + listOf("grouping", "aggregates", index)
                val input = inputTypes.getValue(aggregate.input)
                val aggregation = aggregate.aggregation
                when (aggregation) {
                    is ResultAggregation.Average -> {
                        if (aggregation.meanTarget != MeanTarget.MATHEMATICAL) CoreFinalizationExpressions.unsupported(at + "aggregation",
                            "core AVG requires the MATHEMATICAL mean target")
                        requireExactInput(stage, aggregate.input, input, at)
                    }
                    is ResultAggregation.Sum, is ResultAggregation.Minimum, is ResultAggregation.Maximum -> requireExactInput(stage, aggregate.input, input, at)
                    is ResultAggregation.Count -> if (aggregation.distinct) CoreValueKeys.requireSupported(input, at + "input")
                    is ResultAggregation.SampleDeviation, is ResultAggregation.Collect -> CoreFinalizationExpressions.unsupported(at + "aggregation",
                        "aggregation is not supported by core regroup: ${aggregation.kind}")
                }
                val nullPolicy = (aggregation as ResultAggregation.Scalar).nullPolicy
                val nullable = if (aggregation is ResultAggregation.Count) nullPolicy == ResultAggregation.NullPolicy.PROPAGATE && input.nullable
                    else input.nullable || grouping.groupBy.isEmpty()
                val contract = when (aggregation) {
                    is ResultAggregation.Sum -> aggregation.valueContract
                    is ResultAggregation.Average -> aggregation.valueContract
                    is ResultAggregation.Count -> requireNotNull(stage.outputs.first { it.name == aggregate.outputAlias }.quantity).declaration
                    else -> null
                }
                val quantize = contract?.let {
                    val working = CoreScalarType(CoreScalarType.Kind.NUMBER, nullable, null)
                    CoreFinalizationExpressions(mapOf(aggregate.outputAlias to working)).compile(
                        ResultExpression.ResultNumeric(ResultExpression.ResultSymbol(aggregate.outputAlias), it, null), at + "aggregation")
                }
                outputs[aggregate.outputAlias] = quantize?.type ?: input.copy(nullable = nullable,
                    storage = input.storage?.let { CoreFinalizationExpressions.projectionType(listOf(input), nullable, at).storage })
                Aggregate(aggregate.input, aggregate.outputAlias, aggregation, input, quantize)
            }
            check(stage.outputs.map { it.name } == outputs.keys.toList()) { "core regroup output columns differ from semantic analysis" }
            return CoreRegroup(grouping, inputTypes.toMap(), outputs, aggregates)
        }

        private fun requireExactInput(stage: QueryStageAnalysis.Stage, name: String, type: CoreScalarType, path: List<Any>) {
            val declaration = stage.inputs.first { it.name == name }.quantity?.declaration
            if (type.kind !in setOf(CoreScalarType.Kind.NUMBER, CoreScalarType.Kind.NULL) ||
                declaration == null || declaration.arithmetic !is MetricValueContract.Exact || declaration.result is MetricValueContract.BinaryFloat ||
                type.storage?.let { it.sqlTypeName != org.apache.calcite.sql.type.SqlTypeName.NULL && !SqlTypeUtil.isExactNumeric(it) } == true)
                CoreFinalizationExpressions.unsupported(path + "input", "core numeric regroup requires an Exact integer or decimal input")
        }
    }
}
