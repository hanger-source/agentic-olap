package com.olap.prototype.calculation

import com.olap.prototype.calculation.Calculation.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*

/** 共同计算声明的闭合验证；不解析目录、来源授权或实际调用绑定。 */
internal object CalculationValidation {
    fun validate(calculation: Calculation): Set<String> {
        val names = linkedSetOf<String>()
        fun unique(name: String) {
            MetricDefinition.text(name, "name")
            require(names.add(name)) { "duplicate calculation identity: $name" }
        }
        calculation.dimensionSlots.forEach { unique(it.name) }
        calculation.memberSlots.forEach { unique(it.name) }
        calculation.measurementSlots.forEach { unique(it.name) }
        calculation.relationSlots.forEach { unique(it.name) }
        calculation.parameters.keys.forEach(::unique)
        val dimensions = calculation.dimensionSlots.associateBy { it.name }
        calculation.memberSlots.forEach { member -> requireMemberUnits(member, dimensions) }
        calculation.measurementSlots.forEach { measurement ->
            require(measurement.observationSlots.all { it in calculation.dimensionSlots }) { "measurement observation requires an exact declared parent dimension slot" }
            requireUnit(measurement.valueContract, dimensions)
            measurement.parameters.values.forEach { requireDefinitionValue(it.defaultValue) }
        }
        calculation.relationSlots.forEachIndexed { index, relation -> relation.outputs.forEachIndexed { output, slot ->
            requireRelationShape(slot.shape, dimensions, listOf("relationSlots", index, "outputs", output, "shape"))
        } }
        calculation.parameters.values.forEach { requireDefinitionValue(it.defaultValue) }
        val references = ParameterReferences(calculation).validate()
        requireScopeUnits(calculation.computation, dimensions, listOf("computation"))
        return references
    }

    private fun requireDefinitionValue(value: Value?) {
        require(value !is Condition || value.condition is DimensionCondition.Unrestricted) { "algorithm condition values must be supplied through declared arguments, not adopted analysis dimensions or relation filters" }
    }

    private fun requireRelationShape(shape: SetOutputContract.Shape, dimensions: Map<String, DimensionSlot>, path: List<Any>) {
        requireShape(shape, dimensions, path)
        when (shape) {
            is SetOutputContract.Quantity -> require(shape.valueContract.unitEquations.isEmpty()) { "relation formal quantities cannot declare unit conversion equations" }
            is SetOutputContract.Row -> shape.fields.forEachIndexed { index, field -> requireRelationShape(field, dimensions, path + listOf("fields", index)) }
            is SetOutputContract.Array -> requireRelationShape(shape.element, dimensions, path + "element")
            else -> Unit
        }
    }

    private fun requireMemberUnits(member: MemberSlot, dimensions: Map<String, DimensionSlot>) {
        member.outputs.values.forEach { output ->
            if (output is MemberDimension) require(output.dimensionSlot in dimensions) { "member output requires a declared internal semantic dimension slot" }
            val value = Calculation.rootValueContract(output) ?: return@forEach
            require(value.unitEquations.isEmpty() && (value.result !is MetricValueContract.Decimal || value.result.rounding == MetricValueContract.Rounding.NONE)) {
                "member formal inputs require lossless value binding without local conversion equations"
            }
            when (val unit = value.unit) {
                is MetricValueContract.Fixed -> Unit
                is MetricValueContract.SlotDimension -> {
                    val dimension = requireNotNull(dimensions[unit.slot]) { "member unit requires a declared algorithm dimension slot" }
                    require(dimension.dimensionId == unit.axis.dimensionId && dimension.dimensionVersion == unit.axis.dimensionVersion && dimension.role == unit.axis.role &&
                        member.outputs.values.any { it is MemberDimension && it.dimensionSlot == unit.slot && it.valueContract == null }) {
                        "member unit axis requires its categorical dimension output in the same formal member slot"
                    }
                }
                else -> throw IllegalArgumentException("member formal units must use fixed identities or algorithm dimension slots")
            }
        }
    }

