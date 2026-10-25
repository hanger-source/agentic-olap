package com.olap.prototype.calculation

import com.olap.prototype.calculation.Calculation.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import java.math.BigDecimal
import java.util.Collections

/** 精确调用的实参、采用和阶段替换；不解析物理来源。 */
object CalculationInstantiation {
    fun budget(depth: Int, count: IntArray) { require(depth <= ScopedInputContract.MAX_DEPTH && ++count[0] <= ScopedInputContract.MAX_INPUTS) { "calculation exceeds explicit scope/instance budget" } }

    fun arguments(parameters: Map<String, Parameter>, fixedArguments: Map<String, Value>, supplied: Map<String, Value>, bindings: CalculationBindings): Map<String, Value> {
        requireFixedArguments(parameters, fixedArguments, bindings)
        require(fixedArguments.keys.intersect(supplied.keys).isEmpty()) { "query arguments cannot override frozen fixed arguments" }
        val combined = fixedArguments + supplied
        require(parameters.keys.containsAll(combined.keys)) { "unknown metric calculation arguments" }
        return Collections.unmodifiableMap(linkedMapOf<String, Value>().apply {
            parameters.forEach { (name, declaration) ->
                val value = if (name in combined) combined[name] else declaration.defaultValue
                requireArgument(name, declaration, value, bindings)
                put(name, requireNotNull(value))
            }
        })
    }
    fun remainingParameters(calculation: Calculation, bindings: CalculationBindings, fixedArguments: Map<String, Value>): Map<String, Parameter> {
        requireFixedArguments(calculation.parameters, fixedArguments, bindings)
        return Collections.unmodifiableMap(LinkedHashMap(calculation.parameters).apply { fixedArguments.keys.forEach { remove(it) } })
    }
    fun remainingGroupingCandidates(calculation: Calculation, bindings: CalculationBindings, fixedArguments: Map<String, Value>): Map<String, List<ScopedInputContract.DimensionRef>> =
        Collections.unmodifiableMap(linkedMapOf<String, List<ScopedInputContract.DimensionRef>>().apply {
            remainingParameters(calculation, bindings, fixedArguments).forEach { (name, parameter) ->
                if (parameter.kind == ParameterKind.GROUPING) put(name, requireNotNull(bindings.groupingCandidates[name]) { "grouping parameter binding is missing" }.map { it.reference })
            }
        })
    fun remainingMemberParameters(calculation: Calculation, bindings: CalculationBindings): Map<String, MemberSlot> =
        Collections.unmodifiableMap(linkedMapOf<String, MemberSlot>().apply {
            calculation.memberSlots.forEach { slot -> if (requireNotNull(bindings.members[slot.name]) { "member binding is missing" } is CalculationBindings.InputParameter) put(slot.name, slot) }
        })
    fun remainingRelationParameters(calculation: Calculation, bindings: CalculationBindings): Map<String, RelationSlot> =
        Collections.unmodifiableMap(linkedMapOf<String, RelationSlot>().apply {
            calculation.relationSlots.forEach { slot -> if (requireNotNull(bindings.relations[slot.name]) { "relation binding is missing" } is CalculationBindings.RelationParameter) put(slot.name, slot) }
        })
    fun relationArguments(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Relation>): CalculationBindings = mergeRelations(calculation, bindings, supplied, false)
    fun relationBindings(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Relation>): CalculationBindings = mergeRelations(calculation, bindings, supplied, true)
    private fun mergeRelations(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Relation>, declaration: Boolean): CalculationBindings {
        require(supplied.keys == remainingRelationParameters(calculation, bindings).keys) { "relation arguments must fill all and only declared external relation parameters" }
        if (!declaration) require(supplied.values.none { it is CalculationBindings.RelationParameter }) { "relation arguments require an actual local input" }
        return CalculationBindings(bindings.dimensions, bindings.members, bindings.groupingCandidates, bindings.measurements, bindings.relations + supplied)
    }
    fun memberArguments(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Member>): CalculationBindings = mergeMembers(calculation, bindings, supplied, false)
    fun memberBindings(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Member>): CalculationBindings = mergeMembers(calculation, bindings, supplied, true)
    private fun mergeMembers(calculation: Calculation, bindings: CalculationBindings, supplied: Map<String, CalculationBindings.Member>, declaration: Boolean): CalculationBindings {
        require(supplied.keys == remainingMemberParameters(calculation, bindings).keys) { "member arguments must fill all and only declared external member parameters" }
        if (!declaration) require(supplied.values.none { it is CalculationBindings.InputParameter }) { "member arguments require an actual source call or local input" }
        return CalculationBindings(bindings.dimensions, bindings.members + supplied, bindings.groupingCandidates, bindings.measurements, bindings.relations)
    }
    fun localInput(calculation: Calculation, local: CalculationBindings.LocalRef): InputScope {
        val input = requireNotNull(calculation.computation.children()[local.inputId]) { "frozen local member binding must reference its calculation's own declared input" }
        require(input.mode == "ROOT") { "frozen local member bindings require an explicitly declared ROOT input" }
        return input
    }

