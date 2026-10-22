package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow

/** 事实调用身份和入口选择各只有一个事实来源；对象身份保持occurrence。 */
class ExecutableMetricInput(val invocation: ExecutableMetricCall.Invocation, val selection: ExecutableMeasureSelection,
    override val event: ExecutableEvent, override val topology: ExecutableTopology, override val timeAnchor: ExecutableTimeAnchor?,
    val rangeDimension: ExecutableBoundAnalysisDimension?, override val analysisDimensions: List<ExecutableBoundAnalysisDimension>,
    override val filterExpression: DimensionCondition, val relationConditions: Map<String, ExecutableRelationCondition>,
    val objectRestrictions: List<ExecutableObjectRestriction>) : ExecutableEventInput {
    init { require((selection.timeRange == null) == (rangeDimension == null)) { "fact range requires its actual event dimension" } }
    val metric: ExecutableMetric get() = invocation.metric
    val scope: QueryStageAnalysis.ScopeId get() = invocation.scope
    val callId: String get() = invocation.callId
    val owner get() = invocation.owner
    val outputAlias: String get() = invocation.outputAlias
    val groupingLevels: GroupingLevels.Bound? get() = selection.groupingLevels
    val timeRange: ExecutableTimeRange? get() = selection.timeRange
    override fun clocks(): Map<String, TemporalClockBindings.Value> = selection.clocks
    override fun window(): TemporalWindow? = timeRange?.window()
    override fun rangeExpression(): SemanticExpression {
        check(timeRange != null) { "fact range is absent" }
        val anchor = requireNotNull(timeAnchor) { "fact range requires an exact time anchor" }
        return SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, anchor.effectiveTimeLogicUnitId, anchor.effectiveTimeLogicUnitVersion.toLong())
    }
    override fun sourceExpressions(): List<SemanticExpression> {
        val fact = metric.factMeasure()
        val expressions = mutableListOf(fact.input)
        fact.privatePredicate?.let(expressions::add)
        fact.snapshotRule?.expressions()?.let(expressions::addAll)
        objectRestrictions.forEach { restriction ->
            val keys = event.identity.entityRoles.getValue((restriction.target as Calculation.EventRole).role)
            keys.forEach { expressions.add(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, it.logicUnitId, it.logicUnitVersion)) }
        }
        analysisDimensions.forEach { expressions.add(it.binding.expression) }
        if (timeRange != null) expressions.add(rangeExpression())
        return expressions.toList()
    }
}
