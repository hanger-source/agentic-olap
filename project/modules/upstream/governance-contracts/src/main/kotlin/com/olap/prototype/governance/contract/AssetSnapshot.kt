package com.olap.prototype.governance.contract

import com.olap.prototype.metric.MetricBusinessMeaning

/** 异构精确读取的封闭返回合同；不会把定义重新变成任意 JSON。 */
sealed interface AssetSnapshot {
    data class BusinessLine(val value: BusinessLineRecord) : AssetSnapshot
    data class PhysicalTable(val value: PhysicalTableRecord) : AssetSnapshot
    data class PhysicalColumn(val value: PhysicalColumnRecord) : AssetSnapshot
    data class LogicalTable(val value: LogicalTableRecord) : AssetSnapshot
    data class LogicalUnit(val value: LogicUnitRecord) : AssetSnapshot
    data class Topology(val value: TopologyRecord) : AssetSnapshot
    data class JoinPath(val value: JoinPathRecord) : AssetSnapshot
    data class Event(val value: EventRecord) : AssetSnapshot
    data class Metric(val value: MetricRecord, val businessMeaning: MetricBusinessMeaning) : AssetSnapshot
    data class Dimension(val value: DimensionRecord) : AssetSnapshot
    data class DimensionBinding(val value: DimensionEventBindingRecord) : AssetSnapshot
    data class SubjectArea(val value: SubjectAreaRecord) : AssetSnapshot
    data class MemberSource(val value: MemberSourceRecord) : AssetSnapshot
    data class MemberDefinition(val value: MemberDefinitionRecord) : AssetSnapshot
    data class AnalysisDimension(val value: AnalysisDimensionRecord) : AssetSnapshot
    data class Scenario(val value: ScenarioRecord) : AssetSnapshot
}
