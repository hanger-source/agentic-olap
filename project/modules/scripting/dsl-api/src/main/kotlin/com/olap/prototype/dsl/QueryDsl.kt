package com.olap.prototype.dsl

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.QueryIntent
import com.olap.prototype.expression.ScalarFunction
import java.math.BigDecimal

@DslMarker
annotation class OlapDsl

/** 局部 builder 只在 build 时交出完整正式模型，不保存 lambda。 */
@OlapDsl
class CalculationScopeBuilder internal constructor() {
    private val inputs = linkedMapOf<String, Calculation.InputScope>()
    private val stages = mutableListOf<PostOperationContract.ValidatedOperation>()
    fun input(id: String, computation: Calculation.Scope, mode: InputMode) {
        require(id !in inputs) { "computation input identity is repeated: $id" }
        inputs[id] = Calculation.InputScope(mode.name, computation)
    }

    /** 实际完成输入与其形参列映射一起登记；身份、数量及键仍由共同绑定核验。 */
    fun relation(id: String, computation: Calculation.Scope,
        outputs: Map<String, String>): CalculationBindings.LocalRelation {
        val reference = CalculationBindings.LocalRelation(id, outputs)
        completed(id, computation)
        return reference
    }

    /** 保留实际成员的来源及角色，不将普通完成测量变成成员属性。 */
    fun member(id: String, computation: Calculation.Scope,
        outputs: Map<String, String>): CalculationBindings.LocalRef {
        val reference = CalculationBindings.LocalRef(id, outputs)
        completed(id, computation)
        return reference
    }

    private fun completed(id: String, computation: Calculation.Scope) {
        val declared = inputs[id]
        if (declared == null) input(id, computation, InputMode.ROOT)
        else require(declared.mode == InputMode.ROOT.name && declared.computation == computation) {
            "completed input identity refers to a different computation or mode: $id"
        }
    }

    fun stage(operation: PostOperationContract.ValidatedOperation) { stages.add(operation) }
    internal fun build(computation: Calculation.Scope): Calculation.Scope {
        val children = LinkedHashMap(computation.inputs)
        inputs.forEach { (id, input) ->
            val existing = children.putIfAbsent(id, input)
            require(existing == null || existing == input) { "computation input identity is already owned by a different input: $id" }
        }
        return Calculation.Scope(computation.root, children, computation.stages + stages)
    }

    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "receiverContext" to "Computations.scope { ... } or Computations.scope(root) { ... }",
            "construction" to "CREATED_BY_SCOPE", "result" to "Calculation.Scope",
            "stageOrder" to "RETURNED_COMPUTATION_STAGES_THEN_BUILDER_STAGES",
            "completedInputs" to mapOf(
                "relation" to "REGISTER_COMPLETED_SCOPE_AND_RETURN_LOCAL_RELATION_WITH_EXPLICIT_FORMAL_TO_ACTUAL_COLUMNS",
                "member" to "REGISTER_COMPLETED_SCOPE_AND_RETURN_LOCAL_MEMBER_WITH_ORIGINAL_SOURCE_IDENTITY_AND_ROLE_CHECKS",
                "registration" to "SDK_OWNS_ROOT_MODE_AND_LOCAL_REFERENCE_IN_THE_SAME_LEXICAL_SCOPE",
                "referenceScope" to "CONSUMER_LOCAL_ID_NOT_A_CAPTURED_OR_GLOBAL_HANDLE_PASS_THE_WHOLE_SCOPE_OR_FORWARD_A_DECLARED_FORMAL",
                "sameId" to "REUSE_ONLY_THE_SAME_EXACT_COMPUTATION_AND_MODE_OTHERWISE_REJECT",
                "outputMapping" to "CALLER_SELECTS_COLUMNS_ENGINE_CHECKS_FORMAL_ACCEPTANCE_WITHOUT_COPYING_ACTUAL_CONTRACTS"))
    }
}
enum class InputMode { ROOT, KEY_ALIGNMENT, RELATION }

/** 构造共同计算体；只组织实际引用，不推断人口、身份或数量合同。 */
object Computations {
    data class ProjectedInput(val computation: Calculation.Scope, val symbols: List<String>)

