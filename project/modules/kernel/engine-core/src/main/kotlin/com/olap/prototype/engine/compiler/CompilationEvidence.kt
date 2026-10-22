package com.olap.prototype.engine.compiler

import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.engine.executable.*
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract

/** scope身份只取真实调用边，不从业务别名重建。 */
internal data class CompilationEvidence(val sources: List<CompiledQuery.SourceEvidence>, val invocations: List<CompiledQuery.InvocationEvidence>) {
    companion object {
        fun collect(graph: ExecutableInputGraph, resolved: ResolvedInputSources): CompilationEvidence = collect(graph, ScopeId.root(), resolved)
        private fun collect(graph: ExecutableInputGraph, scope: ScopeId, resolved: ResolvedInputSources): CompilationEvidence {
            val input = requireNotNull(graph.scope(scope))
            val sources = input.eventInputs.map { sourceEvidence(it, scope, resolved) }.toMutableList()
            val invocations = mutableListOf<CompiledQuery.InvocationEvidence>()
            val children = graph.children(scope).map { it to collect(graph, it.scope, resolved) }
            val additional = children.filter { it.first.kind == ExecutableInputGraph.EdgeKind.DECLARED_INPUT }.map { it.second }
            additional.forEach { sources.addAll(it.sources) }
            input.metricCalls.forEach { call ->
                val invocation = call.invocation
                invocations.add(CompiledQuery.InvocationEvidence(scope, invocation.callId, invocation.owner, invocation.metric.reference,
                    invocation.outputAlias, invocation.arguments, invocation.bindings))
                children.filter { it.first.kind == ExecutableInputGraph.EdgeKind.METRIC_CALL && it.first.name == invocation.callId }.forEach {
                    sources.addAll(it.second.sources); invocations.addAll(it.second.invocations)
                }
            }
            additional.forEach { invocations.addAll(it.invocations) }
            children.filter { it.first.kind == ExecutableInputGraph.EdgeKind.MEMBER_BODY }.forEach { sources.addAll(it.second.sources); invocations.addAll(it.second.invocations) }
            return CompilationEvidence(sources.toList(), invocations.toList())
        }
        private fun sourceEvidence(event: ExecutableEventInput, scope: ScopeId, resolved: ResolvedInputSources): CompiledQuery.SourceEvidence {
            val source = resolved.require(event)
            val identity = when (event) {
                is ExecutableMetricInput -> CompiledQuery.FactIdentity(event.callId, event.owner, event.metric.reference,
                    event.analysisDimensions.filterNot { it.filterOnly }.associate { it.outputAlias to ScopedInputContract.DimensionRef(
                        it.analysisDimension.analysisDimensionId, it.analysisDimension.analysisDimensionVersion, it.levelCode) }, event.filterExpression)
                is ExecutableMemberInput.Projection -> CompiledQuery.MemberIdentity(event.memberSource.id, event.memberSource.version.toString(), event.outputs, event.meanings, event.condition)
            }
            val relations = if (event is ExecutableMetricInput) event.relationConditions.values.map {
                CompiledQuery.RelationEvidence(it.instanceId, it.definition.id, sourceEvidence(it.input, scope, resolved))
            } else emptyList()
            return CompiledQuery.SourceEvidence(scope, identity, event.event.eventId, event.event.eventVersion, event.event.identity,
                positions(source.positions), joins(source.joins), event.window(), source.temporalClockBindings, quantifiers(source.quantifiers), relations)
        }
        private fun positions(positions: Map<Id, ExecutablePhysicalExecutionBinding>): List<CompiledQuery.PositionEvidence> =
            positions.map { (id, binding) -> CompiledQuery.PositionEvidence(id, binding.bindingId, binding.bindingVersion) }
        private fun joins(joins: List<ExecutablePhysicalJoin>): List<CompiledQuery.JoinEvidence> = joins.map {
            CompiledQuery.JoinEvidence(it.joinPath.joinPathId, it.joinPath.joinPathVersion, it.source, it.target, it.sourceTimes, it.clockSlots, it.requiredMatch)
        }
        private fun quantifiers(quantifiers: Map<List<String>, ResolvedQuantifier>): List<CompiledQuery.QuantifiedEvidence> = quantifiers.map { (occurrence, source) ->
            CompiledQuery.QuantifiedEvidence(occurrence, source.parent, positions(source.positions), joins(source.joins), source.clocks, quantifiers(source.quantifiers))
        }
    }
}
