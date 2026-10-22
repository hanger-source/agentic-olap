package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.CalculationInstantiation
import com.olap.prototype.calculation.CalculationOwner
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.engine.catalog.ExecutableMetric
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract

/** 同指标版本的调用仍持独立身份与绑定。 */
sealed interface ExecutableMetricCall {
    val invocation: Invocation
    fun calculationScopes(owner: ScopeId): Map<ScopeId, Calculation> = when (this) {
        is Calculation -> mapOf(owner.metric(invocation.callId) to this)
        is LevelCalculation -> levels.associate { owner.metric(invocation.callId).level(it.level.id) to it.calculation }
        is Fact -> emptyMap()
    }
    fun calculations(): List<Calculation> = when (this) { is Calculation -> listOf(this); is LevelCalculation -> levels.map { it.calculation }; is Fact -> emptyList() }

    data class Invocation(val scope: ScopeId, val callId: String, val owner: CalculationOwner, val displayName: String,
        val outputAlias: String, val metric: ExecutableMetric, val arguments: Map<String, com.olap.prototype.calculation.Calculation.Value>,
        val bindings: CalculationBindings, val measurementSlot: MeasurementRequirement?, @get:JsonIgnore val members: Map<String, ExecutableMemberInput>,
        @get:JsonIgnore val relations: Map<String, ExecutableRelationInput>) {
        init { ScopedInputContract.requireId(callId); MetricDefinition.text(displayName, "metric instance displayName") }
    }
    data class MeasurementRequirement(val observations: CalculationInstantiation.ObservationGrain, val valueContract: MetricValueContract) {
        fun atLevel(selected: Collection<ScopedInputContract.DimensionRef>): MeasurementRequirement = copy(observations = observations.atLevel(selected))
    }
    data class Fact(val input: ExecutableMetricInput) : ExecutableMetricCall { override val invocation: Invocation get() = input.invocation }
    data class Calculation(override val invocation: Invocation, val input: ExecutableInputScope, val measureOutput: String, val keyOutputs: List<String>) : ExecutableMetricCall
    data class CalculationLevel(val level: GroupingLevels.BoundLevel, val calculation: Calculation)
    data class LevelCalculation(override val invocation: Invocation, val groupingLevels: GroupingLevels.Bound, val levels: List<CalculationLevel>) : ExecutableMetricCall {
        init { require(groupingLevels.levels == levels.map { it.level }) { "calculation must instantiate all and only declared grouping levels" } }
    }
}