    fun contract(): Map<String, Any> = mapOf(
        "computation" to "ONE_CALCULATION_SCOPE_FOR_INLINE_COMPOSITION_QUERY_AND_DEFINITION_BODY",
        "scope" to "RETURNS_THE_FORMAL_COMPUTATION_WITH_ITS_EXACT_INPUTS_AND_ORDERED_STAGES",
        "formalInputs" to "DECLARE_ONLY_FOR_REPLACEABLE_INPUTS_OF_A_PARAMETERIZED_CALCULATION",
        "meaning" to "IDENTITY_QUANTITY_NULL_AND_KEYS_ARE_ANALYZED_BY_THE_COMMON_ENGINE_NOT_REDECLARED_BY_THE_SDK",
        "calls" to Calculation.Call.contract(),
        "join" to mapOf("inputs" to "COMPLETE_LEFT_AND_RIGHT_COMPUTATIONS", "position" to "AFTER_THE_LEFT_EXISTING_STAGES",
            "registration" to "SDK_OWNS_THE_RIGHT_RELATION_INPUT_MODE", "choices" to "AUTHOR_OWNS_JOIN_TYPE_CONDITION_AND_OCCURRENCE"))

    fun scope(root: Calculation.Root, build: CalculationScopeBuilder.() -> Unit = {}): Calculation.Scope =
        CalculationScopeBuilder().apply(build).build(Calculation.Scope(root, emptyMap(), emptyList()))

    /** 返回共同计算体；本地实参登记与最终消费它们的根拥有同一词法作用域。 */
    fun scope(build: CalculationScopeBuilder.() -> Calculation.Scope): Calculation.Scope =
        CalculationScopeBuilder().let { builder -> builder.build(build.invoke(builder)) }

    fun query(publication: QueryCapabilityRequest, computation: Calculation.Scope): QueryIntent =
        QueryIntent(publication, computation)

    fun query(publication: QueryCapabilityRequest, build: CalculationScopeBuilder.() -> Calculation.Scope): QueryIntent =
        query(publication, scope(build))

    fun query(publication: QueryCapabilityRequest, root: Calculation.Root,
        build: CalculationScopeBuilder.() -> Unit = {}): QueryIntent = query(publication, scope(root, build))

    fun input(inputId: String): Calculation.Scope = scope(Calculation.Input(inputId))

    fun stages(computation: Calculation.Scope,
        operations: List<PostOperationContract.ValidatedOperation>): Calculation.Scope =
        Calculation.Scope(computation.root, computation.inputs, computation.stages + operations)

    /** 关系运算接收完整计算；不拆出根，也不让调用者补写内部输入模式。 */
    fun join(computation: Calculation.Scope, relation: Calculation.Scope,
        operation: PostOperationContract.JoinInput): Calculation.Scope {
        val inputs = LinkedHashMap(computation.inputs)
        val actual = Calculation.InputScope(InputMode.RELATION.name, relation)
        val previous = inputs.putIfAbsent(operation.inputId, actual)
        require(previous == null || previous == actual) { "join input identity refers to a different computation or mode: ${operation.inputId}" }
        return Calculation.Scope(computation.root, inputs, computation.stages + operation)
    }

    fun members(callId: String, source: MemberRef, arguments: Map<String, Calculation.Binding>,
        outputs: List<Calculation.MemberProjection>, range: Calculation.Binding? = null,
        clocks: Map<String, Calculation.Binding> = emptyMap(), condition: MemberCondition = MemberCondition.Unrestricted(),
        members: Map<String, CalculationBindings.Member> = emptyMap(),
        relations: Map<String, CalculationBindings.Relation> = emptyMap()): Calculation.Scope =
        scope(Calculation.Members(callId, Calculation.MemberSource(source), outputs, range, clocks, condition, arguments, members, relations))

    fun members(callId: String, target: Calculation.MemberTarget, outputs: List<Calculation.MemberProjection>,
        range: Calculation.Binding? = null, clocks: Map<String, Calculation.Binding> = emptyMap(),
        condition: MemberCondition = MemberCondition.Unrestricted(), arguments: Map<String, Calculation.Binding> = emptyMap(),
        members: Map<String, CalculationBindings.Member> = emptyMap(),
        relations: Map<String, CalculationBindings.Relation> = emptyMap()): Calculation.Scope =
        scope(Calculation.Members(callId, target, outputs, range, clocks, condition, arguments, members, relations))