    class ComputeApplication(val bindings: CalculationBindings, arguments: Map<String, Value>) {
        val arguments: Map<String, Value> = java.util.Map.copyOf(arguments)
    }

    fun computeBindings(root: Compute, parent: CalculationBindings): CalculationBindings = when (val adoption = root.bindings) {
        is ExplicitBindings -> relationArguments(root.calculation,
            memberArguments(root.calculation, adoption.bindings, adoption.members), adoption.relations)
        is ParentSlots -> forwardedBindings(root.calculation, adoption.dimensionSlots, adoption.memberSlots,
            adoption.measurementSlots, adoption.relationSlots, root.arguments, parent)
    }

    fun compute(root: Compute, parent: Calculation?, parentBindings: CalculationBindings, parentArguments: Map<String, Value>): ComputeApplication {
        if (root.bindings is ParentSlots) require(parent != null) { "parent slot application requires an enclosing parameterized calculation" }
        val adopted = when (val adoption = root.bindings) {
            is ExplicitBindings -> relationArguments(root.calculation, memberArguments(root.calculation, adoption.bindings,
                resolveMembers(adoption.members, parentBindings, parentArguments)), adoption.relations)
            is ParentSlots -> {
                val forwarded = computeBindings(root, parentBindings)
                CalculationBindings(forwarded.dimensions, resolveMembers(forwarded.members, parentBindings, parentArguments),
                    forwarded.groupingCandidates, forwarded.measurements, forwarded.relations)
            }
        }
        val supplied = root.arguments.mapValues { resolve(it.value, parentBindings, parentArguments) }
        val values = arguments(root.calculation.parameters, emptyMap(), supplied, adopted)
        val bound = CalculationBindings(adopted.dimensions, resolveMembers(adopted.members, adopted, values), adopted.groupingCandidates,
            adopted.measurements, adopted.relations)
        return ComputeApplication(bound, values)
    }

    private fun forwardedBindings(calculation: Calculation?, dimensionSlots: Map<String, String>, memberSlots: Map<String, String>,
        measurementSlots: Map<String, String>, relationSlots: Map<String, String>, arguments: Map<String, Binding>, parent: CalculationBindings): CalculationBindings {
        val dimensions = dimensionSlots.mapValues { requireNotNull(parent.dimensions[it.value]) { "nested dimension slot is not adopted" } }
        val memberFormals = calculation?.memberSlots?.associateBy { it.name } ?: emptyMap()
        val members = memberSlots.mapValues { (slot, parentSlot) ->
            val formal = requireNotNull(memberFormals[slot]) { "nested member slot is not declared" }
            val actual = requireNotNull(parent.members[parentSlot]) { "nested member slot is not adopted" }
            fun outputs(values: Map<String, String>): Map<String, String> = formal.outputs.mapValues { (name, _) ->
                requireNotNull(values[name]) { "nested member output is not supplied: $name" }
            }
            when (actual) {
                is CalculationBindings.SourceCall -> CalculationBindings.SourceCall(actual.source, actual.arguments, outputs(actual.outputSlots), actual.members, actual.relations)
                is CalculationBindings.LocalRef -> CalculationBindings.LocalRef(actual.inputId, outputs(actual.outputSlots))
                is CalculationBindings.InputParameter -> actual
            }
        }
        val measurements = measurementSlots.mapValues { requireNotNull(parent.measurements[it.value]) { "nested measurement slot is not adopted" } }
        val candidates = linkedMapOf<String, List<CalculationBindings.Dimension>>()
        calculation?.parameters?.forEach { (name, parameter) -> if (parameter.kind == ParameterKind.GROUPING) {
            candidates[name] = when (val binding = arguments[name] ?: parameter.defaultValue) {
                is Argument -> requireNotNull(parent.groupingCandidates[binding.name]) { "parent grouping candidates are missing" }
                is GroupingSlots -> binding.slots.map { requireNotNull(parent.dimensions[it]) { "grouping slot is not adopted" } }
                is Grouping -> { require(binding.dimensions.isEmpty()) { "nested definitions bind grouping through parent semantic slots" }; emptyList() }
                else -> throw IllegalArgumentException("nested grouping parameter requires a declared grouping argument or parent dimension slots")
            }
        } }
        return CalculationBindings(dimensions, members, candidates, measurements, forwardedRelations(relationSlots, parent.relations, calculation))
    }

