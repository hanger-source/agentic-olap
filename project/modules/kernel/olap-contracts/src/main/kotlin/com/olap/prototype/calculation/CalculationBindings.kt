package com.olap.prototype.calculation

import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.ScopedInputContract

/** 一次计算采用的精确维度、成员、分组范围和测量实例；不复制源表达式。 */
class CalculationBindings(dimensions: Map<String, Dimension>, members: Map<String, Member>, groupingCandidates: Map<String, List<Dimension>>, measurements: Map<String, MetricInstanceRef>, relations: Map<String, Relation>) {
    val dimensions: Map<String, Dimension> = java.util.Map.copyOf(dimensions)
    val members: Map<String, Member> = java.util.Map.copyOf(members)
    val measurements: Map<String, MetricInstanceRef> = java.util.Map.copyOf(measurements)
    val relations: Map<String, Relation> = java.util.Map.copyOf(relations)
    val groupingCandidates: Map<String, List<Dimension>> = java.util.Collections.unmodifiableMap(linkedMapOf<String, List<Dimension>>().apply {
        groupingCandidates.forEach { (name, values) ->
            ScopedInputContract.requireId(name, listOf("CalculationBindings", "groupingCandidates", name))
            require(values.distinct().size == values.size) { "grouping candidates repeat a binding" }
            require(values.map { it.reference }.distinct().size == values.size) { "grouping candidates contain an ambiguous semantic reference" }
            put(name, java.util.List.copyOf(values))
        }
    })
    init {
        this.measurements.keys.forEach { ScopedInputContract.requireId(it, listOf("CalculationBindings", "measurements", it)) }
        this.relations.keys.forEach { ScopedInputContract.requireId(it, listOf("CalculationBindings", "relations", it)) }
    }

    class Dimension(val reference: ScopedInputContract.DimensionRef, val role: String, mappingIds: List<String>) {
        val mappingIds: List<String> = java.util.List.copyOf(mappingIds)
        init {
            MetricDefinition.text(role, "dimension role")
            this.mappingIds.forEach { MetricDefinition.text(it, "dimension mapping id") }
            require(this.mappingIds.isNotEmpty() && this.mappingIds.distinct().size == this.mappingIds.size) { "dimension adoption requires explicit distinct mapping references" }
        }
        fun requireMeaning(dimensionId: String, dimensionVersion: String, constraints: List<Calculation.DimensionSlot>) {
            require(constraints.any { it.role == role && it.dimensionId == dimensionId && it.dimensionVersion.toString() == dimensionVersion }) { "bound dimension differs from its declared semantic dimension, version or role" }
        }
        override fun equals(other: Any?): Boolean = other is Dimension && reference == other.reference && role == other.role && mappingIds == other.mappingIds
        override fun hashCode(): Int = listOf(reference, role, mappingIds).hashCode()
    }

    sealed interface Member
    class SourceCall(val source: MemberRef, arguments: Map<String, Calculation.Binding>, outputSlots: Map<String, String>, members: Map<String, Member>, relations: Map<String, Relation>) : Member {
        val arguments: Map<String, Calculation.Binding> = java.util.Map.copyOf(arguments)
        val outputSlots: Map<String, String> = java.util.Map.copyOf(outputSlots)
        val members: Map<String, Member> = java.util.Map.copyOf(members)
        val relations: Map<String, Relation> = java.util.Map.copyOf(relations)
        init { this.arguments.keys.forEach { ScopedInputContract.requireId(it, listOf("CalculationBindings.SourceCall", "arguments", it)) } }
        override fun equals(other: Any?): Boolean = other is SourceCall && source == other.source && arguments == other.arguments && outputSlots == other.outputSlots && members == other.members && relations == other.relations
        override fun hashCode(): Int = listOf(source, arguments, outputSlots, members, relations).hashCode()
    }
    class LocalRef(val inputId: String, outputSlots: Map<String, String>) : Member {
        val outputSlots: Map<String, String> = java.util.Map.copyOf(outputSlots)
        init { ScopedInputContract.requireId(inputId, listOf("CalculationBindings.LocalRef", "inputId")) }
        override fun equals(other: Any?): Boolean = other is LocalRef && inputId == other.inputId && outputSlots == other.outputSlots
        override fun hashCode(): Int = listOf(inputId, outputSlots).hashCode()
    }
    class InputParameter : Member { override fun equals(other: Any?): Boolean = other is InputParameter; override fun hashCode(): Int = 0 }

