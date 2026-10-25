package com.olap.prototype.dsl

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricInstanceRef
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
class CalculationScopeBuilder internal constructor(private val root: Calculation.Root) {
    private val inputs = linkedMapOf<String, Calculation.InputScope>()
    private val stages = mutableListOf<PostOperationContract.ValidatedOperation>()
    fun input(id: String, computation: Calculation.Scope, mode: InputMode) {
        require(id !in inputs) { "query input identity is repeated: $id" }
        inputs[id] = Calculation.InputScope(mode.name, computation)
    }
    fun stage(operation: PostOperationContract.ValidatedOperation) { stages.add(operation) }
    internal fun build(): Calculation.Scope = Calculation.Scope(root, inputs, stages)
}
enum class InputMode { ROOT, KEY_ALIGNMENT, RELATION }

/** 构造共同计算体；只组织实际引用，不推断人口、身份或数量合同。 */
object Computations {
    data class ProjectedInput(val computation: Calculation.Scope, val symbols: List<String>)

    fun scope(root: Calculation.Root, build: CalculationScopeBuilder.() -> Unit = {}): Calculation.Scope =
        CalculationScopeBuilder(root).apply(build).build()

    fun query(publication: QueryCapabilityRequest, computation: Calculation.Scope): QueryIntent =
        QueryIntent(publication, computation)

    fun query(publication: QueryCapabilityRequest, root: Calculation.Root,
        build: CalculationScopeBuilder.() -> Unit = {}): QueryIntent = query(publication, scope(root, build))

    fun input(inputId: String): Calculation.Scope = scope(Calculation.Input(inputId))

    fun stages(computation: Calculation.Scope,
        operations: List<PostOperationContract.ValidatedOperation>): Calculation.Scope =
        Calculation.Scope(computation.root, computation.inputs, computation.stages + operations)

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
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted())): Calculation.Call =
        invoke(Calculation.Instance(instance), callId, outputAlias, arguments, members = members, relations = relations, sourceCondition = sourceCondition)

    fun invoke(target: Calculation.CallTarget, callId: String, outputAlias: String = callId,
        arguments: Map<String, Calculation.Binding> = emptyMap(),
        dimensionSlots: Map<String, String> = emptyMap(), memberSlots: Map<String, String> = emptyMap(),
        measurementSlots: Map<String, String> = emptyMap(), relationSlots: Map<String, String> = emptyMap(),
        members: Map<String, CalculationBindings.Member> = emptyMap(), relations: Map<String, CalculationBindings.Relation> = emptyMap(),
        sourceCondition: Calculation.Binding = Calculation.Condition(DimensionCondition.Unrestricted())): Calculation.Call =
        Calculation.Call(callId, target, outputAlias, dimensionSlots, memberSlots, measurementSlots, relationSlots, arguments, sourceCondition, members, relations)

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
        arguments: Map<String, Calculation.Binding> = emptyMap(), members: Map<String, CalculationBindings.Member> = emptyMap(),
        relations: Map<String, CalculationBindings.Relation> = emptyMap()): Calculation.Scope =
        compute(calculation, Calculation.ExplicitBindings(bindings, members, relations), arguments)

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