    private fun forwardedRelations(slots: Map<String, String>, parent: Map<String, CalculationBindings.Relation>, calculation: Calculation?): Map<String, CalculationBindings.Relation> {
        val formals = calculation?.relationSlots?.associateBy { it.name } ?: emptyMap()
        return Collections.unmodifiableMap(slots.mapValues { (slot, parentSlot) ->
            val formal = requireNotNull(formals[slot]) { "nested relation slot is not declared" }
            when (val actual = requireNotNull(parent[parentSlot]) { "nested relation slot is not bound" }) {
                is CalculationBindings.LocalRelation -> CalculationBindings.LocalRelation(actual.inputId, formal.outputs.associate { output ->
                    output.name to requireNotNull(actual.outputSlots[output.name]) { "nested relation output is not supplied: ${output.name}" }
                })
                is CalculationBindings.RelationParameter -> actual
            }
        })
    }
    fun resolveMembers(members: Map<String, CalculationBindings.Member>, parent: CalculationBindings, arguments: Map<String, Value>): Map<String, CalculationBindings.Member> {
        val count = intArrayOf(0)
        fun resolveLevel(values: Map<String, CalculationBindings.Member>, depth: Int): Map<String, CalculationBindings.Member> {
            budget(depth, count)
            return Collections.unmodifiableMap(linkedMapOf<String, CalculationBindings.Member>().apply {
                values.forEach { (name, input) ->
                    val actual = when (input) {
                        is CalculationBindings.SourceCall -> CalculationBindings.SourceCall(input.source, input.arguments.mapValues { resolve(it.value, parent, arguments) }, input.outputSlots, resolveLevel(input.members, depth + 1), input.relations)
                        is CalculationBindings.LocalRef, is CalculationBindings.InputParameter -> input
                    }
                    put(name, actual)
                }
            })
        }
        return resolveLevel(members, 0)
    }
    fun requireFixedArguments(parameters: Map<String, Parameter>, fixed: Map<String, Value>, bindings: CalculationBindings) {
        require(parameters.keys.containsAll(fixed.keys)) { "fixed arguments reference an undeclared parameter" }
        fixed.forEach { (name, value) ->
            requireArgument(name, requireNotNull(parameters[name]), value, bindings)
            require(value !is Condition || value.condition is DimensionCondition.Unrestricted) {
                "fixed condition arguments cannot retain publication filter grants; supply source conditions at invocation and express lasting rules in member or calculation definitions"
            }
        }
    }
    private fun requireArgument(name: String, declaration: Parameter, value: Value?, bindings: CalculationBindings) {
        require(value != null && declaration.hasType(value.parameterKind, value.valueType)) { "missing or incorrectly typed metric argument: $name" }
        if (value is Grouping) {
            val allowed = requireNotNull(bindings.groupingCandidates[name]) { "grouping parameter adoption is missing" }
            require(allowed.map { it.reference }.containsAll(value.dimensions)) { "metric grouping argument is outside frozen adoption candidates: $name" }
        }
        declaration.valueContract?.requireScalar(value as Scalar)
    }
    fun requireParameterBinding(owner: Calculation, expected: Parameter, value: Binding, used: MutableSet<String>) {
        when (value) {
            is Argument -> {
                val actual = requireNotNull(owner.parameters[value.name]) { "call references an undeclared parent parameter" }
                used.add(value.name)
                require(expected.hasType(actual.kind, actual.valueType)) { "call parameter type differs from its target declaration" }
                require((expected.valueContract == null) == (actual.valueContract == null)) { "call parameter must preserve quantity or categorical meaning" }
                expected.valueContract?.let { MetricValueContract.requireMeasurementValue(it, requireNotNull(actual.valueContract)) }
                require(expected.allowsDimensions(actual.allowedDimensions)) { "call grouping parameter exceeds its target semantic dimensions" }
            }
            is GroupingSlots -> {
                require(expected.kind == ParameterKind.GROUPING) { "grouping slots require a grouping target parameter" }
                value.slots.forEach { name -> require(expected.allowsDimension(owner.dimensionSlots.first { it.name == name })) { "grouping slot differs from its target parameter" } }
            }
            is Value -> {
                require(expected.hasType(value.parameterKind, value.valueType)) { "call argument has an incorrect type" }
                expected.valueContract?.requireScalar(value as Scalar)
            }
        }
    }
    fun grouping(root: Measures, bindings: CalculationBindings, arguments: Map<String, Value>, aliases: (ScopedInputContract.DimensionRef) -> String): Map<String, CalculationBindings.Dimension> =
        Collections.unmodifiableMap(linkedMapOf<String, CalculationBindings.Dimension>().apply {
            root.grouping.filterIsInstance<DimensionParameter>().forEach { put(it.slot, requireNotNull(bindings.dimensions[it.slot]) { "internal grouping slot is not adopted" }) }
            root.groupingArguments.forEach { name ->
                (arguments[name] as Grouping).dimensions.forEach { reference ->
                    val candidates = requireNotNull(bindings.groupingCandidates[name]) { "grouping parameter binding is missing" }.filter { it.reference == reference }
                    require(candidates.size == 1) { "grouping argument requires one exact dimension binding" }
                    require(putIfAbsent(aliases(reference), candidates.single()) == null) { "calculation grouping aliases conflict" }
                }
            }
        })

