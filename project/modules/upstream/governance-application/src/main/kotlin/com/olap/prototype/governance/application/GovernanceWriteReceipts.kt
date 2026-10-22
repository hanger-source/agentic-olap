package com.olap.prototype.governance.application

import com.olap.prototype.governance.contract.*
import com.olap.prototype.warehouse.onboarding.RecordedWarehouseDiscovery

/** 写入回执只投影 typed 结果；完整定义通过回执中的精确读取入口取得。 */
object GovernanceWriteReceipts {
    fun project(operation: String, value: Any): Map<String, Any?> = when (value) {
        is SubjectContextRevisionResult -> project(operation, value.subjectArea) + ("carried" to value.carried)
        is PhysicalTableCreateResult -> asset(operation, physical(value.physicalTable, value.physicalTableVersion), mapOf("columns" to value.physicalColumns.size), "read-physical-columns")
        is PhysicalTableColumnsUpdateResult -> asset(operation, physical(value.physicalTable, value.physicalTableVersion), mapOf("columns" to value.physicalColumns.size), "read-physical-columns")
        is PhysicalTableMetadataUpdateResult -> asset(operation, physical(value.physicalTable, value.physicalTableVersion), emptyMap(), "read-physical-columns")
        is BusinessLineCreateResult -> with(value) { asset(operation, AssetSummary("BUSINESS_LINE", businessLine.id, businessLineVersion.version, businessLineVersion.versionStatus, businessLine.code, businessLine.displayName)) }
        is LogicalTableCreateResult -> with(value) { asset(operation, AssetSummary("LOGICAL_TABLE", logicalTable.id, logicalTableVersion.version, logicalTableVersion.versionStatus, logicalTable.code, logicalTable.displayName),
            mapOf("logicUnits" to logicUnits.size, "sourceRefs" to sourceRefs.size), "read-logical-table-units") }
        is TopologyCreateResult -> with(value) { asset(operation, AssetSummary("TOPOLOGY", topology.id, topologyVersion.version, topologyVersion.versionStatus, topology.code, topology.displayName), emptyMap(), "read-topology-tables", "read-topology-joins") }
        is JoinPathCreateResult -> with(value) { asset(operation, AssetSummary("JOIN_PATH", joinPath.id, joinPathVersion.version, joinPathVersion.versionStatus, joinPath.code, null), mapOf("keys" to keyRefs.size), "read-join-keys") }
        is EventCreateResult -> with(value) { asset(operation, AssetSummary("EVENT", event.id, eventVersion.version, eventVersion.versionStatus, event.code, event.displayName), mapOf("logicUnitRefs" to logicUnitRefs.size), "read-event-units", "read-event-filters") }
        is MetricCreateResult -> metric(operation, value.metric, value.metricVersion)
        is MetricCalculationCreateResult -> metric(operation, value.metric, value.metricVersion)
        is DimensionCreateResult -> with(value) { asset(operation, AssetSummary("DIMENSION", dimension.id, dimensionVersion.version, dimensionVersion.versionStatus, dimension.code, dimension.displayName), emptyMap(), "read-dimension-bindings") }
        is DimensionEventBindingCreateResult -> dimensionBinding(operation, value)
        is SubjectAreaCreateResult -> with(value) { asset(operation, AssetSummary("SUBJECT_AREA", subjectArea.id, subjectAreaVersion.version, subjectAreaVersion.versionStatus, subjectArea.code, subjectArea.displayName), emptyMap(),
            "read-subject-area-metrics", "read-subject-area-dimensions", "read-subject-area-scenarios", "read-subject-area-member-sources") }
        is AnalysisDimensionCreateResult -> analysisDimension(operation, value)
        is ScenarioCreateResult -> with(value) { asset(operation, AssetSummary("SCENARIO", scenario.id, scenarioVersion.version, scenarioVersion.versionStatus, scenario.code, scenario.displayName), emptyMap(),
            "read-scenario-metrics", "read-scenario-dimensions", "read-scenario-filters", "read-scenario-pipeline-rules", "read-scenario-member-sources", "read-scenario-relation-filters") }
        is MemberDefinitionRecord -> asset(operation, AssetSummary("MEMBER_DEFINITION", value.id, value.currentVersion, value.status, value.code, value.displayName))
        is MemberSourceRecord -> receipt(operation, coordinate("MEMBER_SOURCE", value.id, value.subjectAreaVersion) + mapOf("subjectAreaId" to value.subjectAreaId,
            "subjectAreaVersion" to value.subjectAreaVersion, "code" to value.code, "displayName" to value.displayName), listOf(assetRead("MEMBER_SOURCE", value.id, value.subjectAreaVersion)))
        is SubjectAreaMetricRefCreateResult -> metricAdoption(operation, value)
        is TopologyLogicalTableRefCreateResult -> with(value.topologyLogicalTableRef) { receipt(operation, this, listOf(memberRead("read-topology-tables", topologyId, topologyVersion))) }
        is AnalysisDimensionEventMappingCreateResult -> with(value.mapping) { receipt(operation, this, listOf(memberRead("read-analysis-dimension-mappings", analysisDimensionId, analysisDimensionVersion))) }
        is AnalysisDimensionValueProviderCreateResult -> valueProvider(operation, value)
        is AnalysisDimensionValueAlignmentRecord -> valueAlignment(operation, value)
        is ScenarioMetricRefCreateResult -> with(value.ref) { receipt(operation, this, listOf(memberRead("read-scenario-metrics", scenarioId, scenarioVersion))) }
        is ScenarioAnalysisDimensionRefCreateResult -> with(value.ref) { receipt(operation, this, listOf(memberRead("read-scenario-dimensions", scenarioId, scenarioVersion))) }
        is ScenarioFilterRefCreateResult -> scenarioFilter(operation, value)
        is ScenarioPipelineRuleCreateResult -> pipelineRule(operation, value)
        is ScenarioMemberSourceRefRecord -> receipt(operation, mapOf("scenarioId" to value.scenarioId, "scenarioVersion" to value.scenarioVersion, "sourceId" to value.sourceId,
            "sourceVersion" to value.sourceVersion), listOf(memberRead("read-scenario-member-sources", value.scenarioId, value.scenarioVersion)))
        is ScenarioRelationFilterRecord -> receipt(operation, mapOf("scenarioId" to value.scenarioId, "scenarioVersion" to value.scenarioVersion, "code" to value.code),
            listOf(memberRead("read-scenario-relation-filters", value.scenarioId, value.scenarioVersion)))
        is GovernanceVersionActivationResult -> receipt(operation, value, assetReads(value.objectType, value.objectId, value.objectVersion))
        is GovernanceVersionDeprecationResult -> receipt(operation, value, assetReads(value.objectType, value.objectId, value.objectVersion))
        is SubjectAreaReleaseBuildResult -> receipt(operation, value, listOf(read("read-release-identity", mapOf("releaseId" to value.releaseId, "releaseVersion" to value.releaseVersion))))
        is RecordedWarehouseDiscovery -> sourceDiscovery(operation, value)
        else -> error("governance write result has no receipt projection: ${value.javaClass.name}")
    }
    private fun physical(table: PhysicalTableRecord, version: PhysicalTableVersionRecord) = AssetSummary("PHYSICAL_TABLE", table.id, version.version, version.versionStatus, table.code, table.displayName)
    private fun metric(operation: String, metric: MetricRecord, version: MetricVersionRecord) = asset(operation, AssetSummary("METRIC", metric.id, version.version, version.versionStatus, metric.code, metric.displayName))
    private fun dimensionBinding(operation: String, created: DimensionEventBindingCreateResult): Map<String, Any?> {
        val binding = created.binding
        val summary = AssetSummary("DIMENSION_EVENT_BINDING", binding.id, created.bindingVersion.version, created.bindingVersion.versionStatus, null, null)
        return receipt(operation, summary.fields() + mapOf("dimension" to coordinate("DIMENSION", binding.dimensionId, binding.dimensionVersion),
            "event" to coordinate("EVENT", binding.eventId, binding.eventVersion), "topology" to coordinate("TOPOLOGY", binding.topologyId, binding.topologyVersion)), summary.reads())
    }
    private fun analysisDimension(operation: String, created: AnalysisDimensionCreateResult): Map<String, Any?> {
        val dimension = created.analysisDimension
        val version = created.analysisDimensionVersion
        val summary = AssetSummary("ANALYSIS_DIMENSION", dimension.id, version.version, version.versionStatus, dimension.code, dimension.displayName)
        return receipt(operation, summary.fields() + mapOf("defaultLevel" to created.defaultLevel, "subjectAreaRef" to created.subjectAreaRef),
            summary.reads("read-analysis-dimension-levels", "read-analysis-dimension-mappings", "read-analysis-dimension-providers", "read-analysis-dimension-alignments"))
    }
    private fun metricAdoption(operation: String, created: SubjectAreaMetricRefCreateResult): Map<String, Any?> = with(created.subjectAreaMetricRef) {
        receipt(operation, owned("SUBJECT_AREA_METRIC_REF", id, "SUBJECT_AREA", subjectAreaId, subjectAreaVersion) + mapOf("code" to code, "displayName" to displayName,
            "status" to status, "metricId" to metricId, "metricVersion" to metricVersion, "instance" to instance), listOf(memberRead("read-subject-area-metrics", subjectAreaId, subjectAreaVersion)))
    }
    private fun valueProvider(operation: String, created: AnalysisDimensionValueProviderCreateResult): Map<String, Any?> = with(created.provider) {
        receipt(operation, owned("ANALYSIS_DIMENSION_VALUE_PROVIDER", id, "ANALYSIS_DIMENSION", analysisDimensionId, analysisDimensionVersion) + mapOf("levelCode" to levelCode,
            "providerType" to providerType, "sourceTableId" to sourceTableId, "sourceTableVersion" to sourceTableVersion, "sourceLogicUnitId" to sourceLogicUnitId,
            "sourceLogicUnitVersion" to sourceLogicUnitVersion, "status" to status), listOf(memberRead("read-analysis-dimension-providers", analysisDimensionId, analysisDimensionVersion)))
    }
    private fun valueAlignment(operation: String, value: AnalysisDimensionValueAlignmentRecord): Map<String, Any?> = with(value) {
        receipt(operation, owned("ANALYSIS_DIMENSION_VALUE_ALIGNMENT", id, "ANALYSIS_DIMENSION", analysisDimensionId, analysisDimensionVersion) + mapOf("levelCode" to levelCode,
            "alignmentType" to alignmentType, "confirmedBy" to confirmedBy, "diagnosticStatus" to diagnosticStatus), listOf(memberRead("read-analysis-dimension-alignments", analysisDimensionId, analysisDimensionVersion)))
    }
    private fun scenarioFilter(operation: String, created: ScenarioFilterRefCreateResult): Map<String, Any?> = with(created.ref) {
        receipt(operation, owned("SCENARIO_FILTER_REF", id, "SCENARIO", scenarioId, scenarioVersion) + mapOf("filterTargetType" to filterTargetType, "targetId" to targetId,
            "targetVersion" to targetVersion, "valueProviderId" to valueProviderId, "requiredByDefault" to requiredByDefault), listOf(memberRead("read-scenario-filters", scenarioId, scenarioVersion)))
    }
    private fun pipelineRule(operation: String, created: ScenarioPipelineRuleCreateResult): Map<String, Any?> = with(created.rule) {
        receipt(operation, owned("SCENARIO_PIPELINE_RULE", id, "SCENARIO", scenarioId, scenarioVersion) + mapOf("ruleType" to ruleType,
            "displayName" to displayName, "status" to status), listOf(memberRead("read-scenario-pipeline-rules", scenarioId, scenarioVersion)))
    }
    private fun sourceDiscovery(operation: String, value: RecordedWarehouseDiscovery): Map<String, Any?> = with(value.snapshot) {
        receipt(operation, mapOf("snapshotId" to id, "dataSourceId" to dataSource, "sourceIdentityHash" to sourceIdentityHash, "status" to status,
            "truncated" to value.truncated, "tableCount" to tableCount, "columnCount" to value.columns.size),
            listOf(read("read-source-discovery", mapOf("snapshotId" to id)), read("read-source-discovery-tables", mapOf("snapshotId" to id, "offset" to 0, "limit" to 50))))
    }
    private data class AssetSummary(val assetType: String, val id: String, val version: Long, val status: String, val code: String?, val displayName: String?) {
        fun fields(): Map<String, Any?> = coordinate(assetType, id, version) + mapOf("status" to status, "code" to code, "displayName" to displayName)
        fun reads(vararg members: String): List<Map<String, Any?>> = assetReads(assetType, id, version, *members)
    }
    private fun asset(operation: String, summary: AssetSummary, counts: Map<String, Int> = emptyMap(), vararg members: String): Map<String, Any?> =
        receipt(operation, if (counts.isEmpty()) summary.fields() else summary.fields() + ("counts" to counts), summary.reads(*members))
    private fun coordinate(type: String, id: String, version: Long): Map<String, Any?> = mapOf("assetType" to type, "id" to id, "version" to version)
    private fun owned(kind: String, id: String, ownerType: String, ownerId: String, ownerVersion: Long): Map<String, Any?> = mapOf("kind" to kind, "id" to id, "owner" to coordinate(ownerType, ownerId, ownerVersion))
    private fun assetReads(type: String, id: String, version: Long, vararg members: String): List<Map<String, Any?>> = buildList {
        add(assetRead(type, id, version))
        if (type != "MEMBER_SOURCE") add(read("read-asset-definition", coordinate(type, id, version)))
        members.forEach { add(memberRead(it, id, version)) }
    }
    private fun assetRead(type: String, id: String, version: Long) = read("read-asset-version", coordinate(type, id, version))
    private val pagedMembers = setOf("read-physical-columns", "read-subject-area-metrics", "read-subject-area-dimensions", "read-subject-area-scenarios", "read-subject-area-member-sources",
        "read-analysis-dimension-levels", "read-analysis-dimension-mappings", "read-analysis-dimension-providers", "read-analysis-dimension-alignments", "read-scenario-metrics", "read-scenario-dimensions",
        "read-scenario-filters", "read-scenario-pipeline-rules", "read-scenario-member-sources", "read-scenario-relation-filters")
    private fun memberRead(operation: String, id: String, version: Long): Map<String, Any?> {
        val input = mapOf("id" to id, "version" to version)
        return read(operation, if (operation in pagedMembers) input + mapOf("offset" to 0, "limit" to 50) else input)
    }
    private fun read(operation: String, payload: Map<String, Any?>): Map<String, Any?> = mapOf("operation" to operation, "payload" to payload)
    private fun receipt(operation: String, result: Any, reads: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("formatVersion" to "governance-write-receipt/v1", "operation" to operation, "result" to result, "reads" to reads)
}
