package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutableDimensionBinding
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.query.TemporalClockBindings

data class ResolvedEventInput(val root: Id, val positions: Map<Id, ExecutablePhysicalExecutionBinding>,
    val eventExpression: Expression, val metricExpression: Expression?, val dimensions: Map<String, Expression>,
    val memberOutputs: Map<String, Expression>, val rangeExpression: Expression?, val joins: List<ExecutablePhysicalJoin>,
    val temporalClockBindings: Map<String, TemporalClockBindings.Value>, val quantifiers: Map<List<String>, ResolvedQuantifier>) {
    init { require(root in positions) { "event backbone position is required" } }
    fun backboneExecutionBinding(): ExecutablePhysicalExecutionBinding = positions.getValue(root)
    fun timeAnchorExecutionBinding(): ExecutablePhysicalExecutionBinding? = rangeExpression?.let { positions[it.local] }
    fun dimension(binding: ExecutableDimensionBinding): Expression = requireNotNull(dimensions["${binding.bindingId}@${binding.bindingVersion}"]) { "dimension expression position is missing" }
}
