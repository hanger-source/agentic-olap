package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.calculation.CalculationInstantiation.expandSymbols
import com.olap.prototype.calculation.CalculationInstantiation.groupingFamilies
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.*
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 精确调用、测量要求、分层与事实采用的规则owner。 */
internal class MetricBinding(private val session: ExecutableBindingSession, private val catalog: ExecutableCatalog,
    private val dimensions: ExecutableDimensionBindings, private val memberSources: MemberSources) {
    fun bindChild(declaration: Calculation.Call, parent: CalculationBinding, measures: Calculation.Measures,
        selection: ExecutableMeasureSelection, caller: ScopeAssembly, scope: ScopeId, depth: Int): ExecutableMetricCall {
        require(parent.definition != null || declaration is Calculation.InstanceCall) { "a query selects an exact published metric instance; formal or fixed definition calls require a parameterized calculation" }
        val child = CalculationInstantiation.child(declaration, parent.definition, parent.bindings, parent.arguments,
            catalog::requireMetricInstance, { catalog.requireMetric(it).definition })
        val metric = catalog.requireMetric(child.metric)
        val fact = metric.definition as? MetricDefinition.FactMeasure
        val actualDimensions = if (fact != null) selection.groupingBindings.values.toList() else child.bindings.dimensions.values + child.bindings.groupingCandidates.values.flatten()
        val invocation = InvocationBinding(child)
        val call = when (declaration) {
            is Calculation.InstanceCall -> invocation.published(declaration, scope, catalog.requireMetricInstance(declaration.instance), caller, depth + 1)
            is Calculation.DefinitionCall -> invocation.child(declaration, scope, parent, actualDimensions,
                declaration.memberSlots.mapValues { (_, parentSlot) -> parent.fullMember(parentSlot, scope, depth) }, declaration.relationSlots)
            is Calculation.MeasurementCall -> invocation.child(declaration, scope, parent, actualDimensions,
                declaration.memberSlots.mapValues { (_, parentSlot) -> parent.fullMember(parentSlot, scope, depth) }, declaration.relationSlots)
        }
        val timeBinding = (measures.timeRange?.dimension as? Calculation.DimensionParameter)?.let { parent.bindings.dimensions[it.slot] }
        if (fact != null) {
            selection.groupingBindings.values.forEach { catalog.requireDimensionEvent(it, fact.event) }
            if (selection.timeRange != null && timeBinding != null) catalog.requireDimensionEvent(timeBinding, fact.event)
        }
        val condition = CalculationInstantiation.resolve(declaration.sourceCondition, parent.bindings, parent.arguments) as? Calculation.Condition
            ?: throw IllegalArgumentException("call source condition must resolve to a condition value")
        var filter = condition.condition
        if (fact != null) {
            val rootCondition = CalculationInstantiation.resolve(measures.sourceCondition, parent.bindings, parent.arguments) as Calculation.Condition
            filter = DimensionCondition.and(rootCondition.condition, filter)
            measures.sourceEventConditions.filter { it.eventId == fact.event.eventId && it.eventVersion == fact.event.eventVersion.toString() }
                .forEach { filter = DimensionCondition.and(filter, it.condition) }
        }
        return bindLevelCall(call, MeasureSelection(if (fact != null) selection else selection.withoutSourceWindow(),
            filter, timeBinding), depth + 1, parent.childStack(declaration))
    }

    private inner class InvocationBinding(child: CalculationInstantiation.Child) {
        private val metric = catalog.requireMetric(child.metric)
        private val measurementSlot = child.measurementSlot
        private val parameters = (metric.definition as? MetricCalculation)?.calculation?.parameters ?: emptyMap()
        private val arguments = CalculationInstantiation.arguments(parameters, child.fixedArguments, child.arguments, child.bindings)
        private val bindings = CalculationBindings(child.bindings.dimensions,
            CalculationInstantiation.resolveMembers(child.bindings.members, child.bindings, arguments), child.bindings.groupingCandidates, child.bindings.measurements, child.bindings.relations)
        init { catalog.requireBoundBindings(metric.definition, bindings) }

        fun published(declaration: Calculation.InstanceCall, scope: ScopeId, instance: MetricInstance, caller: ScopeAssembly, depth: Int): ExecutableMetricCall.Invocation {
            val definition = metric.definition as? MetricCalculation
            val members = if (definition == null) emptyMap() else {
                val environment = MemberEnvironment(caller.callerInputs(), scope.metric(declaration.callId), scope)
                val stack = linkedSetOf<String>()
                declaration.members.keys.associateWith { memberSources.bindFormal(definition.calculation, bindings, arguments, it, environment, scope, depth, stack) }
            }
            val relations = if (definition == null) emptyMap() else {
                val environment = MemberEnvironment(caller.callerInputs(), scope.metric(declaration.callId), scope)
                declaration.relations.keys.associateWith { session.relations.bind(definition.calculation, bindings, it, environment) }
            }
            return ExecutableMetricCall.Invocation(scope, declaration.callId, CalculationOwner.Metric(declaration.instance), instance.displayName,
                declaration.outputAlias, metric, arguments, bindings, null, members, relations)
        }
        fun child(declaration: Calculation.Call, scope: ScopeId, parent: CalculationBinding,
            actualDimensions: Collection<CalculationBindings.Dimension>, members: Map<String, ExecutableMemberInput>,
            relationSlots: Map<String, String>): ExecutableMetricCall.Invocation {
            val definition = metric.definition as? MetricCalculation
            val relations = relationSlots.mapValues { (slot, parentSlot) ->
                requireNotNull(definition) { "fact measures do not declare relation slots" }
                session.relations.adopt(definition.calculation, bindings, slot, parent.fullRelation(parentSlot))
            }
            return ExecutableMetricCall.Invocation(scope, declaration.callId, parent.owner, parent.displayName, declaration.outputAlias, metric, arguments,
                bindings, measurementRequirement(measurementSlot, parent.bindings, bindings, arguments, actualDimensions), members, relations)
        }
    }

    fun bindLevelCall(invocation: ExecutableMetricCall.Invocation, selection: MeasureSelection, depth: Int, stack: MutableSet<String>): ExecutableMetricCall {
        val levels = selection.levels
        if (levels == null || invocation.metric.definition is MetricDefinition.FactMeasure)
            return bindCall(invocation, selection, depth, stack, invocation.scope.metric(invocation.callId))
        val definition = invocation.metric.definition as MetricCalculation
        val calls = levels.levels.map { level ->
            val selected = selection.grouping.filterKeys { it in level.groupBy }
            val arguments = definition.levelArguments(invocation.arguments, selected.values)
            val bound = invocation.copy(arguments = arguments, measurementSlot = invocation.measurementSlot?.atLevel(selected.values))
            val source = ExecutableMeasureSelection(selected, selection.groupingBindings.filterKeys(selected::containsKey), null, selection.range, selection.source.clocks)
            val calculation = bindCall(bound, MeasureSelection(source, selection.filter, selection.timeBinding), depth + 1, stack,
                invocation.scope.metric(invocation.callId).level(level.id)) as ExecutableMetricCall.Calculation
            ExecutableMetricCall.CalculationLevel(level, calculation)
        }
        return ExecutableMetricCall.LevelCalculation(invocation, levels, calls)
    }
    private fun bindCall(call: ExecutableMetricCall.Invocation, selection: MeasureSelection, depth: Int, stack: MutableSet<String>, computationScope: ScopeId): ExecutableMetricCall {
        session.completion.charge(depth)
        val fact = call.metric.definition as? MetricDefinition.FactMeasure
        if (fact != null) return bindFact(call, fact, selection)
        require(selection.range == null && selection.filter is DimensionCondition.Unrestricted) { "algorithm source conditions must be supplied through declared child parameters" }
        val key = call.metric.reference.key()
        require(stack.add(key)) { "metric calculation cycle: $stack -> $key" }
        return try {
            val definition = call.metric.definition as MetricCalculation
            val input = CalculationBinding(session, call, computationScope, depth, stack).bind(null)
            ExecutableMetricCall.Calculation(call, input, definition.measureOutput,
                expandSymbols(definition.keyOutputs, groupingFamilies(call.arguments, dimensions::alias)))
        } finally { stack.remove(key) }
    }
    private fun bindFact(call: ExecutableMetricCall.Invocation, fact: MetricDefinition.FactMeasure, selection: MeasureSelection): ExecutableMetricCall.Fact {
        val event = requireNotNull(catalog.events["${fact.event.eventId}@${fact.event.eventVersion}"]) { "fact event is missing" }
        val topology = requireNotNull(catalog.topologies["${event.topologyId}@${event.topologyVersion}"]) { "fact topology is missing" }
        val boundDimensions = mutableListOf<ExecutableBoundAnalysisDimension>()
        val rangeDimension = selection.range?.let { dimensions.bind(event, ScopedInputContract.DimensionRef(it.timeDimensionId, it.timeDimensionVersion, it.levelCode), true, selection.timeBinding) }
        selection.grouping.forEach { (alias, ref) -> dimensions.include(boundDimensions, dimensions.bind(event, ref, false, selection.groupingBindings[alias]).copy(outputAlias = alias)) }
        DimensionCondition.leaves(selection.filter).forEach { dimensions.include(boundDimensions, dimensions.bind(event, it.dimension, true)) }
        val relations = linkedMapOf<String, ExecutableRelationCondition>()
        DimensionCondition.existences(selection.filter).forEach { exists ->
            val occurrence = bindRelationCondition(event, call.scope, exists)
            require(relations.putIfAbsent(exists.instanceId, occurrence) == null) { "relation occurrence id is duplicated within a source input" }
        }
        return ExecutableMetricCall.Fact(ExecutableMetricInput(call, selection.source, event, topology, dimensions.timeAnchor(event, selection.range),
            rangeDimension, boundDimensions.toList(), selection.filter, relations, emptyList()))
    }
    private fun bindRelationCondition(event: ExecutableEvent, scope: ScopeId, exists: DimensionCondition.Exists): ExecutableRelationCondition {
        val matching = catalog.relationFilters.values.filter { it.id == exists.filterRefId }
        require(matching.size == 1) { "relation input must identify one exact definition" }
        val grant = matching.single()
        require(grant.parent.eventId == event.eventId && grant.parent.eventVersion.toString() == event.eventVersion) { "relation filter belongs to a different parent event" }
        val source = requireNotNull(catalog.memberSources["${grant.memberSourceId}@${grant.memberSourceVersion}"]) { "related member source is not frozen" }
        require(source is MemberSource.Projection) { "event relation filters require an event projection source" }
        val member = session.members.projectionMember(source, MemberInvocationBinding.relationSelection(exists, source, grant.clockSlots), exists.instanceId, scope)
        return ExecutableRelationCondition(exists.instanceId, grant, member)
    }
    private fun measurementRequirement(slot: Calculation.MeasurementSlot?, parent: CalculationBindings, target: CalculationBindings,
        arguments: Map<String, Calculation.Value>, actualDimensions: Collection<CalculationBindings.Dimension>): ExecutableMetricCall.MeasurementRequirement? {
        if (slot == null) return null
        val observations = CalculationInstantiation.observations(slot, parent, target, arguments)
        observations.dimensions().forEach { (dimension, role) ->
            val roles = actualDimensions.filter { it.reference == dimension }.map { it.role }.toSet()
            require(roles == setOf(role)) { "measurement observation differs in its exact semantic dimension or role: $dimension" }
        }
        return ExecutableMetricCall.MeasurementRequirement(observations, slot.valueContract.adopted(parent))
    }
}