    sealed interface Relation
    class LocalRelation(val inputId: String, outputSlots: Map<String, String>) : Relation {
        val outputSlots: Map<String, String> = java.util.Map.copyOf(outputSlots)
        init {
            ScopedInputContract.requireId(inputId, listOf("CalculationBindings.LocalRelation", "inputId"))
            this.outputSlots.forEach { (slot, symbol) -> com.olap.prototype.query.PostOperationContract.requireAlias(slot); com.olap.prototype.query.PostOperationContract.requireAlias(symbol) }
        }
        override fun equals(other: Any?): Boolean = other is LocalRelation && inputId == other.inputId && outputSlots == other.outputSlots
        override fun hashCode(): Int = listOf(inputId, outputSlots).hashCode()
    }
    class RelationParameter : Relation { override fun equals(other: Any?): Boolean = other is RelationParameter; override fun hashCode(): Int = 0 }

    fun require(definition: MetricDefinition, dimensionFacts: (Dimension, List<Calculation.DimensionSlot>) -> Unit,
        sources: (MemberRef) -> MemberSource, outputMappings: MemberInvocationBinding.DimensionMapping,
        definitions: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance) {
        when (definition) {
            is MetricDefinition.FactMeasure -> requireNoSlots()
            is MetricCalculation -> validate(definition.calculation, dimensionFacts, sources, outputMappings, definitions, instances, true)
        }
    }
    fun require(calculation: Calculation, dimensionFacts: (Dimension, List<Calculation.DimensionSlot>) -> Unit,
        sources: (MemberRef) -> MemberSource, outputMappings: MemberInvocationBinding.DimensionMapping,
        definitions: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance) =
        validate(calculation, dimensionFacts, sources, outputMappings, definitions, instances, true)

    fun requireBound(definition: MetricDefinition, dimensionFacts: (Dimension, List<Calculation.DimensionSlot>) -> Unit,
        sources: (MemberRef) -> MemberSource, outputMappings: MemberInvocationBinding.DimensionMapping,
        definitions: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance) {
        when (definition) {
            is MetricDefinition.FactMeasure -> requireNoSlots()
            is MetricCalculation -> validate(definition.calculation, dimensionFacts, sources, outputMappings, definitions, instances, false)
        }
    }
    fun requireBound(calculation: Calculation, dimensionFacts: (Dimension, List<Calculation.DimensionSlot>) -> Unit,
        sources: (MemberRef) -> MemberSource, outputMappings: MemberInvocationBinding.DimensionMapping,
        definitions: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance) =
        validate(calculation, dimensionFacts, sources, outputMappings, definitions, instances, false)

    private fun requireNoSlots() {
        require(dimensions.isEmpty() && members.isEmpty() && groupingCandidates.isEmpty() && measurements.isEmpty() && relations.isEmpty()) { "fact measure does not declare calculation slots or grouping parameters" }
    }

