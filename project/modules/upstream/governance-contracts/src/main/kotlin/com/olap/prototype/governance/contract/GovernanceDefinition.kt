package com.olap.prototype.governance.contract

import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints

/** 一个版本的完整定义正文；生命周期状态由版本行拥有，引用索引按正文核对。 */
sealed interface GovernanceDefinition {
    data class VersionRef(val id: String, val version: Long)
    class PhysicalTable(val record: PhysicalTableRecord, columnVersionRefs: List<VersionRef>,
        val sourceConstraints: PhysicalSourceConstraints, val dataDomain: String?, val sensitivityLevel: String?, val sourceSystem: String?) : GovernanceDefinition {
        val columnVersionRefs: List<VersionRef> = java.util.List.copyOf(columnVersionRefs)
        override fun equals(other: Any?): Boolean = other is PhysicalTable && record == other.record && columnVersionRefs == other.columnVersionRefs && sourceConstraints == other.sourceConstraints && dataDomain == other.dataDomain && sensitivityLevel == other.sensitivityLevel && sourceSystem == other.sourceSystem
        override fun hashCode(): Int = listOf(record, columnVersionRefs, sourceConstraints, dataDomain, sensitivityLevel, sourceSystem).hashCode()
    }
    data class PhysicalColumn(val record: PhysicalColumnRecord, val sensitivityLevel: String?) : GovernanceDefinition
    data class LogicalTable(val record: LogicalTableRecord, val physicalTableId: String, val physicalTableVersion: Long, val logicUnitCount: Int) : GovernanceDefinition
    class LogicUnit(val record: LogicUnitRecord, sourceRefs: List<LogicalTableCreateRequest.SourceRefInput>) : GovernanceDefinition {
        val sourceRefs: List<LogicalTableCreateRequest.SourceRefInput> = java.util.List.copyOf(sourceRefs)
        override fun equals(other: Any?): Boolean = other is LogicUnit && record == other.record && sourceRefs == other.sourceRefs
        override fun hashCode(): Int = listOf(record, sourceRefs).hashCode()
    }
    data class Topology(val record: TopologyRecord) : GovernanceDefinition
    data class JoinPath(val record: JoinPathRecord) : GovernanceDefinition
    class Event(val record: EventRecord, val identity: EventIdentity, roles: Map<String, RelationAccessPath>,
        filterableLogicUnitRefs: List<EventCreateRequest.LogicUnitRefInput>, measureLogicUnitRefs: List<EventCreateRequest.LogicUnitRefInput>) : GovernanceDefinition {
        val roles: Map<String, RelationAccessPath> = java.util.Map.copyOf(roles)
        val filterableLogicUnitRefs: List<EventCreateRequest.LogicUnitRefInput> = java.util.List.copyOf(filterableLogicUnitRefs)
        val measureLogicUnitRefs: List<EventCreateRequest.LogicUnitRefInput> = java.util.List.copyOf(measureLogicUnitRefs)
        override fun equals(other: Any?): Boolean = other is Event && record == other.record && identity == other.identity && roles == other.roles && filterableLogicUnitRefs == other.filterableLogicUnitRefs && measureLogicUnitRefs == other.measureLogicUnitRefs
        override fun hashCode(): Int = listOf(record, identity, roles, filterableLogicUnitRefs, measureLogicUnitRefs).hashCode()
    }
    data class Metric(val record: MetricRecord, val businessMeaning: MetricBusinessMeaning) : GovernanceDefinition
    data class Dimension(val record: DimensionRecord) : GovernanceDefinition
    class DimensionBinding(val record: DimensionEventBindingRecord, val accessPath: RelationAccessPath, roles: Map<String, RelationAccessPath>) : GovernanceDefinition {
        val roles: Map<String, RelationAccessPath> = java.util.Map.copyOf(roles)
        override fun equals(other: Any?): Boolean = other is DimensionBinding && record == other.record && accessPath == other.accessPath && roles == other.roles
        override fun hashCode(): Int = listOf(record, accessPath, roles).hashCode()
    }
    data class BusinessLine(val record: BusinessLineRecord) : GovernanceDefinition
    data class SubjectArea(val record: SubjectAreaRecord) : GovernanceDefinition
    data class AnalysisDimension(val record: AnalysisDimensionRecord) : GovernanceDefinition
    data class Scenario(val record: ScenarioRecord) : GovernanceDefinition
}
