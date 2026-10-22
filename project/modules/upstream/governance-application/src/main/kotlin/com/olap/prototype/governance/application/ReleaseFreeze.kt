package com.olap.prototype.governance.application

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.SubjectAreaReleaseBuildResult
import com.olap.prototype.management.governance.CanonicalReleaseRepository.*
import com.olap.prototype.query.contract.QueryScenarioRef
import com.olap.prototype.release.contract.*

/** 一次冻结的完整输出，编译与持久化消费同一批行，不携带解析过程缓存。 */
class ReleaseFreeze(val release: SubjectAreaReleaseRecord, objects: List<ReleaseObjectRefRecord>, capabilities: List<ReleaseMetricDimensionCapabilityRecord>,
    unavailable: List<ReleaseUnavailableReasonRecord>, mappings: List<ReleaseDimensionMappingRecord>, timeAnchors: List<ReleaseTimeAnchorRuleRecord>,
    diagnostics: Map<QueryScenarioRef, List<ReleaseDiagnosticIssue>>) {
    val objects: List<ReleaseObjectRefRecord> = java.util.List.copyOf(objects)
    val capabilities: List<ReleaseMetricDimensionCapabilityRecord> = java.util.List.copyOf(capabilities)
    val unavailable: List<ReleaseUnavailableReasonRecord> = java.util.List.copyOf(unavailable)
    val mappings: List<ReleaseDimensionMappingRecord> = java.util.List.copyOf(mappings)
    val timeAnchors: List<ReleaseTimeAnchorRuleRecord> = java.util.List.copyOf(timeAnchors)
    val diagnostics: Map<QueryScenarioRef, List<ReleaseDiagnosticIssue>> = java.util.Collections.unmodifiableMap(diagnostics.mapValues { java.util.List.copyOf(it.value) })

    fun catalog(snapshot: ReleaseScenarioSnapshot): ReleaseCatalogView {
        val scenario = snapshot.scenario
        if (release.releaseId != scenario.releaseId || release.releaseVersion != scenario.releaseVersion || release.subjectAreaId != snapshot.subjectArea.subjectAreaId ||
            release.subjectAreaVersion != snapshot.subjectArea.subjectAreaVersion) throw ProducerContractException("scenario must belong to the exact frozen release")
        val issues = diagnostics[scenario] ?: throw ProducerContractException("scenario diagnostics were not captured during freezing")
        return ReleaseCatalogView(scenario.scenarioId, scenario.scenarioVersion, scenario.scenarioDisplayName, release.releaseId, release.releaseVersion, release.releaseDisplayName,
            objects.sortedWith(compareBy<ReleaseObjectRefRecord> { it.refRole }.thenBy { it.objectType }.thenBy { it.objectId }.thenBy { it.objectVersion }).map(::project),
            capabilities.filter { selected(it.scenarioId, it.scenarioVersion, scenario) }
                .sortedWith(compareBy<ReleaseMetricDimensionCapabilityRecord> { it.metricId }.thenBy { it.analysisDimensionId }.thenBy { it.levelCode }).map(::project),
            mappings.sortedWith(compareBy<ReleaseDimensionMappingRecord> { it.analysisDimensionId }.thenBy { it.levelCode }.thenBy { it.eventId }).map(::project),
            timeAnchors.sortedWith(compareBy<ReleaseTimeAnchorRuleRecord> { it.eventId }.thenBy { it.timeRole }).map(::project),
            unavailable.filter { selected(it.scenarioId, it.scenarioVersion, scenario) }
                .sortedWith(compareBy<ReleaseUnavailableReasonRecord> { it.scopeType }.thenBy { it.metricId }.thenBy { it.analysisDimensionId }.thenBy { it.levelCode }.thenBy { it.code }).map(::project), issues)
    }
    private fun selected(id: String?, version: Long?, scenario: QueryScenarioRef): Boolean = id == null || id == scenario.scenarioId && version == scenario.scenarioVersion
    private fun display(kind: String, id: String?, version: Long?): String = objects.firstOrNull { it.objectType == kind && it.objectId == id && it.objectVersion == version }?.objectDisplayName
        ?: throw ProducerContractException("frozen display object is missing: $kind#$id@$version")
    private fun levelDisplay(capability: ReleaseMetricDimensionCapabilityRecord): String? = mappings.filter {
        it.eventId == capability.metricEventId && it.eventVersion == capability.metricEventVersion && it.analysisDimensionId == capability.analysisDimensionId &&
            it.analysisDimensionVersion == capability.analysisDimensionVersion && it.levelCode == capability.levelCode
    }.minOfOrNull { it.levelDisplayName }
    fun result(): SubjectAreaReleaseBuildResult = SubjectAreaReleaseBuildResult(release.releaseId, release.releaseVersion, release.subjectAreaId, release.subjectAreaVersion,
        "BUILT", 1, objects.size, count("METRIC"), count("ANALYSIS_DIMENSION"), count("SCENARIO"), count("EVENT"), count("TOPOLOGY"), count("JOIN_PATH"),
        count("LOGIC_UNIT"), count("DIMENSION"), count("DIMENSION_EVENT_BINDING"), capabilities.size, unavailable.size)
    private fun count(kind: String): Int = objects.count { it.objectType == kind }
    private fun project(row: ReleaseObjectRefRecord) = ReleaseObjectRef(row.id, row.releaseId, row.releaseVersion, row.objectType, row.objectId, row.objectVersion,
        row.objectCode, row.objectDisplayName, row.refRole, row.snapshotProto, row.createdAt)
    private fun project(row: ReleaseMetricDimensionCapabilityRecord) = ReleaseMetricDimensionCapability(row.id, row.releaseId, row.releaseVersion, row.subjectAreaId,
        row.scenarioId, row.scenarioVersion, row.adoptionId, row.adoptionVersion, row.metricId, row.metricVersion, display("METRIC", row.metricId, row.metricVersion),
        row.metricEventId, row.metricEventVersion, row.analysisDimensionId, row.analysisDimensionVersion, display("ANALYSIS_DIMENSION", row.analysisDimensionId, row.analysisDimensionVersion),
        row.levelCode, levelDisplay(row), row.available, row.unavailableCode, row.unavailableReason, row.producerStage, row.targetRef, row.targetVersionRef,
        row.blocking, row.pageBlocking, row.executableBlocking, row.createdAt)
    private fun project(row: ReleaseDimensionMappingRecord) = ReleaseDimensionMapping(row.id, row.mappingId, row.releaseId, row.releaseVersion, row.subjectAreaId, row.eventId,
        row.eventVersion, row.analysisDimensionId, row.analysisDimensionVersion, display("ANALYSIS_DIMENSION", row.analysisDimensionId, row.analysisDimensionVersion), row.levelCode,
        row.levelDisplayName, row.dimensionId, row.dimensionVersion, row.dimensionCodeNamespace, row.dimensionCodeNamespaceDisplayName, row.dimensionDisplayName, row.bindingId,
        row.bindingVersion, row.valueAlignmentKey, row.valueAlignmentStatus, row.topologyId, row.topologyVersion, row.topologyImplementationNamespaceCode,
        row.topologyImplementationNamespaceDisplayName, row.logicUnitId, row.logicUnitVersion, row.logicUnitDisplayName, row.joinPathRefs, row.expression, row.createdAt)
    private fun project(row: ReleaseTimeAnchorRuleRecord) = ReleaseTimeAnchorRule(row.id, row.releaseId, row.releaseVersion, row.subjectAreaId, row.scenarioId, row.scenarioVersion,
        row.eventId, row.eventVersion, row.topologyId, row.topologyVersion, row.topologyImplementationNamespaceCode, row.topologyImplementationNamespaceDisplayName,
        row.effectiveTimeLogicUnitId, row.effectiveTimeLogicUnitVersion, row.effectiveTimeLogicUnitDisplayName, row.timeRole,
        requireNotNull(row.analysisDimensionId) { "release time anchor analysis dimension is required" },
        requireNotNull(row.analysisDimensionVersion) { "release time anchor analysis dimension version is required" },
        display("ANALYSIS_DIMENSION", row.analysisDimensionId, row.analysisDimensionVersion),
        requireNotNull(row.levelCode) { "release time anchor level is required" }, row.partitionLogicUnitId,
        row.partitionLogicUnitVersion, row.diagnosticJson, row.createdAt)
    private fun project(row: ReleaseUnavailableReasonRecord) = ReleaseUnavailableReason(row.id, row.releaseId, row.releaseVersion, row.scopeType, row.scenarioId, row.scenarioVersion,
        row.metricId, row.metricVersion, row.analysisDimensionId, row.analysisDimensionVersion, row.levelCode, row.code, row.reason, row.producerStage, row.targetRef,
        row.targetVersionRef, row.blocking, row.pageBlocking, row.executableBlocking, row.createdAt)

    override fun equals(other: Any?): Boolean = other is ReleaseFreeze && release == other.release && objects == other.objects && capabilities == other.capabilities &&
        unavailable == other.unavailable && mappings == other.mappings && timeAnchors == other.timeAnchors && diagnostics == other.diagnostics
    override fun hashCode(): Int = listOf(release, objects, capabilities, unavailable, mappings, timeAnchors, diagnostics).hashCode()
}