    private fun requireUnit(value: MetricValueContract, dimensions: Map<String, DimensionSlot>) {
        val unit = value.unit
        if (unit is MetricValueContract.Fixed) return
        require(unit is MetricValueContract.SlotDimension) { "algorithm stage quantities must use fixed identities or semantic dimension slots" }
        val dimension = requireNotNull(dimensions[unit.slot]) { "stage unit dimension slot is not declared" }
        require(dimension.dimensionId == unit.axis.dimensionId && dimension.dimensionVersion == unit.axis.dimensionVersion && dimension.role == unit.axis.role) { "stage quantity axis differs from its declared semantic dimension slot" }
    }

    private fun requireShape(shape: SetOutputContract.Shape, dimensions: Map<String, DimensionSlot>, path: List<Any>) {
        when (shape) {
            is SetOutputContract.Dimension -> throw InputContractException.violation(InputContractException.Rule.DIMENSION_SLOT_REQUIRED,
                path + "kind", "algorithm set dimensions must use semantic slots before adoption", "DIMENSION_SLOT", "DIMENSION")
            is SetOutputContract.DimensionSlot -> require(shape.slot in dimensions) { "algorithm set output references an undeclared dimension slot" }
            is SetOutputContract.Quantity -> requireUnit(shape.valueContract, dimensions)
            is SetOutputContract.Row -> shape.fields.forEachIndexed { index, field -> requireShape(field, dimensions, path + listOf("fields", index)) }
            is SetOutputContract.Array -> requireShape(shape.element, dimensions, path + "element")
            else -> Unit
        }
    }

    private fun requireScopeUnits(scope: Scope, dimensions: Map<String, DimensionSlot>, path: List<Any>) {
        when (val root = scope.root) {
            is SetCombination -> root.outputSlots.forEachIndexed { index, output -> requireShape(output.shape, dimensions, path + listOf("root", "outputSlots", index, "shape")) }
            is ObservationStatistics -> root.statistics.forEach { statistic ->
                when (statistic) {
                    is Reduction -> (statistic.aggregate.aggregation as? ResultAggregation.Numeric)?.valueContract?.let { requireUnit(it, dimensions) }
                    is WeightedMean -> listOf(statistic.numeratorContract, statistic.weightSumContract, statistic.resultContract).forEach { requireUnit(it, dimensions) }
                }
            }
            else -> Unit
        }
        scope.stages.forEach { stage -> PostOperationTraversal.valueContracts(stage).forEach { requireUnit(it, dimensions) } }
        scope.children().forEach { (id, child) -> requireScopeUnits(child.computation, dimensions, path + listOf("inputs", id, "computation")) }
    }

    /** 一份声明共用形参消费集合与scope计费；不消费尚未采用的成员/测量实参。 */
    private class ParameterReferences(private val calculation: Calculation) {
        private val dimensions = calculation.dimensionSlots.map { it.name }.toSet()
        private val members = calculation.memberSlots.map { it.name }.toSet()
        private val measurements = calculation.measurementSlots.map { it.name }.toSet()
        private val relations = calculation.relationSlots.map { it.name }.toSet()
        private val parameters = calculation.parameters
        private val used = linkedSetOf<String>()
        private var count = 0

        fun validate(): Set<String> {
            scope(calculation.computation, 0)
            return java.util.Collections.unmodifiableSet(used)
        }

        private fun scope(scope: Scope, depth: Int) {
            require(depth <= ScopedInputContract.MAX_DEPTH && ++count <= ScopedInputContract.MAX_INPUTS) { "metric computation exceeds scope budget" }
            when (val root = scope.root) {
                is Measures -> measures(root)
                is Members -> members(root)
                is Relation -> require(root.slot in relations) { "unknown relation source slot" }
                is Input -> requireInput(scope, root.inputId)
                is SetCombination -> root.inputs.forEach { requireInput(scope, it.inputId) }
                is ObjectMeasure -> root.roles.forEach { role ->
                    val target = role.target
                    if (target is RecordOutputs) require(target.memberSlot in members) { "record qualification refers to an unknown member formal" }
                }
                is AlignMeasurements, is ObservationStatistics -> Unit
            }
            rootSelections(scope.root).forEach(::groupingSelection)
            scope.stages.forEach(::stage)
            scope.children().values.forEach { this.scope(it.computation, depth + 1) }
        }

