package com.olap.prototype.governance.contract

import java.time.Instant
import java.util.Collections

data class TopologyCreateRequest(val code: String, val displayName: String, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val backboneLogicalTableId: String, val backboneLogicalTableVersion: Long, val owner: String, val description: String?, val createdBy: String)
data class TopologyCreateResult(val topology: TopologyRecord, val topologyVersion: TopologyVersionRecord, val backboneRef: TopologyLogicalTableRefRecord)
data class TopologyRecord(val id: String, val code: String, val displayName: String, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val backboneLogicalTableId: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class TopologyVersionRecord(val id: String, val topologyId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class TopologyJoinPathRefRecord(val id: String, val topologyId: String, val topologyVersion: Long, val joinPathId: String, val joinPathVersion: Long, val createdAt: Instant)
data class TopologyLogicalTableRefRecord(val id: String, val topologyId: String, val topologyVersion: Long, val logicalTableId: String, val logicalTableVersion: Long, val usageRole: String, val createdAt: Instant)
data class TopologyLogicalTableRefCreateRequest(val topologyId: String, val topologyVersion: Long, val logicalTableId: String, val logicalTableVersion: Long, val usageRole: TopologyNodeRole)
data class TopologyLogicalTableRefCreateResult(val topologyLogicalTableRef: TopologyLogicalTableRefRecord)
data class TopologyJoinKeyCandidateRecord(val columnName: String, val logicUnitVersion: Long, val columnType: String, val columnTypeDisplayName: String, val displayName: String, val filterable: Boolean, val primaryKeyCandidate: Boolean)
data class TopologyTableCandidateRecord(val logicalTableId: String, val logicalTableVersion: Long, val tableName: String, val displayName: String, val role: String, val tableKind: String, val domain: String?, val status: String, val ready: Boolean, val disabledReason: String?)
class TopologyBindingContextRecord(val topology: TopologyRecord, topologyLogicalTableRefs: List<TopologyLogicalTableRefRecord>, logicalTables: List<LogicalTableRecord>, reachableLogicUnits: List<ReachableLogicUnitCandidateRecord>, joinPaths: List<JoinPathRecord>, joinPathKeyRefs: List<JoinPathKeyRefRecord>, targetCandidatesBySource: Map<String, List<TopologyTableCandidateRecord>>, keyCandidatesByTable: Map<String, List<TopologyJoinKeyCandidateRecord>>) {
    val topologyLogicalTableRefs: List<TopologyLogicalTableRefRecord> = java.util.List.copyOf(topologyLogicalTableRefs)
    val logicalTables: List<LogicalTableRecord> = java.util.List.copyOf(logicalTables)
    val reachableLogicUnits: List<ReachableLogicUnitCandidateRecord> = java.util.List.copyOf(reachableLogicUnits)
    val joinPaths: List<JoinPathRecord> = java.util.List.copyOf(joinPaths)
    val joinPathKeyRefs: List<JoinPathKeyRefRecord> = java.util.List.copyOf(joinPathKeyRefs)
    val targetCandidatesBySource: Map<String, List<TopologyTableCandidateRecord>> = Collections.unmodifiableMap(targetCandidatesBySource.mapValuesTo(linkedMapOf()) { java.util.List.copyOf(it.value) })
    val keyCandidatesByTable: Map<String, List<TopologyJoinKeyCandidateRecord>> = Collections.unmodifiableMap(keyCandidatesByTable.mapValuesTo(linkedMapOf()) { java.util.List.copyOf(it.value) })
    override fun equals(other: Any?): Boolean = other is TopologyBindingContextRecord && topology == other.topology && topologyLogicalTableRefs == other.topologyLogicalTableRefs && logicalTables == other.logicalTables && reachableLogicUnits == other.reachableLogicUnits && joinPaths == other.joinPaths && joinPathKeyRefs == other.joinPathKeyRefs && targetCandidatesBySource == other.targetCandidatesBySource && keyCandidatesByTable == other.keyCandidatesByTable
    override fun hashCode(): Int = listOf(topology, topologyLogicalTableRefs, logicalTables, reachableLogicUnits, joinPaths, joinPathKeyRefs, targetCandidatesBySource, keyCandidatesByTable).hashCode()
}
data class JoinPathRecord(val id: String, val topologyId: String, val topologyVersion: Long, val code: String, val sourceLogicalTableId: String, val sourceLogicalTableVersion: Long, val targetLogicalTableId: String, val targetLogicalTableVersion: Long, val joinType: String, val cardinality: String, val temporalConfigJson: String?, val nullPolicy: String?, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant)
data class JoinPathVersionRecord(val id: String, val joinPathId: String, val version: Long, val versionStatus: String, val contentJson: String, val joinType: String, val cardinality: String, val temporalConfigJson: String?, val nullPolicy: String?, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class JoinPathKeyRefRecord(val id: String, val joinPathId: String, val joinPathVersion: Long, val keyOrder: Int, val sourceLogicUnitId: String, val sourceLogicUnitVersion: Long, val targetLogicUnitId: String, val targetLogicUnitVersion: Long, val createdAt: Instant)
class JoinPathCreateRequest(val topologyId: String, val topologyVersion: Long, val code: String, val sourceLogicalTableId: String, val sourceLogicalTableVersion: Long, val targetLogicalTableId: String, val targetLogicalTableVersion: Long, val joinType: String, val cardinality: String, val temporalConfigJson: String?, val nullPolicy: String, val createdBy: String, keyRefs: List<KeyRefInput>) {
    val keyRefs: List<KeyRefInput> = java.util.List.copyOf(keyRefs)
    data class KeyRefInput(val sourceLogicUnitId: String, val sourceLogicUnitVersion: Long, val targetLogicUnitId: String, val targetLogicUnitVersion: Long)
    override fun equals(other: Any?): Boolean = other is JoinPathCreateRequest && topologyId == other.topologyId && topologyVersion == other.topologyVersion && code == other.code && sourceLogicalTableId == other.sourceLogicalTableId && sourceLogicalTableVersion == other.sourceLogicalTableVersion && targetLogicalTableId == other.targetLogicalTableId && targetLogicalTableVersion == other.targetLogicalTableVersion && joinType == other.joinType && cardinality == other.cardinality && temporalConfigJson == other.temporalConfigJson && nullPolicy == other.nullPolicy && createdBy == other.createdBy && keyRefs == other.keyRefs
    override fun hashCode(): Int = listOf(topologyId, topologyVersion, code, sourceLogicalTableId, sourceLogicalTableVersion, targetLogicalTableId, targetLogicalTableVersion, joinType, cardinality, temporalConfigJson, nullPolicy, createdBy, keyRefs).hashCode()
}
class JoinPathCreateResult(val joinPath: JoinPathRecord, val joinPathVersion: JoinPathVersionRecord, val topologyJoinPathRef: TopologyJoinPathRefRecord, keyRefs: List<JoinPathKeyRefRecord>) {
    val keyRefs: List<JoinPathKeyRefRecord> = java.util.List.copyOf(keyRefs)
    override fun equals(other: Any?): Boolean = other is JoinPathCreateResult && joinPath == other.joinPath && joinPathVersion == other.joinPathVersion && topologyJoinPathRef == other.topologyJoinPathRef && keyRefs == other.keyRefs
    override fun hashCode(): Int = listOf(joinPath, joinPathVersion, topologyJoinPathRef, keyRefs).hashCode()
}
