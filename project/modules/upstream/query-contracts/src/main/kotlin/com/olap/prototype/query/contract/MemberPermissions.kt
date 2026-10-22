package com.olap.prototype.query.contract

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.event.RelationFilter
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSource
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import java.util.Collections
import java.util.TreeMap

/** 读取属性、值、筛选、范围和时钟分别授权。 */
class MemberQueryPermission(outputs: List<String>, values: List<String>, filters: Map<String, List<MemberCondition.Operator>>, val timeRange: Boolean, clocks: List<String>) {
    val outputs: List<String> = distinct(outputs)
    val values: List<String> = distinct(values)
    val filters: Map<String, List<MemberCondition.Operator>> = operators(filters)
    val clocks: List<String> = distinct(clocks)
    init { require(this.outputs.isNotEmpty() || this.values.isNotEmpty()) { "member permission requires approved outputs or value slots" } }
    override fun equals(other: Any?): Boolean = other is MemberQueryPermission && outputs == other.outputs && values == other.values && filters == other.filters && timeRange == other.timeRange && clocks == other.clocks
    override fun hashCode(): Int = listOf(outputs, values, filters, timeRange, clocks).hashCode()
    companion object {
        fun operators(values: Map<String, List<MemberCondition.Operator>>): Map<String, List<MemberCondition.Operator>> = Collections.unmodifiableMap(linkedMapOf<String, List<MemberCondition.Operator>>().apply {
            values.forEach { (slot, allowed) ->
                MetricDefinition.text(slot, "member filter slot")
                val operators = java.util.List.copyOf(allowed)
                require(operators.isNotEmpty() && operators.distinct().size == operators.size) { "member operators must be explicit and distinct" }
                put(slot, operators)
            }
        })
        private fun distinct(values: List<String>): List<String> = java.util.List.copyOf(values).also { frozen ->
            frozen.forEach { MetricDefinition.text(it, "member permission slot") }
            require(frozen.distinct().size == frozen.size) { "duplicate member permission slot" }
        }
    }
}
class RelationFilterPermission(filters: Map<String, List<MemberCondition.Operator>>, val timeRange: Boolean, clocks: List<String>) {
    val filters: Map<String, List<MemberCondition.Operator>> = MemberQueryPermission.operators(filters)
    val clocks: List<String> = java.util.List.copyOf(clocks)
    init {
        this.clocks.forEach { MetricDefinition.text(it, "relation clock slot") }
        require(this.clocks.distinct().size == this.clocks.size) { "relation clock permissions must be distinct" }
    }
    override fun equals(other: Any?): Boolean = other is RelationFilterPermission && filters == other.filters && timeRange == other.timeRange && clocks == other.clocks
    override fun hashCode(): Int = listOf(filters, timeRange, clocks).hashCode()
}
data class PublishedRelationFilter(val relation: RelationFilter, val scenarioId: String, val displayName: String,
    val permission: RelationFilterPermission, val requiredByDefault: Boolean, val sortOrder: Int) {
    init {
        MetricDefinition.text(scenarioId, "scenarioId"); MetricDefinition.text(displayName, "displayName")
        require(relation.clockSlots.keys.containsAll(permission.clocks)) { "approved relation clocks are absent from the relation" }
    }
}
data class PublishedMemberSource(val source: MemberSource, val subjectAreaId: String, val code: String, val displayName: String) {
    init { MetricDefinition.text(subjectAreaId, "subjectAreaId"); MetricDefinition.text(code, "code"); MetricDefinition.text(displayName, "displayName") }
}
class RelationFilterCandidate(val definition: PublishedRelationFilter, val entity: String, val role: String,
    filterTypes: Map<String, ScalarValueType>, filterValueContracts: Map<String, MetricValueContract?>,
    val timeRange: MemberCandidate.TimeRangeCapability?, clockSlots: Map<String, String>) {
    val filterTypes: Map<String, ScalarValueType> = java.util.Map.copyOf(filterTypes)
    val filterValueContracts: Map<String, MetricValueContract?> = Collections.unmodifiableMap(TreeMap(filterValueContracts))
    val clockSlots: Map<String, String> = java.util.Map.copyOf(clockSlots)
    init {
        require(this.filterTypes.keys == definition.permission.filters.keys && this.filterValueContracts.keys == this.filterTypes.keys &&
            this.clockSlots.keys == definition.permission.clocks.toSet() && definition.permission.timeRange == (timeRange != null)) {
            "relation candidate must exactly describe its granted filters, range and clocks"
        }
    }
    override fun equals(other: Any?): Boolean = other is RelationFilterCandidate && definition == other.definition && entity == other.entity && role == other.role && filterTypes == other.filterTypes && filterValueContracts == other.filterValueContracts && timeRange == other.timeRange && clockSlots == other.clockSlots
    override fun hashCode(): Int = listOf(definition, entity, role, filterTypes, filterValueContracts, timeRange, clockSlots).hashCode()
}