        private fun measures(root: Measures) {
            require(dimensions.containsAll(root.groupSlots)) { "unknown calculation grouping slot" }
            require(root.timeSlot == null || root.timeSlot in dimensions) { "unknown calculation time dimension slot" }
            root.groupingArguments.forEach { argument(it, ParameterKind.GROUPING) }
            binding(root.range, ParameterKind.RANGE)
            root.clocks.values.forEach { binding(it, ParameterKind.CLOCK) }
            root.calls.forEach { call ->
                require(dimensions.containsAll(call.dimensionSlots.values) && members.containsAll(call.memberSlots.values) && measurements.containsAll(call.measurementSlots.values) && relations.containsAll(call.relationSlots.values) &&
                    (call.target !is Measurement || call.target.slot in measurements)) { "child call maps an unknown parent slot" }
                binding(call.sourceCondition, ParameterKind.CONDITION)
                (call.sourceCondition as? Condition)?.let(::requireDefinitionValue)
                call.arguments.values.forEach { value ->
                    when (value) {
                        is Argument -> { require(value.name in parameters) { "unknown parent argument: ${value.name}" }; used.add(value.name) }
                        is GroupingSlots -> require(dimensions.containsAll(value.slots)) { "grouping maps an unknown parent dimension slot" }
                        is Grouping -> require(value.dimensions.isEmpty()) { "metric definitions bind semantic grouping slots, not adopted analysis dimensions" }
                        is Condition -> requireDefinitionValue(value)
                        else -> Unit
                    }
                }
            }
        }

        private fun members(root: Members) {
            require(root.slot in members) { "unknown member source slot" }
            binding(root.range, ParameterKind.RANGE)
            root.clocks.values.forEach { binding(it, ParameterKind.CLOCK) }
            condition(root.condition)
        }

        private fun condition(condition: MemberCondition) {
            when (condition) {
                is MemberCondition.Unrestricted -> Unit
                is MemberCondition.Predicate -> condition.values.forEach { value ->
                    when (value) { is Argument -> argument(value.name, ParameterKind.SCALAR); is Scalar -> Unit; else -> throw IllegalArgumentException("member conditions accept only typed scalar values or scalar arguments") }
                }
                is MemberCondition.All -> condition.operands.forEach(::condition)
                is MemberCondition.Any -> condition.operands.forEach(::condition)
                is MemberCondition.Not -> this.condition(condition.operand)
            }
        }

        private fun stage(stage: PostOperationContract.ValidatedOperation) {
            PostOperationTraversal.bindings(stage).forEach { binding(it.binding, it.kind) }
            PostOperationTraversal.groupingSelections(stage).forEach(::groupingSelection)
            PostOperationTraversal.expressions(stage).forEach { expression ->
                ResultExpressionTree.groupingSelections(expression).forEach(::groupingSelection)
                ResultExpressionTree.nodes(expression).filterIsInstance<ResultExpression.ResultArgument>().forEach { node ->
                    require(parameters[node.name]?.hasType(ParameterKind.SCALAR, node.valueType.name) == true) { "stage argument differs from its scalar parameter declaration: ${node.name}" }
                    used.add(node.name)
                }
            }
        }
        private fun groupingSelection(symbol: String) { Calculation.groupingFamily(symbol)?.let { argument(it, ParameterKind.GROUPING) } }
        private fun argument(name: String, kind: ParameterKind) { require(parameters[name]?.kind == kind) { "argument kind differs from consumption: $name" }; used.add(name) }
        private fun binding(binding: Binding?, kind: ParameterKind) {
            when (binding) {
                null -> Unit
                is Argument -> argument(binding.name, kind)
                is Value -> require(binding.parameterKind == kind) { "binding kind differs from consumption" }
                is GroupingSlots -> throw IllegalArgumentException("binding kind differs from consumption")
            }
        }
        private fun requireInput(scope: Scope, id: String) { require(id in scope.inputs) { "root references undeclared input: $id" } }
        private fun rootSelections(root: Root): List<String> = when (root) {
            is AlignMeasurements -> root.observationKeys + root.inputs.values.flatMap { it.keys.keys + it.keys.values }
            is ObservationStatistics -> root.outerKeys + ((root.unit as? ObjectsBy)?.keys ?: emptyList())
            is ObjectMeasure -> root.roles.flatMap { it.objectKeys }
            else -> emptyList()
        }
    }
}
