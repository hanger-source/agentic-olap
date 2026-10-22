package com.olap.prototype.governance.contract

import com.olap.prototype.event.RelationAccessPath
import java.time.Instant
import java.util.Collections

data class DimensionCreateRequest(val code: String, val dimensionCodeNamespaceDisplayName: String, val displayName: String, val owner: String, val description: String?, val createdBy: String)
data class DimensionCreateResult(val dimension: DimensionRecord, val dimensionVersion: DimensionVersionRecord)
data class DimensionRecord(val id: String, val code: String, val dimensionCodeNamespace: String, val dimensionCodeNamespaceDisplayName: String, val localCode: String, val displayName: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class DimensionVersionRecord(val id: String, val dimensionId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
class DimensionEventBindingCreateRequest(val dimensionId: String, val dimensionVersion: Long, val eventId: String, val eventVersion: Long, val logicUnitId: String?, val logicUnitVersion: Long?, val expressionJson: String?, val accessPath: RelationAccessPath?, roles: Map<String, RelationAccessPath>, val createdBy: String) {
    val roles: Map<String, RelationAccessPath> = Collections.unmodifiableMap(LinkedHashMap(roles))
    override fun equals(other: Any?): Boolean = other is DimensionEventBindingCreateRequest && dimensionId == other.dimensionId && dimensionVersion == other.dimensionVersion && eventId == other.eventId && eventVersion == other.eventVersion && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && expressionJson == other.expressionJson && accessPath == other.accessPath && roles == other.roles && createdBy == other.createdBy
    override fun hashCode(): Int = listOf(dimensionId, dimensionVersion, eventId, eventVersion, logicUnitId, logicUnitVersion, expressionJson, accessPath, roles, createdBy).hashCode()
}
data class DimensionEventBindingCreateResult(val binding: DimensionEventBindingRecord, val bindingVersion: DimensionEventBindingVersionRecord)
data class DimensionEventBindingRecord(val id: String, val dimensionId: String, val dimensionVersion: Long, val eventId: String, val eventVersion: Long, val topologyId: String, val topologyVersion: Long, val logicUnitId: String?, val logicUnitVersion: Long?, val expressionJson: String?, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class DimensionEventBindingVersionRecord(val id: String, val bindingId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class DimensionEventBindingCandidateRecord(val bindingId: String, val bindingVersion: Long, val dimensionId: String, val dimensionVersion: Long, val eventId: String, val eventVersion: Long, val eventDisplayName: String, val topologyId: String, val topologyVersion: Long, val logicUnitId: String?, val logicUnitVersion: Long?, val logicUnitDisplayName: String?, val status: String)
data class DimensionEventBindingDimensionCandidateRecord(val id: String, val code: String, val dimensionCodeNamespace: String, val dimensionCodeNamespaceDisplayName: String, val localCode: String, val displayName: String, val owner: String, val status: String, val currentVersion: Long, val disabledReason: String?)
class DimensionEventBindingContextRecord(val event: EventRecord, topologyLogicalTableRefs: List<TopologyLogicalTableRefRecord>, dimensionCandidates: List<DimensionEventBindingDimensionCandidateRecord>, reachableLogicUnits: List<ReachableLogicUnitCandidateRecord>) {
    val topologyLogicalTableRefs: List<TopologyLogicalTableRefRecord> = java.util.List.copyOf(topologyLogicalTableRefs)
    val dimensionCandidates: List<DimensionEventBindingDimensionCandidateRecord> = java.util.List.copyOf(dimensionCandidates)
    val reachableLogicUnits: List<ReachableLogicUnitCandidateRecord> = java.util.List.copyOf(reachableLogicUnits)
    override fun equals(other: Any?): Boolean = other is DimensionEventBindingContextRecord && event == other.event && topologyLogicalTableRefs == other.topologyLogicalTableRefs && dimensionCandidates == other.dimensionCandidates && reachableLogicUnits == other.reachableLogicUnits
    override fun hashCode(): Int = listOf(event, topologyLogicalTableRefs, dimensionCandidates, reachableLogicUnits).hashCode()
}