    fun groupingReferences(root: Measures, bindings: CalculationBindings, arguments: Map<String, Value>, aliases: (ScopedInputContract.DimensionRef) -> String): Map<String, ScopedInputContract.DimensionRef> {
        val adopted = grouping(root, bindings, arguments, aliases)
        return Collections.unmodifiableMap(linkedMapOf<String, ScopedInputContract.DimensionRef>().apply {
            root.grouping.forEach { reference ->
                val entry = when (reference) {
                    is DimensionParameter -> reference.slot to adopted.getValue(reference.slot).reference
                    is PublishedDimension -> aliases(reference.reference) to reference.reference
                }
                require(putIfAbsent(entry.first, entry.second) == null) { "calculation grouping aliases conflict" }
            }
            adopted.filterKeys { name -> root.grouping.none { it is DimensionParameter && it.slot == name } }.forEach { (name, dimension) ->
                require(putIfAbsent(name, dimension.reference) == null) { "calculation grouping aliases conflict" }
            }
        })
    }

    class Child(val metric: MetricRef, arguments: Map<String, Value>, val bindings: CalculationBindings, fixedArguments: Map<String, Value>, val measurementSlot: MeasurementSlot?) {
        val arguments: Map<String, Value> = java.util.Map.copyOf(arguments)
        val fixedArguments: Map<String, Value> = java.util.Map.copyOf(fixedArguments)
        override fun equals(other: Any?): Boolean = other is Child && metric == other.metric && arguments == other.arguments && bindings == other.bindings && fixedArguments == other.fixedArguments && measurementSlot == other.measurementSlot
        override fun hashCode(): Int = listOf(metric, arguments, bindings, fixedArguments, measurementSlot).hashCode()
    }
    data class Observation(val dimension: ScopedInputContract.DimensionRef, val role: String) { init { MetricDefinition.text(role, "observation role") } }
    class ObservationGrain(fixed: List<Observation>, grouping: List<Observation>) {
        val fixed: List<Observation> = java.util.List.copyOf(fixed)
        val grouping: List<Observation> = java.util.List.copyOf(grouping)
        init { val dimensions = (this.fixed + this.grouping).map { it.dimension }; require(dimensions.distinct().size == dimensions.size) { "measurement observations repeat an exact semantic dimension" } }
        fun dimensions(): Map<ScopedInputContract.DimensionRef, String> = Collections.unmodifiableMap(linkedMapOf<ScopedInputContract.DimensionRef, String>().apply { (fixed + grouping).forEach { put(it.dimension, it.role) } })
        fun atLevel(selected: Collection<ScopedInputContract.DimensionRef>): ObservationGrain = ObservationGrain(fixed, grouping.filter { it.dimension in selected })
        override fun equals(other: Any?): Boolean = other is ObservationGrain && fixed == other.fixed && grouping == other.grouping
        override fun hashCode(): Int = listOf(fixed, grouping).hashCode()
    }
    fun observations(slot: MeasurementSlot, parent: CalculationBindings, target: CalculationBindings, arguments: Map<String, Value>): ObservationGrain {
        val fixed = slot.observationSlots.map { observation ->
            val dimension = requireNotNull(parent.dimensions[observation.name]) { "measurement observation slot is not bound" }
            require(dimension.role == observation.role) { "measurement observation role differs from its declaration" }
            Observation(dimension.reference, dimension.role)
        }
        val dynamic = slot.groupingArguments.flatMap { name ->
            val selected = requireNotNull(arguments[name]) { "measurement grouping argument is not bound" } as Grouping
            val candidates = requireNotNull(target.groupingCandidates[name]) { "measurement grouping candidates are missing" }
            selected.dimensions.map { reference ->
                val matching = candidates.filter { it.reference == reference }
                require(matching.size == 1) { "measurement grouping requires one exact dimension binding" }
                Observation(matching.single().reference, matching.single().role)
            }
        }
        return ObservationGrain(fixed, dynamic)
    }

