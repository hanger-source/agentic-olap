package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.calculation.CalculationInstantiation.expandSymbols
import com.olap.prototype.calculation.CalculationInstantiation.grouping
import com.olap.prototype.calculation.CalculationInstantiation.groupingFamilies
import com.olap.prototype.calculation.CalculationInstantiation.resolve
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.*
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import java.util.Collections
import java.util.IdentityHashMap

/** 一次计算调用的实参、采用与词法完成状态，不拥有指标或来源规则。 */
internal class CalculationBinding private constructor(private val session: ExecutableBindingSession,
    val definition: Calculation, val bindings: CalculationBindings, val arguments: Map<String, Calculation.Value>,
    private val lexicalScope: ScopeId, val owner: CalculationOwner, val displayName: String,
    private val rootDepth: Int, private val stack: MutableSet<String>, initialMembers: Map<String, ExecutableMemberInput>, initialRelations: Map<String, ExecutableRelationInput>) {
    private val rootInputs = linkedMapOf<String, ExecutableInputScope>()
    private val activeInputs = linkedSetOf<String>()
    private val completedMembers = LinkedHashMap(initialMembers)
    private val completedRelations = LinkedHashMap(initialRelations)
    private val environment = MemberEnvironment(::localInput, lexicalScope, lexicalScope)

    constructor(session: ExecutableBindingSession, invocation: ExecutableMetricCall.Invocation, scope: ScopeId, depth: Int, stack: MutableSet<String>) :
        this(session, (invocation.metric.definition as MetricCalculation).calculation, invocation.bindings, invocation.arguments,
            scope, invocation.owner, invocation.displayName, depth, stack, invocation.members, invocation.relations)
    constructor(session: ExecutableBindingSession, root: QueryComputation.Compute, bindings: CalculationBindings,
        arguments: Map<String, Calculation.Value>, callerScope: ScopeId, lexicalScope: ScopeId, depth: Int) :
        this(session, root.calculation, bindings, arguments, lexicalScope, CalculationOwner.Query(callerScope.path), "内联计算", depth, linkedSetOf(), emptyMap(), emptyMap())
    constructor(session: ExecutableBindingSession, source: MemberSource.Computed, selection: MemberInvocationBinding.Bound,
        bindings: CalculationBindings, lexicalScope: ScopeId, depth: Int) :
        this(session, source.definition.calculation, bindings, selection.sourceArguments, lexicalScope,
            CalculationOwner.Member(MemberRef(source.id, source.version)), source.id, depth, linkedSetOf(), emptyMap(), emptyMap())

    fun childStack(child: Calculation.Call): MutableSet<String> = if (child.target is Calculation.Measurement) linkedSetOf() else stack
    fun bindExternal(supplied: Map<String, CalculationBindings.Member>, suppliedRelations: Map<String, CalculationBindings.Relation>, caller: MemberEnvironment, callerScope: ScopeId, depth: Int, stack: MutableSet<String>) {
        supplied.keys.forEach { completedMembers[it] = session.members.bindFormal(definition, bindings, arguments, it, caller, callerScope, depth, stack) }
        suppliedRelations.keys.forEach { completedRelations[it] = session.relations.bind(definition, bindings, it, caller) }
    }
    fun fullRelation(slot: String): ExecutableRelationInput = completedRelations.getOrPut(slot) { session.relations.bind(definition, bindings, slot, environment) }
    fun fullMember(slot: String, scope: ScopeId, depth: Int): ExecutableMemberInput {
        val existing = completedMembers[slot]
        if (existing is ExecutableMemberInput.Local) {
            val binding = bindings.members.getValue(slot) as CalculationBindings.LocalRef
            val formal = definition.memberSlots.first { it.name == slot }
            return LocalMemberBinding.bind(formal, bindings, binding, session.completion.scope(existing.inputScope), existing.inputScope, scope, slot)
        }
        if (existing != null) return existing
        return session.members.bindFormal(definition, bindings, arguments, slot, environment, scope, depth, stack).also { completedMembers[slot] = it }
    }
    fun bind(mode: String?): ExecutableInputScope = bindScope(definition.computation, lexicalScope, rootDepth, mode)
    private fun aliases(): (ScopedInputContract.DimensionRef) -> String = session.dimensions::alias
    private fun localInput(id: String): ExecutableInputScope {
        rootInputs[id]?.let { return it }
        val declared = requireNotNull(definition.computation.children()[id]) { "local member binding does not belong to this calculation's root scope" }
        require(activeInputs.add(id)) { "local input dependency cycle: $activeInputs -> $id" }
        return try { bindScope(declared.computation, lexicalScope.child(id), rootDepth + 1, declared.mode).also { rootInputs[id] = it } }
        finally { activeInputs.remove(id) }
    }
    private fun bindScope(syntax: Calculation.Scope, scope: ScopeId, depth: Int, mode: String?): ExecutableInputScope = ScopeBinding(syntax, scope, depth, mode).bind()

    private inner class ScopeBinding(private val syntax: Calculation.Scope, private val scope: ScopeId, private val depth: Int, mode: String?) {
        private val input = ScopeAssembly(mode)
        fun bind(): ExecutableInputScope {
            session.completion.charge(depth)
            syntax.children().forEach { (id, child) -> input.inputs[id] = if (syntax === definition.computation) localInput(id)
                else bindScope(child.computation, scope.child(id), depth + 1, child.mode) }
            val boundRoot = when (val root = syntax.root) {
                is Calculation.ObjectMeasure -> bindObjectMeasure(root)
                is Calculation.AlignMeasurements -> bindAlignMeasurements(root)
                is Calculation.ObservationStatistics -> bindObservationStatistics(root)
                is Calculation.Input -> ExecutableInputRoot.Input(root.inputId)
                is Calculation.Relation -> ExecutableInputRoot.Relation(fullRelation(root.slot))
                is Calculation.SetCombination -> ExecutableInputRoot.SetInputs(root.operation, root.all, root.inputs, root.outputSlots.map { it.adopted(bindings) })
                is Calculation.Members -> bindMembers(root)
                is Calculation.Measures -> bindMeasures(root)
            }
            val stages = CalculationInstantiation.stages(syntax.stages, arguments, definition, bindings, aliases())
            return session.completion.register(scope, input.complete(boundRoot, stages), when (val root = syntax.root) {
                // 资格匹配原声明绑定，根节点则保留本次投影与筛选后的成员。
                is Calculation.Members -> completedMembers.getValue(root.slot)
                else -> null
            })
        }
        private fun bindObjectMeasure(root: Calculation.ObjectMeasure): ExecutableInputRoot.ObjectMeasure {
            val families = groupingFamilies(arguments, aliases())
            return ExecutableInputRoot.ObjectMeasure(root.roles.map { Calculation.ObjectRole(expandSymbols(it.objectKeys, families), it.target) })
                .also(::bindObjectRestrictions)
        }
        private fun bindAlignMeasurements(root: Calculation.AlignMeasurements): ExecutableInputRoot.AlignMeasurements {
            val keys = linkedMapOf<String, Map<String, String>>()
            val values = linkedMapOf<String, List<Calculation.AlignedValue>>()
            val families = groupingFamilies(arguments, aliases())
            root.inputs.forEach { (id, child) ->
                val actual = linkedMapOf<String, String>()
                child.keys.forEach { (name, symbol) ->
                    val names = expandSymbols(listOf(name), families); val symbols = expandSymbols(listOf(symbol), families)
                    require(names.size == symbols.size) { "aligned grouping family mapping differs in width" }
                    names.indices.forEach { require(actual.putIfAbsent(names[it], symbols[it]) == null) { "aligned grouping family repeats an identity key" } }
                }
                keys[id] = actual; values[id] = child.values
            }
            return ExecutableInputRoot.AlignMeasurements(expandSymbols(root.observationKeys, families), keys, values, root.population)
        }
        private fun bindObservationStatistics(root: Calculation.ObservationStatistics): ExecutableInputRoot.ObservationStatistics {
            val adopted = root.statistics.map { value -> when (value) {
                is Calculation.Reduction -> value.aggregate.let { aggregate ->
                    Calculation.Reduction(aggregate.copy(aggregation = aggregate.aggregation.mapContracts { it.adopted(bindings) }))
                }
                is Calculation.WeightedMean -> Calculation.WeightedMean(value.value, value.weight, value.outputAlias, value.outputDisplayName,
                    value.numeratorContract.adopted(bindings), value.weightSumContract.adopted(bindings), value.resultContract.adopted(bindings),
                    value.multiplyEquation, value.divideEquation, value.nullPolicy)
            } }
            val families = groupingFamilies(arguments, aliases())
            val unit = (root.unit as? Calculation.ObjectsBy)?.let { Calculation.ObjectsBy(expandSymbols(it.keys, families)) } ?: root.unit
            return ExecutableInputRoot.ObservationStatistics(unit, expandSymbols(root.outerKeys, families), adopted)
        }
        private fun bindMembers(root: Calculation.Members): ExecutableInputRoot.Members {
            val full = fullMember(root.slot, scope, depth)
            return ExecutableInputRoot.Members(session.members.select(this@CalculationBinding, root, full, scope))
        }
        private fun bindMeasures(root: Calculation.Measures): ExecutableInputRoot.Measures {
            val groupingBindings = grouping(root, bindings, arguments, aliases())
            val grouping = groupingBindings.mapValues { it.value.reference }
            val levels = root.groupingLevels?.expand { expandSymbols(it, groupingFamilies(arguments, aliases())) }?.bind(grouping.keys.toList())
            val range = root.range?.let {
                val value = resolve(it, bindings, arguments) as Calculation.Range
                val dimension = requireNotNull(bindings.dimensions[root.timeSlot]) { "calculation time slot is not adopted" }.reference
                ExecutableTimeRange.from(dimension, value.window)
            }
            val clocks = root.clocks.mapValues { (_, value) -> (resolve(value, bindings, arguments) as Calculation.Clock).clock }
            val selection = ExecutableMeasureSelection(grouping, groupingBindings, levels, range, clocks)
            val calls = root.calls.map { session.metrics.bindChild(it, this@CalculationBinding, root, selection, scope, depth) }
            require(calls.any { it is ExecutableMetricCall.Fact } || range == null && clocks.isEmpty()) { "declared source range/clocks have no direct fact consumer" }
            return ExecutableInputRoot.Measures(calls, selection, RowPopulationContract.calls(root.population.callIds), DimensionCondition.Unrestricted())
        }
        private fun bindObjectRestrictions(root: ExecutableInputRoot.ObjectMeasure) {
            val objects = input.inputs.getValue("objects"); val measurement = input.inputs.getValue("measurement")
            root.roles.forEach { role ->
                val records = role.target as? Calculation.RecordOutputs
                val source = records?.let {
                    require(definition.memberSlots.any { formal -> formal.name == it.memberSlot }) { "qualified member formal is not declared" }
                    fullMember(it.memberSlot, scope, depth)
                }
                val count = session.completion.qualifications.attach(measurement, objects, scope.child("objects"), role, source,
                    Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>()))
                require(count > 0) { "object qualification has no exact declared source consumer: ${role.target}" }
            }
        }
    }
}
