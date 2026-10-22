package com.olap.prototype.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.calculation.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.util.WireTypeShape
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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Measures::class, name = "MEASURES"), JsonSubTypes.Type(value = Members::class, name = "MEMBERS"), JsonSubTypes.Type(value = Reuse::class, name = "INPUT"), JsonSubTypes.Type(value = SetInputs::class, name = "SET"), JsonSubTypes.Type(value = Compute::class, name = "COMPUTE"))
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
        init { PostOperationContract.requireAlias(outputAlias); require(this.levels.size in 1..32) { "grouping levels must be bounded and explicit" } }
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
        @param:JsonProperty(value = "sourceTimeRange", required = true) val sourceTimeRange: TimeRange?, sourceClocks: Map<String, TemporalClockBindings.Value>,
        val sourceCondition: DimensionCondition, val outputCondition: DimensionCondition, sourceEventConditions: List<EventCondition>, val population: RowPopulationContract.Selection,
        @param:JsonProperty(value = "groupingLevels", required = true) val groupingLevels: Levels?) : Root {
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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Slot::class, name = "SLOT"), JsonSubTypes.Type(value = MappedDimension::class, name = "MAPPED_DIMENSION"))
    sealed interface MemberProjection { val outputAlias: String; val slot: String }
    data class Slot(override val outputAlias: String, override val slot: String) : MemberProjection { init { text(outputAlias); text(slot) } }
    data class MappedDimension(override val outputAlias: String, override val slot: String, val dimension: ScopedInputContract.DimensionRef) : MemberProjection { init { text(outputAlias); text(slot) } }
    class Members(val callId: String, val source: MemberRef, arguments: Map<String, Calculation.Value>, outputs: List<MemberProjection>,
        @param:JsonProperty(value = "range", required = true) val range: TemporalWindow?, clocks: Map<String, TemporalClockBindings.Value>,
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
    fun parse(syntax: JsonNode): Scope = try { OlapJsonUtils.mapper().readerFor(Scope::class.java).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(syntax) }
        catch (failure: JsonProcessingException) { throw InputContractException.decoding(failure, syntax, listOf("computation")) }
    fun sourceEventConditionContract(): Map<String, Any> = mapOf("field" to "computation.root.sourceEventConditions", "scopeFields" to listOf("eventId", "eventVersion", "condition"),
        "eventVersionType" to "POSITIVE_INTEGER_TEXT", "combination" to "ROOT_SOURCE_CONDITION_AND_INVOCATION_SOURCE_CONDITION_AND_EXACT_EVENT_CONDITION", "placement" to "AFTER_EVENT_JOINS_BEFORE_DIRECT_FACT_AGGREGATION",
        "duplicateScopes" to "REJECT", "unselectedEvents" to "REJECT", "missingEventMappings" to "REJECT", "calculationInputs" to "DECLARED_ARGUMENTS_ONLY")

    fun wireShapes(): WireTypeShape = object : WireTypeShape(OlapJsonUtils.mapper()) {
        override fun typeRules(type: JavaType): Map<String, Any> =
            if (type.rawClass == Calculation.Call::class.java) Calculation.Call.contract() else super.typeRules(type)
    }

    fun rootContracts(): Map<String, Any> {
        val relations = mapOf("variants" to mapOf("LOCAL" to mapOf("fields" to listOf("kind", "inputId", "outputSlots"),
            "input" to "COMPLETED_ROOT_INPUT_IN_THE_CALLER_SCOPE", "outputSlots" to "FORMAL_OUTPUT_TO_COMPLETED_INPUT_SYMBOL")),
            "adoptionDeclaration" to mapOf("kind" to "PARAMETER", "fields" to listOf("kind"), "queryArgument" to false),
            "formalFields" to listOf("name", "outputs", "keys"), "keys" to "NULL_NO_UNIQUENESS_REQUIREMENT_EMPTY_SCALAR_UNIQUENESS_NONEMPTY_COMPLETE_PROVED_KEY",
            "supplied" to "ALL_AND_ONLY_PUBLISHED_RELATION_PARAMETERS", "fixedBindings" to "FROZEN_LOCAL_RELATION_CANNOT_BE_OVERRIDDEN")
        val members = mapOf("variants" to mapOf(
            "SOURCE_CALL" to mapOf("fields" to listOf("kind", "source", "arguments", "outputSlots", "members", "relations"), "sourceFields" to listOf("id", "version"), "sourceVersionType" to "POSITIVE_INTEGER",
                "arguments" to "CONCRETE_CALCULATION_VALUES_FOR_PUBLIC_CALLS", "outputSlots" to "FORMAL_OUTPUT_TO_APPROVED_SOURCE_OUTPUT_SLOT", "members" to "EXPLICIT_NESTED_PUBLISHED_MEMBER_PARAMETERS", "relations" to relations),
            "LOCAL" to mapOf("fields" to listOf("kind", "inputId", "outputSlots"), "input" to "COMPLETED_ROOT_INPUT_IN_THE_SAME_SCOPE", "outputSlots" to "FORMAL_OUTPUT_TO_COMPLETED_INPUT_SYMBOL")),
            "adoptionDeclaration" to mapOf("kind" to "PARAMETER", "fields" to listOf("kind"), "queryArgument" to false),
            "formalFields" to listOf("name", "entity", "role", "shape", "outputs", "fixedSource"), "fixedSource" to "EXPLICIT_MEMBER_REF_OR_NULL_FOR_A_BINDABLE_FORMAL",
            "supplied" to "ALL_AND_ONLY_PUBLISHED_MEMBER_PARAMETERS", "fixedBindings" to "FROZEN_SOURCE_CALL_CANNOT_BE_OVERRIDDEN")
        return mapOf("COMPUTE" to mapOf(
            "wireShape" to wireShapes().describe(OlapJsonUtils.mapper().constructType(Compute::class.java), "olap-wire-type-shape/v1"),
            "bindingPolicy" to "EXACT_PUBLIC_MEASUREMENT_INSTANCES_AND_APPROVED_MEMBER_AND_RELATION_INPUTS", "callTargets" to mapOf("allowed" to listOf("MEASUREMENT"), "FIXED" to "NOT_AUTHORIZED_BY_PUBLIC_INLINE_COMPUTE"),
            "members" to members, "relations" to relations, "rootConsumers" to "EXPLICIT_CALLER_LOCAL_MEMBER_AND_RELATION_ARGUMENTS_RECURSIVELY_ONLY_BODY_LOCALS_HAVE_OWN_SCOPE"),
            "MEASURES" to mapOf("fields" to listOf("kind", "calls", "grouping", "sourceTimeRange", "sourceClocks", "sourceCondition", "outputCondition", "sourceEventConditions", "population", "groupingLevels"),
                "nullableFields" to listOf("sourceTimeRange", "groupingLevels"), "requiredObjects" to listOf("calls", "grouping", "sourceClocks", "sourceCondition", "outputCondition", "sourceEventConditions", "population"),
                "unrestrictedCondition" to mapOf("kind" to "TRUE"), "callFields" to listOf("callId", "instance", "outputAlias", "arguments", "members", "relations", "sourceCondition"), "instanceFields" to listOf("id"),
                "argumentBindings" to "EXPLICIT_OBJECT_EMPTY_WHEN_NO_PARAMETERS", "memberBindings" to members, "relationBindings" to relations,
                "invocationSourceCondition" to "CONSUMED_ONLY_BY_THE_EXACT_DIRECT_FACT_ALGORITHMS_REQUIRE_TRUE_AND_EXPLICIT_CONDITION_ARGUMENT_FORWARDING",
                "dimensionFields" to listOf("id", "version", "levelCode"),
                "groupingLevels" to mapOf("nullable" to true, "fields" to listOf("outputAlias", "levels"), "levelFields" to listOf("id", "dimensions"))),
            "MEMBERS" to mapOf("fields" to listOf("kind", "callId", "source", "arguments", "outputs", "range", "clocks", "condition", "members", "relations"), "nullableFields" to listOf("range"),
                "requiredObjects" to listOf("source", "arguments", "members", "relations", "outputs", "clocks", "condition"), "sourceFields" to listOf("id", "version"), "sourceVersionType" to "POSITIVE_INTEGER", "argumentBindings" to "EXPLICIT_OBJECT_EMPTY_WHEN_NO_PARAMETERS",
                "memberBindings" to members, "relationBindings" to relations, "unrestrictedCondition" to mapOf("kind" to "TRUE"), "conditionContract" to MemberCondition.contract(), "publicConditionValues" to "SCALAR_ONLY_WITH_EXACT_APPROVED_SLOT_TYPE_NO_VALUE_WRAPPER",
                "rawBag" to "RAW_BAG_DECLARES_NO_IDENTITY_AND_PRESERVES_SOURCE_MULTIPLICITY", "recordIdentity" to "RECORDS_REQUIRE_UNIQUENESS_IN_THE_ACTUAL_SELECTED_SOURCE_RELATION",
                "memberAttributes" to "UNIQUE_MEMBERS_REQUIRE_IDENTITY_TO_DETERMINE_SELECTED_ATTRIBUTES_THEN_EXPLICIT_DEDUPLICATION", "sourceGuarantees" to mapOf("PROJECTION" to "EXACT_ADOPTED_KEYS_AND_NONOVERLAP_FOR_ALL_SELECTED_CLOCK_INTERVALS_NO_CARDINALITY_LABEL_PROOF", "COMPUTED" to "FINAL_CALCULATION_RELATION_PROOFS_NOT_RESULT_DECLARATIONS_OR_FAKE_EVENTS"),
                "projections" to mapOf("SLOT" to listOf("kind", "outputAlias", "slot"), "MAPPED_DIMENSION" to listOf("kind", "outputAlias", "slot", "dimension"))),
            "INPUT" to mapOf("fields" to listOf("kind", "inputId")),
            "SET" to mapOf("fields" to listOf("kind", "operation", "all", "inputs", "outputSlots"), "inputFields" to listOf("inputId", "symbols"), "operations" to listOf("UNION", "INTERSECT", "EXCEPT"), "outputSlotFields" to listOf("name", "shape")))
    }
    private fun text(value: String) { require(value.isNotBlank()) { "query identity must be explicit" } }
    private fun version(value: String) { require(value.matches(Regex("[1-9][0-9]*"))) { "query asset version must be exact" } }
}
