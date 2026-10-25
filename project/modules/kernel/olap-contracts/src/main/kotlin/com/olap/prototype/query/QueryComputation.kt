package com.olap.prototype.query

import com.olap.prototype.calculation.*
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.time.TemporalWindow
import java.util.Collections
import java.util.TreeMap

/** 本次查询的计算语言；发布坐标、来源与执行配置归外层请求。 */
object QueryComputation {
    class Scope(val root: Root, inputs: Map<String, Input>, stages: List<PostOperationContract.ValidatedOperation>) {
        val inputs: Map<String, Input> = Collections.unmodifiableMap(TreeMap(inputs))
        val stages: List<PostOperationContract.ValidatedOperation> = java.util.List.copyOf(stages)
        init { this.inputs.keys.forEach { ScopedInputContract.requireId(it) } }
        override fun equals(other: Any?): Boolean = other is Scope && root == other.root && inputs == other.inputs && stages == other.stages
        override fun hashCode(): Int = listOf(root, inputs, stages).hashCode()
    }
    data class Input(val mode: String, val computation: Scope) { init { ScopedInputContract.requireMode(mode) } }
    sealed interface Root
    class Compute(val calculation: Calculation, val bindings: CalculationBindings, arguments: Map<String, Calculation.Value>, members: Map<String, CalculationBindings.Member>, relations: Map<String, CalculationBindings.Relation>) : Root {
        val arguments: Map<String, Calculation.Value> = java.util.Map.copyOf(arguments)
        val members: Map<String, CalculationBindings.Member> = java.util.Map.copyOf(members)
        val relations: Map<String, CalculationBindings.Relation> = java.util.Map.copyOf(relations)
        override fun equals(other: Any?): Boolean = other is Compute && calculation == other.calculation && bindings == other.bindings && arguments == other.arguments && members == other.members && relations == other.relations
        override fun hashCode(): Int = listOf(calculation, bindings, arguments, members, relations).hashCode()
    }
    class Invocation(val callId: String, val instance: MetricInstanceRef, val outputAlias: String, arguments: Map<String, Calculation.Value>, members: Map<String, CalculationBindings.Member>, relations: Map<String, CalculationBindings.Relation>, val sourceCondition: DimensionCondition) {
        val arguments: Map<String, Calculation.Value> = java.util.Map.copyOf(arguments)
        val members: Map<String, CalculationBindings.Member> = java.util.Map.copyOf(members)
        val relations: Map<String, CalculationBindings.Relation> = java.util.Map.copyOf(relations)
        init { ScopedInputContract.requireId(callId); text(outputAlias) }
        override fun equals(other: Any?): Boolean = other is Invocation && callId == other.callId && instance == other.instance && outputAlias == other.outputAlias && arguments == other.arguments && members == other.members && relations == other.relations && sourceCondition == other.sourceCondition
        override fun hashCode(): Int = listOf(callId, instance, outputAlias, arguments, members, relations, sourceCondition).hashCode()
    }
    data class TimeRange(val dimension: ScopedInputContract.DimensionRef, val window: TemporalWindow)
    data class EventCondition(val eventId: String, val eventVersion: String, val condition: DimensionCondition) { init { text(eventId); version(eventVersion) } }
    class DimensionLevel(val id: String, dimensions: List<ScopedInputContract.DimensionRef>) {
        val dimensions: List<ScopedInputContract.DimensionRef> = java.util.List.copyOf(dimensions)
        init { ScopedInputContract.requireId(id); require(this.dimensions.distinct().size == this.dimensions.size) { "grouping level repeats a semantic dimension" } }
        override fun equals(other: Any?): Boolean = other is DimensionLevel && id == other.id && dimensions == other.dimensions
        override fun hashCode(): Int = listOf(id, dimensions).hashCode()
    }
    class Levels(val outputAlias: String, levels: List<DimensionLevel>) {
        val levels: List<DimensionLevel> = java.util.List.copyOf(levels)
        init { PostOperationContract.requireAlias(outputAlias); require(this.levels.isNotEmpty()) { "grouping levels must be nonempty and explicit" } }
        fun bind(grouping: Map<String, ScopedInputContract.DimensionRef>): GroupingLevels.Bound {
            val syntax = GroupingLevels(outputAlias, levels.map { level -> GroupingLevels.Level(level.id, level.dimensions.map { dimension ->
                grouping.entries.firstOrNull { it.value == dimension }?.key ?: throw IllegalArgumentException("grouping level references a dimension outside its declared root grouping")
            }) })
            return syntax.bind(grouping.keys.toList())
        }
        override fun equals(other: Any?): Boolean = other is Levels && outputAlias == other.outputAlias && levels == other.levels
        override fun hashCode(): Int = listOf(outputAlias, levels).hashCode()
    }
    class Measures(calls: List<Invocation>, grouping: List<ScopedInputContract.DimensionRef>,
        val sourceTimeRange: TimeRange?, sourceClocks: Map<String, TemporalClockBindings.Value>,
        val sourceCondition: DimensionCondition, val outputCondition: DimensionCondition, sourceEventConditions: List<EventCondition>, val population: RowPopulationContract.Selection,
        val groupingLevels: Levels?) : Root {
        val calls: List<Invocation> = java.util.List.copyOf(calls)
        val grouping: List<ScopedInputContract.DimensionRef> = java.util.List.copyOf(grouping)
        val sourceClocks: Map<String, TemporalClockBindings.Value> = java.util.Map.copyOf(sourceClocks)
        val sourceEventConditions: List<EventCondition> = java.util.List.copyOf(sourceEventConditions)
        init {
            require(this.sourceEventConditions.map { it.eventId to it.eventVersion }.distinct().size == this.sourceEventConditions.size) { "source event conditions require distinct exact event scopes" }
            require(this.calls.isNotEmpty()) { "measure root requires explicit invocations" }
            require(this.calls.map { it.callId }.distinct().size == this.calls.size && this.calls.map { it.outputAlias }.distinct().size == this.calls.size && this.grouping.distinct().size == this.grouping.size) { "measure identities, aliases and grouping must be distinct" }
            RowPopulationContract.requireSelected(population, this.calls.map { it.callId }.toSet())
        }
        override fun equals(other: Any?): Boolean = other is Measures && calls == other.calls && grouping == other.grouping && sourceTimeRange == other.sourceTimeRange && sourceClocks == other.sourceClocks && sourceCondition == other.sourceCondition && outputCondition == other.outputCondition && sourceEventConditions == other.sourceEventConditions && population == other.population && groupingLevels == other.groupingLevels
        override fun hashCode(): Int = listOf(calls, grouping, sourceTimeRange, sourceClocks, sourceCondition, outputCondition, sourceEventConditions, population, groupingLevels).hashCode()
    }
    sealed interface MemberProjection { val outputAlias: String; val slot: String }
    data class Slot(override val outputAlias: String, override val slot: String) : MemberProjection { init { text(outputAlias); text(slot) } }
    data class MappedDimension(override val outputAlias: String, override val slot: String, val dimension: ScopedInputContract.DimensionRef) : MemberProjection { init { text(outputAlias); text(slot) } }
    class Members(val callId: String, val source: MemberRef, arguments: Map<String, Calculation.Value>, outputs: List<MemberProjection>,
        val range: TemporalWindow?, clocks: Map<String, TemporalClockBindings.Value>,
        val condition: MemberCondition, members: Map<String, CalculationBindings.Member>, relations: Map<String, CalculationBindings.Relation>) : Root {
        val arguments: Map<String, Calculation.Value> = java.util.Map.copyOf(arguments)
        val outputs: List<MemberProjection> = java.util.List.copyOf(outputs)
        val clocks: Map<String, TemporalClockBindings.Value> = java.util.Map.copyOf(clocks)
        val members: Map<String, CalculationBindings.Member> = java.util.Map.copyOf(members)
        val relations: Map<String, CalculationBindings.Relation> = java.util.Map.copyOf(relations)
        init {
            ScopedInputContract.requireId(callId)
            require(this.outputs.isNotEmpty() && this.outputs.map { it.outputAlias }.distinct().size == this.outputs.size) { "member root requires distinct outputs in declared order" }
        }
        override fun equals(other: Any?): Boolean = other is Members && callId == other.callId && source == other.source && arguments == other.arguments && outputs == other.outputs && range == other.range && clocks == other.clocks && condition == other.condition && members == other.members && relations == other.relations
        override fun hashCode(): Int = listOf(callId, source, arguments, outputs, range, clocks, condition, members, relations).hashCode()
    }
    data class Reuse(val inputId: String) : Root { init { ScopedInputContract.requireId(inputId) } }
    class SetInputs(val operation: Calculation.SetKind, val all: Boolean, inputs: List<Calculation.SetProjection>, outputSlots: List<SetOutputContract>) : Root {
        val inputs: List<Calculation.SetProjection> = java.util.List.copyOf(inputs)
        val outputSlots: List<SetOutputContract> = java.util.List.copyOf(outputSlots)
        init { require(this.inputs.size >= 2 && this.outputSlots.isNotEmpty() && this.outputSlots.map { it.name }.distinct().size == this.outputSlots.size && this.inputs.all { it.symbols.size == this.outputSlots.size }) { "set root requires explicit equal-width projections and distinct output slots" } }
        override fun equals(other: Any?): Boolean = other is SetInputs && operation == other.operation && all == other.all && inputs == other.inputs && outputSlots == other.outputSlots
        override fun hashCode(): Int = listOf(operation, all, inputs, outputSlots).hashCode()
    }

