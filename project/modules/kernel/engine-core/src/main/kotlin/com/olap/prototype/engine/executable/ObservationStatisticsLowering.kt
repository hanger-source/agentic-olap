package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.ResultExpression.*

/** 观察统计只降为共同阶段，不另解释数量或聚合规则。 */
internal class ObservationStatisticsLowering private constructor(
    private val statistics: ExecutableInputRoot.ObservationStatistics, actualNames: List<String>,
) {
    private val names = (actualNames + statistics.statistics.map { it.outputAlias }).toMutableSet()
    private val preparation = mutableListOf<ValidatedOperation>()
    private val aggregates = mutableListOf<ResultAggregate>()
    private val finish = mutableListOf<ValidatedOperation>()

    data class Lowered(val operations: List<ValidatedOperation>, val reductionIndex: Int)

    private fun lower(): Lowered {
        statistics.statistics.forEach { when (it) {
            is Calculation.Reduction -> aggregates += it.aggregate
            is Calculation.WeightedMean -> weighted(it)
        } }
        val operations = preparation + Regroup(ResultGrouping(statistics.outerKeys, aggregates.toList(), null)) + finish +
            Project(statistics.outerKeys + statistics.statistics.map { it.outputAlias })
        return Lowered(operations, preparation.size)
    }
    private fun weighted(value: Calculation.WeightedMean) {
        val weightedValue = freshName()
        val actualWeight = freshName()
        preparation += Derive(ResultWeightedContribution(ResultSymbol(value.value), ResultSymbol(value.weight), statistics.unit,
            WeightedComponent.VALUE, value.numeratorContract, value.multiplyEquation), weightedValue, "配对加权观察")
        preparation += Derive(ResultWeightedContribution(ResultSymbol(value.value), ResultSymbol(value.weight), statistics.unit,
            WeightedComponent.WEIGHT, value.weightSumContract, null), actualWeight, "同一有效观察的权重")
        val numerator = freshName()
        val denominator = freshName()
        val nulls = if (value.nullPolicy == "IGNORE_PAIR") ResultAggregation.NullPolicy.IGNORE else ResultAggregation.NullPolicy.PROPAGATE
        aggregates += ResultAggregate(weightedValue, ResultAggregation.Sum(value.numeratorContract, nulls), numerator, "加权组成量")
        aggregates += ResultAggregate(actualWeight, ResultAggregation.Sum(value.weightSumContract, nulls), denominator, "配对权重总量")
        finish += Derive(ResultNumeric(ResultOperation(ResultOperator.SAFE_DIVIDE, listOf(ResultSymbol(numerator), ResultSymbol(denominator))),
            value.resultContract, value.divideEquation), value.outputAlias, value.outputDisplayName)
    }
    private fun freshName(): String {
        var index = 0
        while (!names.add("__observation_$index")) index++
        return "__observation_$index"
    }
    companion object {
        fun lower(statistics: ExecutableInputRoot.ObservationStatistics, actualNames: List<String>): Lowered =
            ObservationStatisticsLowering(statistics, actualNames).lower()
    }
}