    fun child(call: Call, owner: Calculation?, bindings: CalculationBindings, arguments: Map<String, Value>,
        instances: (MetricInstanceRef) -> MetricInstance, definitions: (MetricRef) -> MetricDefinition): Child = when (val target = call.target) {
        is Instance -> {
            val instance = instances(target.instance)
            val definition = definitions(instance.definition)
            val actualMembers = resolveMembers(call.members, bindings, arguments)
            val actual = when (definition) {
                is MetricCalculation -> relationArguments(definition.calculation, memberArguments(definition.calculation, instance.bindings, actualMembers), call.relations)
                is MetricDefinition.FactMeasure -> { require(actualMembers.isEmpty() && call.relations.isEmpty()) { "fact measurements do not declare external inputs" }; instance.bindings }
            }
            Child(instance.definition, call.arguments.mapValues { resolve(it.value, bindings, arguments) }, actual, instance.fixedArguments, null)
        }
        is Measurement -> {
            val instance = instances(requireNotNull(bindings.measurements[target.slot]) { "measurement slot is not bound" })
            val slot = requireNotNull(requireNotNull(owner).measurementSlots.firstOrNull { it.name == target.slot }) { "measurement slot is not declared" }
            val supplied = call.arguments.mapValues { resolve(it.value, bindings, arguments) }
            val definition = definitions(instance.definition)
            val actualMembers = resolveMembers(memberBindings(call, bindings.members), bindings, arguments)
            val actualRelations = relationBindings(call, bindings.relations, definition)
            val actualBindings = when (definition) {
                is MetricCalculation -> relationArguments(definition.calculation, memberArguments(definition.calculation, instance.bindings, actualMembers), actualRelations)
                is MetricDefinition.FactMeasure -> {
                    require(actualMembers.isEmpty()) { "fact measurements do not declare member parameters" }
                    require(actualRelations.isEmpty()) { "fact measurements do not declare relation parameters" }
                    instance.bindings
                }
            }
            Child(instance.definition, supplied, actualBindings, instance.fixedArguments, slot)
        }
        is Fixed -> {
            val calculation = (definitions(target.metric) as? MetricCalculation)?.calculation
            val adopted = forwardedBindings(calculation, call.dimensionSlots, call.memberSlots, call.measurementSlots, call.relationSlots, call.arguments, bindings)
            val actual = CalculationBindings(adopted.dimensions, resolveMembers(adopted.members, bindings, arguments), adopted.groupingCandidates, adopted.measurements, adopted.relations)
            Child(target.metric, call.arguments.mapValues { resolve(it.value, bindings, arguments) }, actual, emptyMap(), null)
        }
    }