    fun invoke(instance: MetricInstanceRef, callId: String, outputAlias: String = callId,
        arguments: Map<String, Calculation.Binding> = emptyMap(),
        members: Map<String, CalculationBindings.Member> = emptyMap(),
        relations: Map<String, CalculationBindings.Relation> = emptyMap(),
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted())): Calculation.InstanceCall =
        Calculation.InstanceCall(callId, instance, outputAlias, arguments, members, relations, sourceCondition)

    fun invoke(metric: MetricRef, callId: String, outputAlias: String = callId,
        arguments: Map<String, Calculation.Binding> = emptyMap(),
        dimensionSlots: Map<String, String> = emptyMap(), memberSlots: Map<String, String> = emptyMap(),
        measurementSlots: Map<String, String> = emptyMap(), relationSlots: Map<String, String> = emptyMap(),
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted())): Calculation.DefinitionCall =
        Calculation.DefinitionCall(callId, metric, outputAlias, dimensionSlots, memberSlots, measurementSlots, relationSlots, arguments, sourceCondition)

    fun measurement(slot: String, callId: String, outputAlias: String = callId,
        arguments: Map<String, Calculation.Binding> = emptyMap(), memberSlots: Map<String, String> = emptyMap(),
        relationSlots: Map<String, String> = emptyMap(),
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted())): Calculation.MeasurementCall =
        Calculation.MeasurementCall(callId, slot, outputAlias, memberSlots, relationSlots, arguments, sourceCondition)

    fun measure(calls: List<Calculation.Call>, grouping: List<Calculation.DimensionReference>,
        population: RowPopulationContract.Selection, groupingArguments: List<String> = emptyList(),
        timeRange: Calculation.TimeRange? = null, clocks: Map<String, Calculation.Binding> = emptyMap(),
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted()),
        outputCondition: DimensionCondition = DimensionCondition.Unrestricted(),
        sourceEventConditions: List<Calculation.EventCondition> = emptyList(),
        groupingLevels: GroupingLevels? = null): Calculation.Scope =
        scope(Calculation.Measures(calls, grouping, groupingArguments, timeRange, clocks, sourceCondition,
            outputCondition, sourceEventConditions, population, groupingLevels))

    fun set(operation: Calculation.SetKind, all: Boolean, inputs: Map<String, ProjectedInput>,
        outputs: List<SetOutputContract>): Calculation.Scope = scope(
        Calculation.SetCombination(operation, all, inputs.map { (id, input) -> Calculation.SetProjection(id, input.symbols) }, outputs)) {
        inputs.forEach { (id, value) -> input(id, value.computation, InputMode.ROOT) }
    }

    fun aligned(computation: Calculation.Scope, keys: Map<String, String>,
        values: List<Calculation.AlignedValue>): Calculation.AlignedInput = Calculation.AlignedInput(computation, keys, values)

    fun align(inputs: Map<String, Calculation.AlignedInput>, observationKeys: List<String>,
        population: Calculation.AlignmentPopulation): Calculation.Scope =
        scope(Calculation.AlignMeasurements(inputs, observationKeys, population))

    fun objectMeasure(measurement: Calculation.Scope, objects: Calculation.Scope,
        roles: List<Calculation.ObjectRole>): Calculation.Scope = scope(Calculation.ObjectMeasure(measurement, objects, roles))

    fun statistics(observations: Calculation.Scope, unit: Calculation.ObservationUnit,
        outerKeys: List<String>, statistics: List<Calculation.ObservationStatistic>): Calculation.Scope =
        scope(Calculation.ObservationStatistics(observations, unit, outerKeys, statistics))

    fun compute(calculation: Calculation, bindings: CalculationBindings,
        arguments: Map<String, Calculation.Binding> = emptyMap()): Calculation.Scope =
        compute(calculation, Calculation.ExplicitBindings(bindings), arguments)

    fun compute(calculation: Calculation, bindings: Calculation.ComputeBindings,
        arguments: Map<String, Calculation.Binding> = emptyMap()): Calculation.Scope =
        scope(Calculation.Compute(calculation, bindings, arguments))
}

fun symbol(name: String): ResultExpression = ResultExpression.ResultSymbol(name)
fun number(value: String): ResultExpression = ResultExpression.ResultLiteral(BigDecimal(value))
fun boolean(value: Boolean): ResultExpression = ResultExpression.ResultLiteral(value)
fun nullValue(): ResultExpression = ResultExpression.ResultLiteral(null)
fun text(value: String): ResultExpression = ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, value)
fun argument(name: String, type: ScalarValueType): ResultExpression = ResultExpression.ResultArgument(name, type)
fun call(function: ScalarFunction, vararg arguments: ResultExpression): ResultExpression = ResultExpression.ResultCall(function, arguments.toList())
fun row(vararg fields: ResultExpression): ResultExpression = ResultExpression.ResultRow(fields.toList())
fun ResultExpression.field(index: Int): ResultExpression = ResultExpression.ResultRowField(this, index)
