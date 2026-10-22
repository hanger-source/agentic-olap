package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MemberInvocationBinding
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSource
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow

/** 只有实际事件投影拥有源事件；计算来源与局部输入保留自身边界。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(value = ExecutableMemberInput.Projection::class, name = "PROJECTION"),
    JsonSubTypes.Type(value = ExecutableMemberInput.Computed::class, name = "COMPUTED"),
    JsonSubTypes.Type(value = ExecutableMemberInput.Local::class, name = "LOCAL"))
sealed interface ExecutableMemberInput {
    val callId: String
    val scope: QueryStageAnalysis.ScopeId
    val selection: MemberInvocationBinding.Bound
    @get:JsonIgnore val outputs: Map<String, String> get() = selection.outputs
    @get:JsonIgnore val meanings: Map<String, QueryStageAnalysis.MemberMeaning> get() = selection.meanings
    @get:JsonIgnore val valueContracts: Map<String, MetricValueContract?> get() = selection.valueContracts
    @get:JsonIgnore val unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>> get() = selection.unitDimensions
    @get:JsonIgnore val usedSlots: Set<String> get() = selection.usedSlots
    @get:JsonIgnore val condition: MemberCondition get() = selection.condition
    @get:JsonIgnore val range: TemporalWindow? get() = selection.range

    class Projection(override val callId: String, override val scope: QueryStageAnalysis.ScopeId,
        val memberSource: MemberSource.Projection, override val event: ExecutableEvent, override val topology: ExecutableTopology,
        override val selection: MemberInvocationBinding.Bound) : ExecutableMemberInput, ExecutableEventInput {
        init { ScopedInputContract.requireId(callId) }
        override val timeAnchor: ExecutableTimeAnchor? get() = null
        override val analysisDimensions: List<ExecutableBoundAnalysisDimension> get() = emptyList()
        override val filterExpression: DimensionCondition get() = DimensionCondition.Unrestricted()
        override fun clocks(): Map<String, TemporalClockBindings.Value> = selection.clocks
        override fun window(): TemporalWindow? = selection.range
        override fun rangeExpression(): SemanticExpression {
            check(window() != null) { "member range is absent" }
            return requireNotNull(memberSource.outputExpressions[requireNotNull(memberSource.definition.result.timeSlot) { "member range output is not declared" }])
        }
        override fun sourceExpressions(): List<SemanticExpression> = selection.usedSlots.map { requireNotNull(memberSource.outputExpressions[it]) }
    }
    data class Computed(override val callId: String, override val scope: QueryStageAnalysis.ScopeId, val source: MemberSource.Computed,
        override val selection: MemberInvocationBinding.Bound, val computation: ExecutableInputScope,
        val computationScope: QueryStageAnalysis.ScopeId) : ExecutableMemberInput { init { ScopedInputContract.requireId(callId) } }
    data class Local(override val callId: String, override val scope: QueryStageAnalysis.ScopeId, val inputScope: QueryStageAnalysis.ScopeId,
        val formal: Calculation.MemberSlot, val bindings: CalculationBindings, override val selection: MemberInvocationBinding.Bound) : ExecutableMemberInput {
        init { ScopedInputContract.requireId(callId) }
    }
}