    /** 仅本次显式传入的Local消费调用者ROOT，冻结计算拥有自己的词法根。 */
    fun rootInputs(root: Root): Set<String> {
        val result = linkedSetOf<String>()
        fun relations(values: Map<String, CalculationBindings.Relation>) {
            values.values.forEach { relation -> when (relation) { is CalculationBindings.LocalRelation -> result.add(relation.inputId); is CalculationBindings.RelationParameter -> Unit } }
        }
        fun members(values: Map<String, CalculationBindings.Member>) {
            values.values.forEach { member -> when (member) { is CalculationBindings.LocalRef -> result.add(member.inputId); is CalculationBindings.SourceCall -> { members(member.members); relations(member.relations) }; is CalculationBindings.InputParameter -> Unit } }
        }
        when (root) {
            is Reuse -> result.add(root.inputId)
            is SetInputs -> root.inputs.forEach { result.add(it.inputId) }
            is Measures -> root.calls.forEach { members(it.members); relations(it.relations) }
            is Members -> { members(root.members); relations(root.relations) }
            is Compute -> { members(root.members); relations(root.relations) }
        }
        return Collections.unmodifiableSet(result)
    }
    private fun text(value: String) { require(value.isNotBlank()) { "query identity must be explicit" } }
    private fun version(value: String) { require(value.matches(Regex("[1-9][0-9]*"))) { "query asset version must be exact" } }
}
