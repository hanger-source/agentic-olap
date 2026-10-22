package com.olap.prototype.calculation

import com.olap.prototype.contract.InputContractException
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberShape
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow
import java.math.BigDecimal
import java.util.Collections
import java.util.TreeMap

/** 共同计算体的形参、作用域与关系操作；结果合同由使用该计算的资产拥有。 */
class Calculation(dimensionSlots: List<DimensionSlot>, memberSlots: List<MemberSlot>, measurementSlots: List<MeasurementSlot>,
    relationSlots: List<RelationSlot>, parameters: Map<String, Parameter>, val computation: Scope) {
    val dimensionSlots: List<DimensionSlot> = java.util.List.copyOf(dimensionSlots)
    val memberSlots: List<MemberSlot> = java.util.List.copyOf(memberSlots)
    val measurementSlots: List<MeasurementSlot> = java.util.List.copyOf(measurementSlots)
    val relationSlots: List<RelationSlot> = java.util.List.copyOf(relationSlots)
    val parameters: Map<String, Parameter> = Collections.unmodifiableMap(LinkedHashMap(parameters))
    private val referencedParameters: Set<String> = CalculationValidation.validate(this)
    fun parameterReferences(): Set<String> = referencedParameters

    fun declaredDimensions(): Map<String, DimensionSlot> = Collections.unmodifiableMap(linkedMapOf<String, DimensionSlot>().apply {
        dimensionSlots.forEach { put("dimension.${it.name}", it) }
        parameters.forEach { (name, parameter) -> parameter.allowedDimensions.forEach { put("parameter.$name.${it.name}", it) } }
        measurementSlots.forEach { measurement ->
            measurement.observationSlots.forEach { put("measurement.${measurement.name}.observation.${it.name}", it) }
            measurement.parameters.forEach { (name, parameter) -> parameter.allowedDimensions.forEach { put("measurement.${measurement.name}.parameter.$name.${it.name}", it) } }
        }
    })

    data class DimensionSlot(val name: String, val dimensionId: String, val dimensionVersion: Long, val role: String) {
        init { ScopedInputContract.requireId(name); MetricDefinition.text(dimensionId, "dimensionId"); MetricDefinition.version(dimensionVersion); MetricDefinition.text(role, "dimension role") }
    }

    /** 实际采用的测量契约；观察键仍由完整计算分析证明。 */
    class MeasurementSlot(val name: String, parameters: Map<String, Parameter>, observationSlots: List<DimensionSlot>, groupingArguments: List<String>, val valueContract: MetricValueContract) {
        val parameters: Map<String, Parameter> = Collections.unmodifiableMap(LinkedHashMap(parameters))
        val observationSlots: List<DimensionSlot> = java.util.List.copyOf(observationSlots)
        val groupingArguments: List<String> = java.util.List.copyOf(groupingArguments)
        init {
            ScopedInputContract.requireId(name)
            this.parameters.keys.forEach { ScopedInputContract.requireId(it) }
            require(this.observationSlots.map { it.name }.distinct().size == this.observationSlots.size) { "measurement observation slots must be distinct" }
            require(this.groupingArguments.distinct().size == this.groupingArguments.size) { "measurement grouping arguments must be distinct" }
            this.groupingArguments.forEach { require(this.parameters[it]?.kind == ParameterKind.GROUPING) { "measurement observations require a declared grouping parameter: $it" } }
        }
        fun require(instance: MetricInstance, definition: MetricDefinition, parent: CalculationBindings) {
            require(parameters == instance.remainingParameters(definition)) { "measurement parameters differ in names, types, defaults or required arguments: $name" }
            MetricValueContract.requireMeasurementValue(valueContract.adopted(parent), definition.valueContract.adopted(instance.bindings))
        }
        override fun equals(other: Any?): Boolean = other is MeasurementSlot && name == other.name && parameters == other.parameters && observationSlots == other.observationSlots && groupingArguments == other.groupingArguments && valueContract == other.valueContract
        override fun hashCode(): Int = listOf(name, parameters, observationSlots, groupingArguments, valueContract).hashCode()
    }

    sealed interface MemberOutput
    sealed interface ScalarMemberOutput : MemberOutput { val valueContract: MetricValueContract? }
    data class MemberContainerProperty(val role: String, val shape: SetOutputContract.Shape) : MemberOutput {
        init { MetricDefinition.text(role, "member container property role"); SetOutputContract.requireSourceContainer(shape) }
    }
    data class MemberDimension(val dimensionSlot: String, override val valueContract: MetricValueContract?) : ScalarMemberOutput {
        init { ScopedInputContract.requireId(dimensionSlot) }
    }
    data class MemberProperty(val role: String, val valueType: ScalarValueType, override val valueContract: MetricValueContract?) : ScalarMemberOutput {
        init { MetricDefinition.text(role, "member property role"); require(valueContract == null || valueType == ScalarValueType.NUMBER) { "only quantitative numeric member properties declare value contracts" } }
    }
    class MemberSlot(val name: String, val entity: String, val role: String, val shape: MemberShape, outputs: Map<String, MemberOutput>,
        val fixedSource: MemberRef?) {
        val outputs: Map<String, MemberOutput> = java.util.Map.copyOf(outputs)
        init {
            ScopedInputContract.requireId(name); MetricDefinition.text(entity, "member entity"); MetricDefinition.text(role, "member role")
            require(this.outputs.isNotEmpty()) { "member output slots must be explicit and distinct" }
        }
        override fun equals(other: Any?): Boolean = other is MemberSlot && name == other.name && entity == other.entity && role == other.role && shape == other.shape && outputs == other.outputs && fixedSource == other.fixedSource
        override fun hashCode(): Int = listOf(name, entity, role, shape, outputs, fixedSource).hashCode()
    }

    class RelationSlot(val name: String, outputs: List<SetOutputContract>,
        keys: List<String>?) {
        val outputs: List<SetOutputContract> = java.util.List.copyOf(outputs)
        val keys: List<String>? = keys?.let { java.util.List.copyOf(it) }
        init {
            ScopedInputContract.requireId(name)
            require(this.outputs.isNotEmpty() && this.outputs.map { it.name }.distinct().size == this.outputs.size) { "relation formal requires explicit distinct outputs" }
            require(this.keys == null || this.keys.distinct().size == this.keys.size && this.outputs.map { it.name }.containsAll(this.keys)) { "relation keys must select distinct declared outputs" }
        }
        override fun equals(other: Any?): Boolean = other is RelationSlot && name == other.name && outputs == other.outputs && keys == other.keys
        override fun hashCode(): Int = listOf(name, outputs, keys).hashCode()
    }

    enum class ParameterKind { SCALAR, RANGE, CLOCK, GROUPING, CONDITION }
    class Parameter(val kind: ParameterKind, val valueType: String, allowedDimensions: List<DimensionSlot>, val defaultValue: Value?,
        val valueContract: MetricValueContract?) {
        val allowedDimensions: List<DimensionSlot> = java.util.List.copyOf(allowedDimensions)
        init {
            MetricDefinition.text(valueType, "parameter valueType")
            require(kind != ParameterKind.CONDITION || valueType == "SOURCE_CONDITION") { "condition parameters require SOURCE_CONDITION valueType" }
            require(kind == ParameterKind.GROUPING || this.allowedDimensions.isEmpty()) { "dimension candidates only belong to grouping parameters" }
            if (valueContract != null) require(kind == ParameterKind.SCALAR && valueType == "NUMBER" && valueContract.unit is MetricValueContract.Fixed &&
                valueContract.unitEquations.isEmpty() && (valueContract.result !is MetricValueContract.Decimal || valueContract.result.rounding == MetricValueContract.Rounding.NONE)) {
                "quantity parameter must be a lossless numeric scalar with one fixed unit and no output equations"
            }
            require(defaultValue == null || defaultValue.parameterKind == kind && defaultValue.valueType == valueType) { "parameter default differs from its declared type" }
            if (defaultValue != null && valueContract != null) valueContract.requireScalar(defaultValue as Scalar)
        }
        fun hasType(actualKind: ParameterKind, actualValueType: String): Boolean = kind == actualKind && valueType == actualValueType
        fun allowsDimension(actual: DimensionSlot): Boolean = allowedDimensions.any { it.dimensionId == actual.dimensionId && it.dimensionVersion == actual.dimensionVersion && it.role == actual.role }
        fun allowsDimensions(actual: Collection<DimensionSlot>): Boolean = actual.all { allowsDimension(it) }
        override fun equals(other: Any?): Boolean = other is Parameter && kind == other.kind && valueType == other.valueType && allowedDimensions == other.allowedDimensions && defaultValue == other.defaultValue && valueContract == other.valueContract
        override fun hashCode(): Int = listOf(kind, valueType, allowedDimensions, defaultValue, valueContract).hashCode()
    }

    sealed interface Binding
    data class Argument(val name: String) : Binding { init { ScopedInputContract.requireId(name) } }
    class GroupingSlots(slots: List<String>) : Binding {
        val slots: List<String> = java.util.List.copyOf(slots)
        init { this.slots.forEach { ScopedInputContract.requireId(it) } }
        override fun equals(other: Any?): Boolean = other is GroupingSlots && slots == other.slots
        override fun hashCode(): Int = slots.hashCode()
    }
    sealed interface Value : Binding { val parameterKind: ParameterKind; val valueType: String }
    class Scalar(val scalarType: ScalarValueType, value: String) : Value {
        val value: String = if (scalarType == ScalarValueType.NUMBER) BigDecimal(value).stripTrailingZeros().toPlainString() else value
        init { decode(scalarType, this.value) }
        override val parameterKind: ParameterKind get() = ParameterKind.SCALAR
        override val valueType: String get() = scalarType.name
        fun jdbcValue(): Any = decode(scalarType, value)
        fun sameValue(other: Scalar): Boolean {
            if (scalarType != other.scalarType) return false
            val left = jdbcValue(); val right = other.jdbcValue()
            return if (left is BigDecimal && right is BigDecimal) left.compareTo(right) == 0 else left == right
        }
        override fun equals(other: Any?): Boolean = other is Scalar && scalarType == other.scalarType && value == other.value
        override fun hashCode(): Int = listOf(scalarType, value).hashCode()
        companion object {
            private fun decode(type: ScalarValueType, value: String): Any = when (type) {
                ScalarValueType.NUMBER -> BigDecimal(value).let { if (it.scale() < 0) it.setScale(0) else it }
                ScalarValueType.BOOLEAN -> { require(value == "true" || value == "false") { "boolean scalar requires true or false" }; value.toBoolean() }
                else -> type.parameter(value)
            }
        }
    }
    data class Range(val window: TemporalWindow) : Value {
        override val parameterKind: ParameterKind get() = ParameterKind.RANGE
        override val valueType: String get() = window.valueType.name
    }
    data class Clock(val clock: TemporalClockBindings.Value) : Value {
        override val parameterKind: ParameterKind get() = ParameterKind.CLOCK
        override val valueType: String get() = clock.valueType.name
    }
    data class Condition(val condition: DimensionCondition) : Value {
        override val parameterKind: ParameterKind get() = ParameterKind.CONDITION
        override val valueType: String get() = "SOURCE_CONDITION"
    }
    class Grouping(dimensions: List<ScopedInputContract.DimensionRef>) : Value {
        val dimensions: List<ScopedInputContract.DimensionRef> = java.util.List.copyOf(dimensions)
        init { require(this.dimensions.distinct().size == this.dimensions.size) { "grouping repeats a dimension" } }
        override val parameterKind: ParameterKind get() = ParameterKind.GROUPING
        override val valueType: String get() = "SEMANTIC_DIMENSIONS"
        override fun equals(other: Any?): Boolean = other is Grouping && dimensions == other.dimensions
        override fun hashCode(): Int = dimensions.hashCode()
    }

    sealed interface CallTarget
    data class Fixed(val metric: MetricRef) : CallTarget
    data class Measurement(val slot: String) : CallTarget { init { ScopedInputContract.requireId(slot) } }
    class Call(val callId: String, val target: CallTarget, val outputAlias: String, dimensionSlots: Map<String, String>, memberSlots: Map<String, String>, measurementSlots: Map<String, String>, relationSlots: Map<String, String>, arguments: Map<String, Binding>, val sourceCondition: Binding) {
        val dimensionSlots: Map<String, String> = java.util.Map.copyOf(dimensionSlots)
        val memberSlots: Map<String, String> = java.util.Map.copyOf(memberSlots)
        val measurementSlots: Map<String, String> = java.util.Map.copyOf(measurementSlots)
        val relationSlots: Map<String, String> = java.util.Map.copyOf(relationSlots)
        val arguments: Map<String, Binding> = Collections.unmodifiableMap(LinkedHashMap(arguments))
        init {
            ScopedInputContract.requireId(callId); MetricDefinition.text(outputAlias, "outputAlias")
            if (target is Measurement) {
                mapOf("dimensionSlots" to this.dimensionSlots, "measurementSlots" to this.measurementSlots).forEach { (field, bindings) ->
                    if (bindings.isNotEmpty()) throw InputContractException.violation(InputContractException.Rule.MEASUREMENT_BINDING_OVERRIDE,
                        listOf(field), "MEASUREMENT target requires an empty $field map; the adopted instance owns these bindings",
                        "EMPTY_MAP", "NONEMPTY_MAP")
                }
            }
            require(sourceCondition is Argument || sourceCondition is Condition) { "call source conditions require a condition value or declared condition argument" }
        }
        companion object {
            fun contract(): Map<String, Any> = mapOf("targetRules" to mapOf("MEASUREMENT" to mapOf(
                "emptyMaps" to listOf("dimensionSlots", "measurementSlots"),
                "bindingOwner" to "EXACT_ADOPTED_MEASUREMENT_INSTANCE",
                "parentBindings" to "REJECT_EVEN_IF_IDENTICAL_TO_INSTANCE_BINDINGS",
                "violationCode" to InputContractException.Rule.MEASUREMENT_BINDING_OVERRIDE.code)))
        }
        override fun equals(other: Any?): Boolean = other is Call && callId == other.callId && target == other.target && outputAlias == other.outputAlias && dimensionSlots == other.dimensionSlots && memberSlots == other.memberSlots && measurementSlots == other.measurementSlots && relationSlots == other.relationSlots && arguments == other.arguments && sourceCondition == other.sourceCondition
        override fun hashCode(): Int = listOf(callId, target, outputAlias, dimensionSlots, memberSlots, measurementSlots, relationSlots, arguments, sourceCondition).hashCode()
    }

    sealed interface Root
    data class Relation(val slot: String) : Root { init { ScopedInputContract.requireId(slot) } }
    class ObjectMeasure(val measurement: Scope, val objects: Scope, roles: List<ObjectRole>) : Root {
        val roles: List<ObjectRole> = java.util.List.copyOf(roles)
        init { require(this.roles.isNotEmpty() && this.roles.map { it.target }.distinct().size == this.roles.size) { "object qualification requires explicit distinct measurement roles" } }
        override fun equals(other: Any?): Boolean = other is ObjectMeasure && measurement == other.measurement && objects == other.objects && roles == other.roles
        override fun hashCode(): Int = listOf(measurement, objects, roles).hashCode()
    }
    class ObjectRole(objectKeys: List<String>, val target: ObjectTarget) {
        val objectKeys: List<String> = symbols(objectKeys, false)
        override fun equals(other: Any?): Boolean = other is ObjectRole && objectKeys == other.objectKeys && target == other.target
        override fun hashCode(): Int = listOf(objectKeys, target).hashCode()
    }
    sealed interface ObjectTarget
    data class EventRole(val role: String) : ObjectTarget { init { MetricDefinition.text(role, "fact entity role") } }
    class RecordOutputs(val memberSlot: String, keys: List<String>) : ObjectTarget {
        val keys: List<String> = symbols(keys, false)
        init { ScopedInputContract.requireId(memberSlot) }
        override fun equals(other: Any?): Boolean = other is RecordOutputs && memberSlot == other.memberSlot && keys == other.keys
        override fun hashCode(): Int = listOf(memberSlot, keys).hashCode()
    }
    enum class NoMatch { PRESERVE_NULL, ZERO_WHEN_ABSENT }
    enum class PopulationKind { BASE, UNION, INTERSECTION }
    class AlignmentPopulation(val kind: PopulationKind, inputIds: List<String>) {
        val inputIds: List<String> = symbols(inputIds, false)
        init { this.inputIds.forEach { ScopedInputContract.requireId(it) }; require(kind != PopulationKind.BASE || this.inputIds.size == 1) { "base population requires exactly one input" } }
        override fun equals(other: Any?): Boolean = other is AlignmentPopulation && kind == other.kind && inputIds == other.inputIds
        override fun hashCode(): Int = listOf(kind, inputIds).hashCode()
    }
    data class AlignedValue(val symbol: String, val outputAlias: String, val noMatch: NoMatch) {
        init { MetricDefinition.text(symbol, "completed measurement output"); PostOperationContract.requireAlias(outputAlias) }
    }
    class AlignedInput(val computation: Scope, keys: Map<String, String>, values: List<AlignedValue>) {
        val keys: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(keys))
        val values: List<AlignedValue> = java.util.List.copyOf(values)
        init {
            this.keys.forEach { (name, symbol) -> if (groupingFamily(name) == null) PostOperationContract.requireAlias(name); MetricDefinition.text(symbol, "observation key output") }
            require(this.keys.values.distinct().size == this.keys.size) { "observation key outputs must be distinct" }
        }
        override fun equals(other: Any?): Boolean = other is AlignedInput && computation == other.computation && keys == other.keys && values == other.values
        override fun hashCode(): Int = listOf(computation, keys, values).hashCode()
    }
    class AlignMeasurements(inputs: Map<String, AlignedInput>, observationKeys: List<String>, val population: AlignmentPopulation) : Root {
        val inputs: Map<String, AlignedInput> = Collections.unmodifiableMap(LinkedHashMap(inputs))
        val observationKeys: List<String> = symbols(observationKeys, true)
        init {
            this.inputs.keys.forEach { ScopedInputContract.requireId(it) }
            require(this.inputs.isNotEmpty() && this.inputs.keys.containsAll(population.inputIds)) { "alignment population must select declared completed inputs" }
            val names = this.observationKeys.toMutableSet()
            this.inputs.values.forEach { input ->
                require(input.keys.keys == this.observationKeys.toSet()) { "each aligned input must map the complete observation identity" }
                input.values.forEach { require(names.add(it.outputAlias)) { "aligned output aliases must be distinct" } }
            }
        }
        override fun equals(other: Any?): Boolean = other is AlignMeasurements && inputs == other.inputs && observationKeys == other.observationKeys && population == other.population
        override fun hashCode(): Int = listOf(inputs, observationKeys, population).hashCode()
    }
    sealed interface ObservationUnit
    class RawRows : ObservationUnit { override fun equals(other: Any?): Boolean = other is RawRows; override fun hashCode(): Int = 0 }
    class ObjectsBy(keys: List<String>) : ObservationUnit {
        val keys: List<String> = symbols(keys, false)
        override fun equals(other: Any?): Boolean = other is ObjectsBy && keys == other.keys
        override fun hashCode(): Int = keys.hashCode()
    }
    sealed interface ObservationStatistic { val outputAlias: String }
    data class Reduction(val aggregate: PostOperationContract.ResultAggregate) : ObservationStatistic { override val outputAlias: String get() = aggregate.outputAlias }
    data class WeightedMean(val value: String, val weight: String, override val outputAlias: String, val outputDisplayName: String,
        val numeratorContract: MetricValueContract, val weightSumContract: MetricValueContract, val resultContract: MetricValueContract,
        val multiplyEquation: String?, val divideEquation: String?, val nullPolicy: String) : ObservationStatistic {
        init {
            MetricDefinition.text(value, "weighted observation value"); MetricDefinition.text(weight, "actual observation weight"); PostOperationContract.requireAlias(outputAlias)
            MetricDefinition.text(outputDisplayName, "weighted statistic display name")
            require(nullPolicy in setOf("IGNORE_PAIR", "PROPAGATE")) { "weighted statistics require an explicit paired NULL policy" }
        }
    }
    class ObservationStatistics(val observations: Scope, val unit: ObservationUnit, outerKeys: List<String>, statistics: List<ObservationStatistic>) : Root {
        val outerKeys: List<String> = symbols(outerKeys, true)
        val statistics: List<ObservationStatistic> = java.util.List.copyOf(statistics)
        init {
            require(this.statistics.isNotEmpty()) { "observation statistics must be explicit" }
            val names = this.outerKeys.toMutableSet()
            this.statistics.forEach { require(names.add(it.outputAlias)) { "statistic outputs must be distinct" } }
        }
        override fun equals(other: Any?): Boolean = other is ObservationStatistics && observations == other.observations && unit == other.unit && outerKeys == other.outerKeys && statistics == other.statistics
        override fun hashCode(): Int = listOf(observations, unit, outerKeys, statistics).hashCode()
    }
    class Measures(calls: List<Call>, groupSlots: List<String>, groupingArguments: List<String>, val timeSlot: String?, val range: Binding?, clocks: Map<String, Binding>,
        val population: Population, val groupingLevels: GroupingLevels?) : Root {
        val calls: List<Call> = java.util.List.copyOf(calls)
        val groupSlots: List<String> = java.util.List.copyOf(groupSlots)
        val groupingArguments: List<String> = java.util.List.copyOf(groupingArguments)
        val clocks: Map<String, Binding> = java.util.Map.copyOf(clocks)
        init {
            require(this.calls.isNotEmpty()) { "measure selection requires calls" }
            val ids = linkedSetOf<String>(); val aliases = linkedSetOf<String>()
            this.calls.forEach { unique(ids, it.callId); unique(aliases, it.outputAlias) }
            require(ids.containsAll(population.callIds)) { "population references undeclared calls" }
            require((timeSlot == null) == (range == null)) { "time slot and range must be declared together" }
        }
        override fun equals(other: Any?): Boolean = other is Measures && calls == other.calls && groupSlots == other.groupSlots && groupingArguments == other.groupingArguments && timeSlot == other.timeSlot && range == other.range && clocks == other.clocks && population == other.population && groupingLevels == other.groupingLevels
        override fun hashCode(): Int = listOf(calls, groupSlots, groupingArguments, timeSlot, range, clocks, population, groupingLevels).hashCode()
    }
    class Population(callIds: List<String>) {
        val callIds: List<String> = java.util.List.copyOf(callIds)
        init { require(this.callIds.isNotEmpty() && this.callIds.distinct().size == this.callIds.size) { "population requires explicit distinct completed instances" } }
        override fun equals(other: Any?): Boolean = other is Population && callIds == other.callIds
        override fun hashCode(): Int = callIds.hashCode()
    }
    data class MemberProjection(val outputAlias: String, val slot: String) { init { MetricDefinition.text(outputAlias, "member outputAlias"); MetricDefinition.text(slot, "member output slot") } }
    class Members(val callId: String, val slot: String, outputs: List<MemberProjection>, val range: Binding?, clocks: Map<String, Binding>, val condition: MemberCondition) : Root {
        val outputs: List<MemberProjection> = java.util.List.copyOf(outputs)
        val clocks: Map<String, Binding> = java.util.Map.copyOf(clocks)
        init {
            ScopedInputContract.requireId(callId); ScopedInputContract.requireId(slot)
            require(this.outputs.isNotEmpty() && this.outputs.map { it.outputAlias }.distinct().size == this.outputs.size) { "member root requires distinct output aliases in declared order" }
        }
        override fun equals(other: Any?): Boolean = other is Members && callId == other.callId && slot == other.slot && outputs == other.outputs && range == other.range && clocks == other.clocks && condition == other.condition
        override fun hashCode(): Int = listOf(callId, slot, outputs, range, clocks, condition).hashCode()
    }
    data class Input(val inputId: String) : Root { init { ScopedInputContract.requireId(inputId) } }
    enum class SetKind { UNION, INTERSECT, EXCEPT }
    class SetProjection(val inputId: String, symbols: List<String>) {
        val symbols: List<String> = java.util.List.copyOf(symbols)
        init { ScopedInputContract.requireId(inputId) }
        override fun equals(other: Any?): Boolean = other is SetProjection && inputId == other.inputId && symbols == other.symbols
        override fun hashCode(): Int = listOf(inputId, symbols).hashCode()
    }
    class SetCombination(val operation: SetKind, val all: Boolean, inputs: List<SetProjection>, outputSlots: List<SetOutputContract>) : Root {
        val inputs: List<SetProjection> = java.util.List.copyOf(inputs)
        val outputSlots: List<SetOutputContract> = java.util.List.copyOf(outputSlots)
        init { require(this.inputs.size >= 2 && this.outputSlots.isNotEmpty() && this.outputSlots.map { it.name }.distinct().size == this.outputSlots.size && this.inputs.all { it.symbols.size == this.outputSlots.size }) { "set combination requires explicit equal-width input projections and distinct output slots" } }
        override fun equals(other: Any?): Boolean = other is SetCombination && operation == other.operation && all == other.all && inputs == other.inputs && outputSlots == other.outputSlots
        override fun hashCode(): Int = listOf(operation, all, inputs, outputSlots).hashCode()
    }
    data class InputScope(val mode: String, val computation: Scope) { init { ScopedInputContract.requireMode(mode) } }
    class Scope(val root: Root, inputs: Map<String, InputScope>, stages: List<PostOperationContract.ValidatedOperation>) {
        val inputs: Map<String, InputScope> = Collections.unmodifiableMap(TreeMap(inputs))
        val stages: List<PostOperationContract.ValidatedOperation> = java.util.List.copyOf(stages)
        init {
            this.inputs.keys.forEach { ScopedInputContract.requireId(it) }
            require(this.inputs.keys.intersect(semanticInputs(root).keys).isEmpty()) { "declared inputs conflict with semantic construct children" }
        }
        fun children(): Map<String, InputScope> = Collections.unmodifiableMap(LinkedHashMap(inputs).apply { semanticInputs(root).forEach { (id, value) -> put(id, InputScope("ROOT", value)) } })
        override fun equals(other: Any?): Boolean = other is Scope && root == other.root && inputs == other.inputs && stages == other.stages
        override fun hashCode(): Int = listOf(root, inputs, stages).hashCode()
    }
    class CallSite(path: List<String>, val call: Call) {
        val path: List<String> = java.util.List.copyOf(path)
        override fun equals(other: Any?): Boolean = other is CallSite && path == other.path && call == other.call
        override fun hashCode(): Int = listOf(path, call).hashCode()
    }
    fun callSites(): List<CallSite> = buildList {
        fun collect(scope: Scope, path: List<String>) {
            (scope.root as? Measures)?.calls?.forEach { add(CallSite(path + it.callId, it)) }
            scope.children().forEach { (id, child) -> collect(child.computation, path + id) }
        }
        collect(computation, emptyList())
    }
    override fun equals(other: Any?): Boolean = other is Calculation && dimensionSlots == other.dimensionSlots && memberSlots == other.memberSlots && measurementSlots == other.measurementSlots && relationSlots == other.relationSlots && parameters == other.parameters && computation == other.computation
    override fun hashCode(): Int = listOf(dimensionSlots, memberSlots, measurementSlots, relationSlots, parameters, computation).hashCode()

    companion object {
        fun groupingFamily(symbol: String): String? = if (symbol.startsWith("\$group:")) ScopedInputContract.requireId(symbol.removePrefix("\$group:")) else null
        fun rootValueContract(output: MemberOutput): MetricValueContract? = (output as? ScalarMemberOutput)?.valueContract
        fun semanticInputs(root: Root): Map<String, Scope> = when (root) {
            is ObjectMeasure -> linkedMapOf("measurement" to root.measurement, "objects" to root.objects)
            is AlignMeasurements -> Collections.unmodifiableMap(root.inputs.mapValues { it.value.computation })
            is ObservationStatistics -> mapOf("observations" to root.observations)
            else -> emptyMap()
        }
        private fun symbols(values: List<String>, empty: Boolean): List<String> {
            values.forEach { MetricDefinition.text(it, "observation symbol") }
            require((empty || values.isNotEmpty()) && values.distinct().size == values.size) { "observation symbols must be explicit and distinct" }
            return java.util.List.copyOf(values)
        }
        private fun unique(names: MutableSet<String>, name: String) { MetricDefinition.text(name, "name"); require(names.add(name)) { "duplicate calculation identity: $name" } }
    }
}
