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
        val target = root.target as? Calculation.MemberParameter ?: throw IllegalArgumentException("formal member binding requires a member parameter reference")
        val adopted = requireNotNull(bindings.members[target.slot]) { "member slot is not adopted" } as CalculationBindings.SourceCall
        val required = owner.memberSlots.first { it.name == target.slot }
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

    /** 精确来源引用采用实际输出事实；父计算只提供明确的参数和维度绑定。 */
    fun sourceSelection(root: Calculation.Members, source: MemberSource, owner: Calculation?, bindings: CalculationBindings,
        arguments: Map<String, Calculation.Value>, mappings: (String) -> List<ScopedInputContract.DimensionRef>, dimensionMappings: DimensionMapping): Bound {
        require(root.target is Calculation.MemberSource) { "exact member selection requires a member source reference" }
        val definition = source.definition.result
        val outputs = linkedMapOf<String, String>()
        val meanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val contracts = linkedMapOf<String, MetricValueContract?>()
        root.outputs.forEach { projection ->
            val output = requireNotNull(definition.outputs[projection.slot]) { "member output is not approved" }
            outputs[projection.outputAlias] = projection.slot
            contracts[projection.outputAlias] = MemberSourceDefinition.rootValueContract(output)
            meanings[projection.outputAlias] = when {
                projection.dimension != null -> {
                    val dimension = sourceDimension(projection, source, output, owner, bindings, mappings, dimensionMappings)
                    QueryStageAnalysis.MappedMemberDimension(dimension, output.role)
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
        val condition = selection.condition(root.condition, { it }, { CalculationInstantiation.resolve(it, bindings, arguments) },
            { MemberSourceDefinition.rootValueContract(requireNotNull(definition.outputs[it])) }, { owner?.parameters?.get(it)?.valueContract })
        val range = root.range?.let { selection.range((CalculationInstantiation.resolve(it, bindings, arguments) as Calculation.Range).window) }
        val clocks = selection.clocks(root.clocks.mapValues { (_, value) -> (CalculationInstantiation.resolve(value, bindings, arguments) as Calculation.Clock).clock }, definition.clockSlots, false)
        val sourceArguments = MemberInvocationBinding.arguments(source, root.arguments, bindings, arguments)
        return Bound(outputs, meanings, contracts, units, selection.usedSlots(outputs.values), condition, range, clocks, sourceArguments)
    }

    fun requireSource(owner: Calculation, root: Calculation.Members, bindings: CalculationBindings, source: MemberSource, dimensionMappings: DimensionMapping) {
        require((root.target as? Calculation.MemberSource)?.source == MemberRef(source.id, source.version)) { "direct member source differs from its exact dependency" }
        root.outputs.forEach { projection ->
            val output = requireNotNull(source.definition.result.outputs[projection.slot]) { "direct member projection references an unavailable source output" }
            if (projection.dimension != null) sourceDimension(projection, source, output, owner, bindings, { emptyList() }, dimensionMappings)
        }
    }

    private fun sourceDimension(projection: Calculation.MemberProjection, source: MemberSource, output: MemberSourceDefinition.Output,
        owner: Calculation?, bindings: CalculationBindings, mappings: (String) -> List<ScopedInputContract.DimensionRef>, dimensionMappings: DimensionMapping): ScopedInputContract.DimensionRef =
        when (val reference = requireNotNull(projection.dimension)) {
            is Calculation.DimensionParameter -> {
                val declared = requireNotNull(owner).dimensionSlots.first { it.name == reference.slot }
                val adopted = bindings.dimensions.getValue(reference.slot)
                adopted.requireMeaning(declared.dimensionId, declared.dimensionVersion.toString(), listOf(declared))
                require(output.role == declared.role && dimensionMappings.matches(source, adopted, output)) { "member output differs from its exact parent dimension binding and role" }
                adopted.reference
            }
            is Calculation.PublishedDimension -> reference.reference.also { require(it in mappings(projection.slot)) { "member output differs from its exact semantic dimension mapping" } }
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

    fun requireArguments(owner: Calculation, call: CalculationBindings.SourceCall, source: MemberSource, used: MutableSet<String>, sources: (MemberRef) -> MemberSource, mappings: DimensionMapping, declaration: Boolean, localScope: Calculation.Scope) {
        requireNestedMembers(owner, call, source, used, sources, mappings, declaration, localScope)
        val parameters = (source as? MemberSource.Computed)?.remainingParameters() ?: emptyMap()
        require(parameters.keys.containsAll(call.arguments.keys)) { "member call references unknown or fixed source parameters" }
        parameters.forEach { (name, parameter) -> require(parameter.defaultValue != null || name in call.arguments) { "member call is missing a required source parameter: $name" } }
        call.arguments.forEach { (name, value) -> CalculationInstantiation.requireParameterBinding(owner, requireNotNull(parameters[name]), value, used) }
    }
    private fun requireNestedMembers(owner: Calculation, call: CalculationBindings.SourceCall, source: MemberSource, used: MutableSet<String>, sources: (MemberRef) -> MemberSource, mappings: DimensionMapping, declaration: Boolean, localScope: Calculation.Scope) {
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
                    requireArguments(owner, member, nestedSource, used, sources, mappings, declaration, localScope)
                }
                is CalculationBindings.LocalRef -> {
                    if (declaration) require(localScope.children()[member.inputId]?.mode == "ROOT") { "nested local member input must identify an explicit ROOT in its declaring lexical scope" }
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
                val input = requireNotNull(localScope.children()[relation.inputId]) { "nested local relation input must belong to its declaring lexical scope" }
                require(input.mode == "ROOT") { "nested local relation input must explicitly declare ROOT mode" }
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