    fun memberBindings(call: Call, parent: Map<String, CalculationBindings.Member>): Map<String, CalculationBindings.Member> =
        if (call.target is Instance) call.members else Collections.unmodifiableMap(call.memberSlots.mapValues { requireNotNull(parent[it.value]) { "nested member slot is not bound" } })
    fun relationBindings(call: Call, parent: Map<String, CalculationBindings.Relation>, definition: MetricDefinition): Map<String, CalculationBindings.Relation> {
        if (call.target is Instance) return call.relations
        return forwardedRelations(call.relationSlots, parent, (definition as? MetricCalculation)?.calculation)
    }
    fun resolve(binding: Binding, bindings: CalculationBindings, arguments: Map<String, Value>): Value = when (binding) {
        is Argument -> requireNotNull(arguments[binding.name]) { "calculation argument is not bound" }
        is GroupingSlots -> Grouping(binding.slots.map { requireNotNull(bindings.dimensions[it]) { "grouping slot is not adopted" }.reference })
        is Value -> binding
    }
    fun groupingFamilies(arguments: Map<String, Value>, aliases: (ScopedInputContract.DimensionRef) -> String): Map<String, List<String>> =
        Collections.unmodifiableMap(arguments.filterValues { it is Grouping }.mapValues { (_, value) -> (value as Grouping).dimensions.map(aliases) })
    fun expandSymbols(selections: List<String>, families: Map<String, List<String>>): List<String> {
        val result = selections.flatMap { selection -> Calculation.groupingFamily(selection)?.let { requireNotNull(families[it]) { "grouping symbol family is not bound" } } ?: listOf(selection) }
        require(result.distinct().size == result.size) { "expanded grouping symbols contain duplicates" }
        return java.util.List.copyOf(result)
    }
    fun stages(stages: List<PostOperationContract.ValidatedOperation>, arguments: Map<String, Value>, owner: Calculation?,
        bindings: CalculationBindings, aliases: (ScopedInputContract.DimensionRef) -> String): List<PostOperationContract.ValidatedOperation> {
        val families = groupingFamilies(arguments, aliases)
        val selections: (List<String>) -> List<String> = { expandSymbols(it, families) }
        val contracts: (MetricValueContract) -> MetricValueContract = { it.adopted(bindings) }
        val expressions: (ResultExpression) -> ResultExpression = { value -> ResultExpressionTree.rewrite(value, selections, contracts) { expression ->
            if (expression is ResultExpression.ResultArgument) stageArgument(expression, arguments, requireNotNull(owner) { "query stages cannot reference undeclared calculation arguments" }, bindings) else expression
        } }
        return stages.map { stage -> PostOperationTraversal.rewrite(stage, expressions, selections, contracts) { resolve(it, bindings, arguments) } }
    }
    private fun stageArgument(argument: ResultExpression.ResultArgument, arguments: Map<String, Value>, owner: Calculation, bindings: CalculationBindings): ResultExpression {
        val scalar = requireNotNull(arguments[argument.name]) { "stage scalar argument is missing" } as Scalar
        require(scalar.valueType == argument.valueType.name) { "stage scalar argument type differs" }
        return when (scalar.scalarType) {
            ScalarValueType.NUMBER -> {
                val contract = requireNotNull(owner.parameters[argument.name]).valueContract
                val number = BigDecimal(scalar.value)
                if (contract == null) ResultExpression.ResultTypedLiteral(ScalarValueType.NUMBER, number)
                else { contract.requireScalar(scalar); ResultExpression.ResultQuantityLiteral(number, contract.adopted(bindings)) }
            }
            ScalarValueType.BOOLEAN -> ResultExpression.ResultLiteral(scalar.jdbcValue())
            else -> ResultExpression.ResultTypedLiteral(if (scalar.scalarType == ScalarValueType.ENUM) ScalarValueType.TEXT else scalar.scalarType, scalar.value)
        }
    }
}
