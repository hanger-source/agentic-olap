package com.olap.prototype.calculation

import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow
import java.util.Collections
import java.util.TreeMap

/** 成员形参与真实来源的绑定；关系位置和源唯一性归来源编译。 */
object MemberInvocationBinding {
    fun interface DimensionMapping { fun matches(source: MemberSource, dimension: CalculationBindings.Dimension, output: MemberSourceDefinition.Output): Boolean }

    class Bound(outputs: Map<String, String>, meanings: Map<String, QueryStageAnalysis.MemberMeaning>, valueContracts: Map<String, MetricValueContract?>,
        unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>>, usedSlots: Set<String>, val condition: MemberCondition,
        val range: TemporalWindow?, clocks: Map<String, TemporalClockBindings.Value>, sourceArguments: Map<String, Calculation.Value>) {
        val outputs: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(outputs))
        val meanings: Map<String, QueryStageAnalysis.MemberMeaning> = Collections.unmodifiableMap(LinkedHashMap(meanings))
        val valueContracts: Map<String, MetricValueContract?> = Collections.unmodifiableMap(LinkedHashMap(valueContracts))
        val unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>> = Collections.unmodifiableMap(unitDimensions.mapValues { java.util.List.copyOf(it.value) })
        val usedSlots: Set<String> = Collections.unmodifiableSet(LinkedHashSet(usedSlots))
        val clocks: Map<String, TemporalClockBindings.Value> = Collections.unmodifiableMap(LinkedHashMap(clocks))
        val sourceArguments: Map<String, Calculation.Value> = Collections.unmodifiableMap(LinkedHashMap(sourceArguments))
        override fun equals(other: Any?): Boolean = other is Bound && outputs == other.outputs && meanings == other.meanings && valueContracts == other.valueContracts && unitDimensions == other.unitDimensions && usedSlots == other.usedSlots && condition == other.condition && range == other.range && clocks == other.clocks && sourceArguments == other.sourceArguments
        override fun hashCode(): Int = listOf(outputs, meanings, valueContracts, unitDimensions, usedSlots, condition, range, clocks, sourceArguments).hashCode()
    }

    fun requireMember(owner: Calculation, required: Calculation.MemberSlot, bindings: CalculationBindings, source: MemberSource, dimensionMappings: DimensionMapping) {
        val definition = source.definition.result
        val adopted = requireNotNull(bindings.members[required.name]) { "member slot is not bound" } as CalculationBindings.SourceCall
        require(required.entity == definition.entity && required.role == definition.role && required.shape == definition.shape) { "member source differs from its declared entity, role or shape" }
        require(required.outputs.keys == adopted.outputSlots.keys) { "member binding must bind all and only declared formal outputs" }
        val units = linkedMapOf<String, ScopedInputContract.DimensionRef>()
        required.outputs.forEach { (formal, requirement) ->
            val slot = requireNotNull(adopted.outputSlots[formal])
            val output = requireNotNull(definition.outputs[slot]) { "bound member output is missing" }
            MetricValueContract.requireMemberValue(Calculation.rootValueContract(requirement), MemberSourceDefinition.rootValueContract(output), required, adopted)
            when (requirement) {
                is Calculation.MemberDimension -> {
                    val semantic = owner.dimensionSlots.first { it.name == requirement.dimensionSlot }
                    val binding = requireNotNull(bindings.dimensions[requirement.dimensionSlot]) { "member semantic dimension slot is not bound" }
                    val meaning = when {
                        output is MemberSourceDefinition.DimensionOutput -> output.dimensionId == semantic.dimensionId && output.dimensionVersion == semantic.dimensionVersion && output.role == semantic.role
                        output is MemberSourceDefinition.ComputedDimensionOutput && source is MemberSource.Computed -> {
                            val declared = source.definition.calculation.dimensionSlots.first { it.name == output.dimensionSlot }
                            declared.dimensionId == semantic.dimensionId && declared.dimensionVersion == semantic.dimensionVersion && declared.role == semantic.role
                        }
                        else -> false
                    }
                    require(meaning && dimensionMappings.matches(source, binding, output)) { "member output differs from its exact bound semantic dimension and relationship position" }
                }
                is Calculation.MemberContainerProperty -> require(output !is MemberSourceDefinition.ScalarOutput && output.role == requirement.role && MemberSourceDefinition.containerShape(output) == requirement.shape) { "member container shape or role differs from the required output" }
                is Calculation.MemberProperty -> require((output is MemberSourceDefinition.PropertyOutput || output is MemberSourceDefinition.ComputedPropertyOutput) && MemberSourceDefinition.scalar(output).valueType == requirement.valueType && output.role == requirement.role) { "member property role or type differs from its required output" }
            }
            val unit = Calculation.rootValueContract(requirement)?.unit as? MetricValueContract.SlotDimension
            if (unit != null) {
                val local = requireNotNull(MemberSourceDefinition.rootValueContract(output)).unit as MetricValueContract.SlotDimension
                val dimension = requireNotNull(bindings.dimensions[unit.slot]) { "member unit dimension is not bound" }
                val previous = units.putIfAbsent(local.slot, dimension.reference)
                require(previous == null || previous == dimension.reference) { "one local member unit slot cannot bind contradictory analysis dimensions" }
            }
        }
    }

    fun bind(owner: Calculation, root: Calculation.Members, bindings: CalculationBindings, arguments: Map<String, Calculation.Value>, source: MemberSource, dimensionMappings: DimensionMapping): Bound {
        val definition = source.definition.result
        val adopted = requireNotNull(bindings.members[root.slot]) { "member slot is not adopted" } as CalculationBindings.SourceCall
        val required = owner.memberSlots.first { it.name == root.slot }
        require(adopted.source == MemberRef(source.id, source.version)) { "member source differs from its exact binding identity" }
        requireMember(owner, required, bindings, source, dimensionMappings)
        val formalMeanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val unitDimensions = linkedMapOf<String, List<ScopedInputContract.DimensionRef>>()
        required.outputs.forEach { (formal, requirement) ->
            val output = requireNotNull(definition.outputs[requireNotNull(adopted.outputSlots[formal])]) { "frozen member output is missing" }
            formalMeanings[formal] = when (requirement) {
                is Calculation.MemberDimension -> QueryStageAnalysis.MappedMemberDimension(requireNotNull(bindings.dimensions[requirement.dimensionSlot]) { "member semantic dimension slot is not adopted" }.reference, output.role)
                is Calculation.MemberContainerProperty -> QueryStageAnalysis.MemberContainer(requirement.shape, requirement.role)
                is Calculation.MemberProperty -> QueryStageAnalysis.MemberProperty(MemberSourceDefinition.scalar(output).valueType, output.role)
            }
            val unit = Calculation.rootValueContract(requirement)?.unit as? MetricValueContract.SlotDimension
            if (unit != null) {
                val local = requireNotNull(MemberSourceDefinition.rootValueContract(output)).unit as MetricValueContract.SlotDimension
                unitDimensions[local.slot] = listOf(requireNotNull(bindings.dimensions[unit.slot]) { "member unit dimension is not adopted" }.reference)
            }
        }
        val outputs = linkedMapOf<String, String>()
        val meanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val contracts = linkedMapOf<String, MetricValueContract?>()
        root.outputs.forEach { projection ->
            outputs[projection.outputAlias] = requireNotNull(adopted.outputSlots[projection.slot]) { "member projection references an undeclared formal output" }
            meanings[projection.outputAlias] = requireNotNull(formalMeanings[projection.slot])
            contracts[projection.outputAlias] = Calculation.rootValueContract(requireNotNull(required.outputs[projection.slot]))?.adopted(bindings)
        }
        val selection = SourceSelection(source)
        val condition = selection.condition(root.condition, adopted.outputSlots::get, { CalculationInstantiation.resolve(it, bindings, arguments) },
            { Calculation.rootValueContract(requireNotNull(required.outputs[it])) }, { owner.parameters[it]?.valueContract })
        val range = root.range?.let { selection.range((CalculationInstantiation.resolve(it, bindings, arguments) as Calculation.Range).window) }
        val clocks = selection.clocks(root.clocks.mapValues { (CalculationInstantiation.resolve(it.value, bindings, arguments) as Calculation.Clock).clock }, definition.clockSlots, true)
        val sourceArguments = MemberInvocationBinding.arguments(source, adopted.arguments, bindings, arguments)
        return Bound(outputs, meanings, contracts, unitDimensions, selection.usedSlots(outputs.values), condition, range, clocks, sourceArguments)
    }

    /** 公开查询直接选择获准来源输出，不引入算法形参或额外采用权限。 */
    fun publicSelection(root: QueryComputation.Members, source: MemberSource, mappings: (String) -> List<ScopedInputContract.DimensionRef>): Bound {
        val definition = source.definition.result
        val outputs = linkedMapOf<String, String>()
        val meanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val contracts = linkedMapOf<String, MetricValueContract?>()
        root.outputs.forEach { projection ->
            val output = requireNotNull(definition.outputs[projection.slot]) { "member output is not approved" }
            outputs[projection.outputAlias] = projection.slot
            contracts[projection.outputAlias] = MemberSourceDefinition.rootValueContract(output)
            meanings[projection.outputAlias] = when {
                projection is QueryComputation.MappedDimension -> {
                    require(projection.dimension in mappings(projection.slot)) { "member output differs from its exact semantic dimension mapping" }
                    QueryStageAnalysis.MappedMemberDimension(projection.dimension, output.role)
                }
                output is MemberSourceDefinition.DimensionOutput -> QueryStageAnalysis.MemberDimension(output.dimensionId, output.dimensionVersion.toString(), output.role)
                output is MemberSourceDefinition.ComputedDimensionOutput && source is MemberSource.Computed -> {
                    val actual = requireNotNull(source.bindings.dimensions[output.dimensionSlot])
                    QueryStageAnalysis.MappedMemberDimension(actual.reference, actual.role)
                }
                output !is MemberSourceDefinition.ScalarOutput -> QueryStageAnalysis.MemberContainer(MemberSourceDefinition.containerShape(output), output.role)
                else -> QueryStageAnalysis.MemberProperty(output.valueType, output.role)
            }
        }
        val units = TreeMap<String, List<ScopedInputContract.DimensionRef>>()
        definition.outputs.values.forEach { output ->
            val unit = MemberSourceDefinition.rootValueContract(output)?.unit as? MetricValueContract.SlotDimension
            if (unit != null) units[unit.slot] = mappings(unit.slot)
        }
        val selection = SourceSelection(source)
        val condition = selection.condition(root.condition, { it }, ::publicScalar, { MemberSourceDefinition.rootValueContract(requireNotNull(definition.outputs[it])) }, { throw IllegalArgumentException("public member conditions cannot contain arguments") })
        val range = root.range?.let(selection::range)
        val clocks = selection.clocks(root.clocks, definition.clockSlots, false)
        val sourceArguments = arguments(source, root.arguments, CalculationBindings.empty(), emptyMap())
        return Bound(outputs, meanings, contracts, units, selection.usedSlots(outputs.values), condition, range, clocks, sourceArguments)
    }

    fun relationSelection(exists: DimensionCondition.Exists, source: MemberSource.Projection, approvedClocks: Map<String, String>): Bound {
        val definition = source.definition.result
        val selection = SourceSelection(source, emptyList())
        val condition = selection.condition(exists.predicate, { it }, ::publicScalar, { MemberSourceDefinition.rootValueContract(requireNotNull(definition.outputs[it])) }, { throw IllegalArgumentException("public relation conditions cannot contain arguments") })
        val range = exists.range?.let(selection::range)
        val clocks = selection.clocks(exists.clocks, approvedClocks, false)
        return Bound(emptyMap(), emptyMap(), emptyMap(), emptyMap(), selection.usedSlots(emptyList()), condition, range, clocks, emptyMap())
    }
    private fun publicScalar(value: Calculation.Binding): Calculation.Scalar = value as? Calculation.Scalar ?: throw IllegalArgumentException("public member conditions require typed scalar values")

    /** 本次来源选择收集实际消费字段，各项绑定直接返回完成值。 */
    private class SourceSelection(private val source: MemberSource, identity: List<String> = source.definition.result.identitySlots) {
        private val used = identity.toMutableSet()
        fun condition(original: MemberCondition, slots: (String) -> String?, values: (Calculation.Binding) -> Calculation.Value,
            fields: (String) -> MetricValueContract?, parameters: (String) -> MetricValueContract?): MemberCondition {
            val definition = source.definition.result
            val actual = MemberCondition.bind(original, definition.filterOperators, { MemberSourceDefinition.scalar(requireNotNull(definition.outputs[it])).valueType }, slots, values, used)
            MemberCondition.requireParameterUnits(original, actual, { MemberSourceDefinition.rootValueContract(requireNotNull(definition.outputs[it])) }, slots, fields, parameters, emptyMap())
            return actual
        }
        fun range(value: TemporalWindow): TemporalWindow {
            val definition = source.definition.result
            val slot = requireNotNull(definition.timeSlot) { "member source has no temporal range slot" }
            require(MemberSourceDefinition.scalar(requireNotNull(definition.outputs[slot])).valueType.name == value.valueType.name) { "member range type differs from its source temporal output" }
            used.add(slot)
            return value
        }
        fun clocks(supplied: Map<String, TemporalClockBindings.Value>, approved: Map<String, String>, rejectDuplicatePositions: Boolean): Map<String, TemporalClockBindings.Value> = buildMap {
            supplied.forEach { (name, value) ->
                val slot = requireNotNull(approved[name]) { "member clock is not approved" }
                if (rejectDuplicatePositions) require(slot !in this) { "member clock maps the same interval more than once" }
                put(slot, value)
            }
        }
        fun usedSlots(outputs: Collection<String>): Set<String> {
            used.addAll(outputs)
            return used
        }
    }

    fun requireArguments(owner: Calculation, call: CalculationBindings.SourceCall, source: MemberSource, used: MutableSet<String>, sources: (MemberRef) -> MemberSource, mappings: DimensionMapping, declaration: Boolean) {
        requireNestedMembers(owner, call, source, used, sources, mappings, declaration)
        val parameters = (source as? MemberSource.Computed)?.remainingParameters() ?: emptyMap()
        require(parameters.keys.containsAll(call.arguments.keys)) { "member call references unknown or fixed source parameters" }
        parameters.forEach { (name, parameter) -> require(parameter.defaultValue != null || name in call.arguments) { "member call is missing a required source parameter: $name" } }
        call.arguments.forEach { (name, value) -> requireParameter(owner, requireNotNull(parameters[name]), value, used) }
    }
    private fun requireNestedMembers(owner: Calculation, call: CalculationBindings.SourceCall, source: MemberSource, used: MutableSet<String>, sources: (MemberRef) -> MemberSource, mappings: DimensionMapping, declaration: Boolean) {
        if (source !is MemberSource.Computed) { require(call.members.isEmpty() && call.relations.isEmpty()) { "event projections do not declare external member or relation inputs" }; return }
        val target = source.definition.calculation
        val actual = CalculationInstantiation.relationArguments(target, CalculationInstantiation.memberArguments(target, source.bindings, call.members), call.relations)
        call.members.forEach { (name, member) ->
            val formal = target.memberSlots.first { it.name == name }
            when (member) {
                is CalculationBindings.SourceCall -> {
                    require(formal.fixedSource == null || formal.fixedSource == member.source) { "nested member input differs from its fixed source reference" }
                    val nestedSource = sources(member.source)
                    requireMember(target, formal, actual, nestedSource, mappings)
                    requireArguments(owner, member, nestedSource, used, sources, mappings, declaration)
                }
                is CalculationBindings.LocalRef -> {
                    if (declaration) CalculationInstantiation.localInput(owner, member)
                    require(formal.fixedSource == null && formal.outputs.keys == member.outputSlots.keys) { "nested local member input must bind exactly its declared formal outputs" }
                }
                is CalculationBindings.InputParameter -> throw IllegalArgumentException("nested member parameter requires an actual source call or local reference")
            }
        }
        call.relations.forEach { (name, relation) ->
            val formal = target.relationSlots.first { it.name == name }
            require(relation is CalculationBindings.LocalRelation) { "nested relation parameter requires an actual local completed relation" }
            require(relation.outputSlots.keys == formal.outputs.map { it.name }.toSet()) { "nested local relation must bind exactly its declared formal outputs" }
            if (declaration) {
                val input = requireNotNull(owner.computation.children()[relation.inputId]) { "nested local relation input must belong to the owner root scope" }
                require(input.mode == "ROOT") { "nested local relation input must explicitly declare ROOT mode" }
            }
        }
    }
    private fun requireParameter(owner: Calculation, expected: Calculation.Parameter, value: Calculation.Binding, used: MutableSet<String>) {
        when (value) {
            is Calculation.Argument -> {
                val actual = requireNotNull(owner.parameters[value.name]) { "member call references an undeclared parent parameter" }
                used.add(value.name)
                require(expected.hasType(actual.kind, actual.valueType)) { "member call parameter type differs from its source declaration" }
                require((expected.valueContract == null) == (actual.valueContract == null)) { "member call parameter must preserve quantity or categorical meaning" }
                expected.valueContract?.let { MetricValueContract.requireMeasurementValue(it, requireNotNull(actual.valueContract)) }
                require(expected.allowsDimensions(actual.allowedDimensions)) { "member grouping parameter exceeds its source semantic dimensions" }
            }
            is Calculation.GroupingSlots -> {
                require(expected.kind == Calculation.ParameterKind.GROUPING) { "member grouping slots require a grouping source parameter" }
                value.slots.forEach { name -> require(expected.allowsDimension(owner.dimensionSlots.first { it.name == name })) { "member grouping slot differs from its source parameter" } }
            }
            is Calculation.Value -> {
                require(expected.hasType(value.parameterKind, value.valueType)) { "member source argument has an incorrect type" }
                expected.valueContract?.requireScalar(value as Calculation.Scalar)
            }
        }
    }
    fun arguments(source: MemberSource, supplied: Map<String, Calculation.Binding>, parent: CalculationBindings, parentArguments: Map<String, Calculation.Value>): Map<String, Calculation.Value> {
        val values = supplied.mapValues { CalculationInstantiation.resolve(it.value, parent, parentArguments) }
        return when (source) {
            is MemberSource.Computed -> CalculationInstantiation.arguments(source.definition.calculation.parameters, source.fixedArguments, values, source.bindings)
            is MemberSource.Projection -> { require(values.isEmpty()) { "event projection does not declare calculation parameters" }; emptyMap() }
        }
    }
}
