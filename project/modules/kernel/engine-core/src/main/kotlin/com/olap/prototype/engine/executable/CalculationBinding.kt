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
    val definition: Calculation?, private val body: Calculation.Scope, val bindings: CalculationBindings, val arguments: Map<String, Calculation.Value>,
    private val lexicalScope: ScopeId, val owner: CalculationOwner, val displayName: String,
    private val rootDepth: Int, private val stack: MutableSet<String>, initialMembers: Map<String, ExecutableMemberInput>, initialRelations: Map<String, ExecutableRelationInput>) {
    private val rootInputs = linkedMapOf<String, ExecutableInputScope>()
    private val activeInputs = linkedSetOf<String>()
    private val completedMembers = LinkedHashMap(initialMembers)
    private val completedRelations = LinkedHashMap(initialRelations)
    private val environment = MemberEnvironment(::localInput, lexicalScope, lexicalScope)

    constructor(session: ExecutableBindingSession, invocation: ExecutableMetricCall.Invocation, scope: ScopeId, depth: Int, stack: MutableSet<String>) :
        this(session, (invocation.metric.definition as MetricCalculation).calculation, (invocation.metric.definition as MetricCalculation).calculation.computation, invocation.bindings, invocation.arguments,
            scope, invocation.owner, invocation.displayName, depth, stack, invocation.members, invocation.relations)
    constructor(session: ExecutableBindingSession, root: Calculation.Compute, bindings: CalculationBindings,
        arguments: Map<String, Calculation.Value>, lexicalScope: ScopeId, depth: Int, owner: CalculationOwner, displayName: String,
        stack: MutableSet<String>, members: Map<String, ExecutableMemberInput>, relations: Map<String, ExecutableRelationInput>) :
        this(session, root.calculation, root.calculation.computation, bindings, arguments, lexicalScope, owner, displayName, depth, stack, members, relations)
    constructor(session: ExecutableBindingSession, body: Calculation.Scope, scope: ScopeId, depth: Int) :
        this(session, null, body, CalculationBindings.empty(), emptyMap(), scope, CalculationOwner.Query(scope.path), "查询计算", depth, linkedSetOf(), emptyMap(), emptyMap())
    constructor(session: ExecutableBindingSession, source: MemberSource.Computed, selection: MemberInvocationBinding.Bound,
        bindings: CalculationBindings, lexicalScope: ScopeId, depth: Int) :
        this(session, source.definition.calculation, source.definition.calculation.computation, bindings, selection.sourceArguments, lexicalScope,
            CalculationOwner.Member(MemberRef(source.id, source.version)), source.id, depth, linkedSetOf(), emptyMap(), emptyMap())

    fun childStack(child: Calculation.Call): MutableSet<String> = when (child) {
        is Calculation.DefinitionCall -> stack
        is Calculation.MeasurementCall, is Calculation.InstanceCall -> linkedSetOf()
    }
    fun templateStack(): MutableSet<String> = stack
    fun bindExternal(supplied: Map<String, CalculationBindings.Member>, suppliedRelations: Map<String, CalculationBindings.Relation>, caller: MemberEnvironment, callerScope: ScopeId, depth: Int, stack: MutableSet<String>) {
        val declared = requireNotNull(definition) { "only a parameterized calculation accepts external formal arguments" }
        supplied.keys.forEach { completedMembers[it] = session.members.bindFormal(declared, bindings, arguments, it, caller, callerScope, depth, stack) }
        suppliedRelations.keys.forEach { completedRelations[it] = session.relations.bind(declared, bindings, it, caller) }
    }
    fun fullRelation(slot: String): ExecutableRelationInput = completedRelations.getOrPut(slot) { session.relations.bind(requireNotNull(definition), bindings, slot, environment) }
    fun fullMember(slot: String, scope: ScopeId, depth: Int): ExecutableMemberInput {
        val existing = completedMembers[slot]
        if (existing is ExecutableMemberInput.Local) {
            val binding = bindings.members.getValue(slot) as CalculationBindings.LocalRef
            val formal = requireNotNull(definition).memberSlots.first { it.name == slot }
            return LocalMemberBinding.bind(formal, bindings, binding, session.completion.scope(existing.inputScope), existing.inputScope, scope, slot)
        }
        if (existing != null) return existing
        return session.members.bindFormal(requireNotNull(definition), bindings, arguments, slot, environment, scope, depth, stack).also { completedMembers[slot] = it }
    }
    fun bind(mode: String?): ExecutableInputScope = bindScope(body, lexicalScope, rootDepth, mode)
    private fun aliases(): (ScopedInputContract.DimensionRef) -> String = session.dimensions::alias
    private fun localInput(id: String): ExecutableInputScope {
        rootInputs[id]?.let { return it }
        val declared = requireNotNull(body.children()[id]) { "local input binding does not belong to this computation's root scope" }
        require(activeInputs.add(id)) { "local input dependency cycle: $activeInputs -> $id" }
        return try { bindScope(declared.computation, lexicalScope.child(id), rootDepth + 1, declared.mode).also { rootInputs[id] = it } }
        finally { activeInputs.remove(id) }
    }
    private fun bindScope(syntax: Calculation.Scope, scope: ScopeId, depth: Int, mode: String?): ExecutableInputScope = ScopeBinding(syntax, scope, depth, mode).bind()

    private inner class ScopeBinding(private val syntax: Calculation.Scope, private val scope: ScopeId, private val depth: Int, mode: String?) {
        private val input = ScopeAssembly(mode)
        fun bind(): ExecutableInputScope {
            session.completion.charge(depth)
            syntax.children().forEach { (id, child) -> input.inputs[id] = if (syntax === body) localInput(id)
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
                is Calculation.Compute -> bindCompute(root)
            }
            val stages = CalculationInstantiation.stages(syntax.stages, arguments, definition, bindings, aliases())
            return session.completion.register(scope, input.complete(boundRoot, stages), when (val root = syntax.root) {
                // 资格匹配原声明绑定，根节点则保留本次投影与筛选后的成员。
                is Calculation.Members -> when (val target = root.target) {
                    is Calculation.MemberParameter -> completedMembers.getValue(target.slot)
                    is Calculation.MemberSource -> (boundRoot as ExecutableInputRoot.Members).input
                }
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
            return when (val target = root.target) {
                is Calculation.MemberParameter -> ExecutableInputRoot.Members(session.members.select(this@CalculationBinding, root, fullMember(target.slot, scope, depth), scope))
                is Calculation.MemberSource -> ExecutableInputRoot.Members(session.members.bindSourceMember(root, this@CalculationBinding, input, scope, depth, linkedSetOf()))
            }
        }
        private fun bindCompute(root: Calculation.Compute): ExecutableInputRoot.Input {
            var id = "computation"
            var suffix = 0
            while (id in input.inputs) id = "computation_${++suffix}"
            ScopedInputContract.requireId(id)
            input.inputs[id] = session.bindInline(root, this@CalculationBinding, input, syntax, scope, scope.child(id), depth + 1)
            return ExecutableInputRoot.Input(id)
        }
        private fun bindMeasures(root: Calculation.Measures): ExecutableInputRoot.Measures {
            val groupingBindings = grouping(root, bindings, arguments, aliases())
            val grouping = CalculationInstantiation.groupingReferences(root, bindings, arguments, aliases())
            val levels = root.groupingLevels?.expand { expandSymbols(it, groupingFamilies(arguments, aliases())) }?.bind(grouping.keys.toList())
            val range = root.timeRange?.let {
                val value = resolve(it.window, bindings, arguments) as Calculation.Range
                val dimension = when (val reference = it.dimension) {
                    is Calculation.DimensionParameter -> requireNotNull(bindings.dimensions[reference.slot]) { "calculation time slot is not adopted" }.reference
                    is Calculation.PublishedDimension -> reference.reference
                }
                ExecutableTimeRange.from(dimension, value.window)
            }
            val clocks = root.clocks.mapValues { (_, value) -> (resolve(value, bindings, arguments) as Calculation.Clock).clock }
            val selection = ExecutableMeasureSelection(grouping, groupingBindings, levels, range, clocks)
            val calls = root.calls.map { session.metrics.bindChild(it, this@CalculationBinding, root, selection, input, scope, depth) }
            val facts = calls.filterIsInstance<ExecutableMetricCall.Fact>()
            val sourceCondition = resolve(root.sourceCondition, bindings, arguments) as Calculation.Condition
            require(facts.isNotEmpty() || range == null && clocks.isEmpty() && sourceCondition.condition is DimensionCondition.Unrestricted && root.sourceEventConditions.isEmpty()) { "declared source selections have no direct fact consumer" }
            root.sourceEventConditions.forEach { condition -> require(facts.any { it.input.event.eventId == condition.eventId && it.input.event.eventVersion == condition.eventVersion }) { "source event condition has no direct fact consumer" } }
            return ExecutableInputRoot.Measures(calls, selection, root.population, root.outputCondition)
        }
        private fun bindObjectRestrictions(root: ExecutableInputRoot.ObjectMeasure) {
            val objects = input.inputs.getValue("objects"); val measurement = input.inputs.getValue("measurement")
            root.roles.forEach { role ->
                val explicit = role.target as? Calculation.RecordCallOutputs
                if (explicit != null) {
                    var selected = measurement
                    var selectedSyntax = (syntax.root as Calculation.ObjectMeasure).measurement
                    explicit.inputPath.forEach { id ->
                        selectedSyntax = requireNotNull(selectedSyntax.children()[id]) { "record call target path is outside the measurement's declared input scopes: $id" }.computation
                        selected = requireNotNull(selected.additionalInputs[id]) { "record call target path differs from its bound input scopes: $id" }
                    }
                    session.completion.qualifications.attachRecordCall(selected, objects, scope.child("objects"), role)
                    return@forEach
                }
                val records = role.target as? Calculation.RecordOutputs
                val source = records?.let {
                    require(requireNotNull(definition).memberSlots.any { formal -> formal.name == it.memberSlot }) { "qualified member formal is not declared" }
                    fullMember(it.memberSlot, scope, depth)
                }
                val count = session.completion.qualifications.attach(measurement, objects, scope.child("objects"), role, source,
                    Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>()))
                require(count > 0) { "object qualification has no exact declared source consumer: ${role.target}" }
            }
        }
    }
}
