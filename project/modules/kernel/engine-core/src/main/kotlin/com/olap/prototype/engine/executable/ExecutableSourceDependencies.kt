package com.olap.prototype.engine.executable

import com.olap.prototype.engine.calcite.SemanticRexCompiler
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.SourceBindingConflictException
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.TemporalJoinContract

/** 实际事件、量词和关联的源依赖闭包，不发射SQL或推导新保证。 */
internal object ExecutableSourceDependencies {
    fun inputs(graph: ExecutableInputGraph, resolved: ResolvedInputSources): List<SourceBindingConflictException.Input> = graph.scopes.flatMap { (scope, plan) ->
        plan.eventInputs.flatMap(::sourceInputs).flatMap { event ->
            val owner = when (event) {
                is ExecutableMetricInput -> "metric:${event.metric.reference.key()}/call:${event.callId}"
                is ExecutableMemberInput.Projection -> "member:${event.memberSource.id}@${event.memberSource.version}"
            }
            fun positions(values: Map<com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id, ExecutablePhysicalExecutionBinding>) = values.map { (position, binding) ->
                SourceBindingConflictException.input(binding, scope.path, "$owner/event:${event.event.eventId}@${event.event.eventVersion}/$position")
            }
            val source = resolved.require(event)
            positions(source.positions) + quantified(source.quantifiers).flatMap { positions(it.positions) }
        }
    }.distinct()
    data class Selection(val bindings: Map<String, ExecutablePhysicalExecutionBinding>, val dependencies: SemanticRexCompiler.PhysicalDependencies)
    fun collect(graph: ExecutableInputGraph, resolved: ResolvedInputSources): Selection {
        val dependencies = graph.inputs.map { requiredPhysicalDependencies(it, resolved) }
        return Selection(bindings(graph.inputs, resolved), SemanticRexCompiler.PhysicalDependencies(
            dependencies.flatMapTo(linkedSetOf()) { it.columns }, dependencies.flatMapTo(linkedSetOf()) { it.tables }))
    }
    fun bindings(inputs: List<ExecutableInputScope>, resolved: ResolvedInputSources): Map<String, ExecutablePhysicalExecutionBinding> {
        val bindings = linkedMapOf<String, ExecutablePhysicalExecutionBinding>()
        inputs.flatMap { it.eventInputs }.flatMap(::sourceInputs).forEach { event -> sourcePositions(resolved.require(event)).forEach { addBinding(bindings, it) } }
        return bindings.toMap()
    }
    private fun addBinding(bindings: MutableMap<String, ExecutablePhysicalExecutionBinding>, binding: ExecutablePhysicalExecutionBinding) {
        val key = "${binding.bindingId}@${binding.bindingVersion}"
        val previous = bindings.putIfAbsent(key, binding)
        check(previous == null || previous == binding) { "conflicting frozen binding: $key" }
    }
    private fun sourceInputs(input: ExecutableEventInput): List<ExecutableEventInput> = listOf(input) +
        if (input is ExecutableMetricInput) input.relationConditions.values.map { it.input } else emptyList()
    private fun sourcePositions(source: ResolvedEventInput): List<ExecutablePhysicalExecutionBinding> = source.positions.values.toList() +
        quantified(source.quantifiers).flatMap { it.positions.values }
    private fun sourceJoins(source: ResolvedEventInput): List<ExecutablePhysicalJoin> = source.joins + quantified(source.quantifiers).flatMap { it.joins }
    private fun quantified(values: Map<List<String>, ResolvedQuantifier>): List<ResolvedQuantifier> = values.values.flatMap { listOf(it) + quantified(it.quantifiers) }

    fun requiredPhysicalDependencies(plan: ExecutableInputScope, resolved: ResolvedInputSources): SemanticRexCompiler.PhysicalDependencies {
        val columns = linkedSetOf<String>()
        val tables = linkedSetOf<SemanticExpression.TableArgument>()
        plan.eventInputs.flatMap(::sourceInputs).forEach { branch ->
            val bindings = linkedMapOf<String, ExecutablePhysicalExecutionBinding>()
            val source = resolved.require(branch)
            sourcePositions(source).forEach { addBinding(bindings, it) }
            val expressions = branch.sourceExpressions().toMutableList()
            branch.event.baseFilter?.let(expressions::add)
            sourceJoins(source).forEach { join ->
                join.joinPath.keys.forEach { key ->
                    expressions.add(SourceRelationCompiler.ref(key.sourceLogicUnitId, key.sourceLogicUnitVersion))
                    expressions.add(SourceRelationCompiler.ref(key.targetLogicUnitId, key.targetLogicUnitVersion))
                }
                join.joinPath.temporalConstraint?.intervals?.forEach { interval ->
                    (interval.sourceTime as? TemporalJoinContract.LogicUnitTime)?.let { expressions.add(SourceRelationCompiler.ref(it.logicUnitId, it.logicUnitVersion.toString())) }
                    expressions.add(SourceRelationCompiler.ref(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion.toString()))
                    expressions.add(SourceRelationCompiler.ref(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion.toString()))
                }
            }
            val dependencies = SemanticRexCompiler.physicalDependencies(expressions, bindings.values)
            columns.addAll(dependencies.columns); tables.addAll(dependencies.tables)
        }
        return SemanticRexCompiler.PhysicalDependencies(columns.toSet(), tables.toSet())
    }
}