    private fun validate(calculation: Calculation, dimensionFacts: (Dimension, List<Calculation.DimensionSlot>) -> Unit,
        sources: (MemberRef) -> MemberSource, outputMappings: MemberInvocationBinding.DimensionMapping,
        definitions: (MetricRef) -> MetricDefinition, instances: (MetricInstanceRef) -> MetricInstance, declaration: Boolean, inheritedInputs: Boolean = false) {
        require(dimensions.keys == calculation.dimensionSlots.map { it.name }.toSet() && members.keys == calculation.memberSlots.map { it.name }.toSet() &&
            groupingCandidates.keys == calculation.parameters.filterValues { it.kind == Calculation.ParameterKind.GROUPING }.keys &&
            measurements.keys == calculation.measurementSlots.map { it.name }.toSet() && relations.keys == calculation.relationSlots.map { it.name }.toSet()) { "calculation binding must bind all and only declared slots and grouping parameters" }
        calculation.dimensionSlots.forEach { dimensionFacts(requireNotNull(dimensions[it.name]), listOf(it)) }
        groupingCandidates.forEach { (name, values) -> values.forEach { dimensionFacts(it, requireNotNull(calculation.parameters[name]).allowedDimensions) } }
        val used = calculation.parameterReferences().toMutableSet()
        calculation.memberSlots.forEach { slot ->
            when (val member = members[slot.name]) {
                is SourceCall -> {
                    require(slot.fixedSource == null || slot.fixedSource == member.source) { "member input differs from its fixed source declaration" }
                    val source = sources(member.source)
                    MemberInvocationBinding.requireMember(calculation, slot, this, source, outputMappings)
                    if (!inheritedInputs) MemberInvocationBinding.requireArguments(calculation, member, source, used, sources, outputMappings, declaration, calculation.computation)
                }
                is LocalRef -> {
                    require(slot.fixedSource == null) { "fixed member sources cannot be supplied as a local or external input" }
                    require(member.outputSlots.keys == slot.outputs.keys) { "local member input must map all and only formal outputs" }
                    if (declaration && !inheritedInputs) CalculationInstantiation.localInput(calculation, member)
                }
                is InputParameter -> Unit
                null -> error("member slot was not bound")
            }
        }
        fun directMembers(scope: Calculation.Scope) {
            val root = scope.root as? Calculation.Members
            val target = root?.target as? Calculation.MemberSource
            if (root != null && target != null) {
                val source = sources(target.source)
                MemberInvocationBinding.requireSource(calculation, root, this, source, outputMappings)
                MemberInvocationBinding.requireArguments(calculation,
                    SourceCall(target.source, root.arguments, emptyMap(), root.members, root.relations), source, used, sources, outputMappings, declaration, scope)
            }
            (scope.root as? Calculation.Compute)?.let { application ->
                require(application.bindings is Calculation.ParentSlots) { "reusable templates forward declared parent slots" }
                val nested = CalculationInstantiation.computeBindings(application, this)
                // 转发绑定的实参与词法位置始终属于父owner，与声明或实际校验模式无关。
                nested.validate(application.calculation, dimensionFacts, sources, outputMappings, definitions, instances,
                    declaration = declaration, inheritedInputs = true)
            }
            scope.children().values.forEach { directMembers(it.computation) }
        }
        directMembers(calculation.computation)
        if (declaration) require(used == calculation.parameters.keys) { "calculation parameters must all be consumed: ${calculation.parameters.keys} != $used" }
        calculation.measurementSlots.forEach { slot ->
            val instance = instances(requireNotNull(measurements[slot.name]))
            slot.require(instance, definitions(instance.definition), this)
        }
        calculation.relationSlots.forEach { slot ->
            when (val relation = relations.getValue(slot.name)) {
                is LocalRelation -> {
                    require(relation.outputSlots.keys == slot.outputs.map { it.name }.toSet()) { "local relation input must map all and only formal outputs" }
                    if (declaration && !inheritedInputs) {
                        val input = requireNotNull(calculation.computation.children()[relation.inputId]) { "local relation input must belong to the calculation root scope" }
                        require(input.mode == "ROOT") { "local relation input must explicitly declare ROOT mode" }
                    }
                }
                is RelationParameter -> Unit
            }
        }
    }
    override fun equals(other: Any?): Boolean = other is CalculationBindings && dimensions == other.dimensions && members == other.members && groupingCandidates == other.groupingCandidates && measurements == other.measurements && relations == other.relations
    override fun hashCode(): Int = listOf(dimensions, members, groupingCandidates, measurements, relations).hashCode()
    companion object { fun empty(): CalculationBindings = CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
}
