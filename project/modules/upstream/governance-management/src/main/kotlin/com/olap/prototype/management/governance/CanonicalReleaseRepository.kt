package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.contract.ProducerContracts.requireVersionedRefPair
import com.olap.prototype.query.ValueAlignmentContract
import com.olap.prototype.release.contract.ReleaseObjectKind
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet
import java.time.Instant

/** 封存冻结与编译已决定的行；不重新读取或解释工作定义。 */
class CanonicalReleaseRepository(private val database: PlatformDatabase) {
    fun <T> inTransaction(work: () -> T): T = database.inTransaction(work)
    fun save(release: SubjectAreaReleaseRecord, objects: List<ReleaseObjectRefRecord>, capabilities: List<ReleaseMetricDimensionCapabilityRecord>, unavailable: List<ReleaseUnavailableReasonRecord>, mappings: List<ReleaseDimensionMappingRecord>, timeAnchors: List<ReleaseTimeAnchorRuleRecord>) {
        saveRelease(release); objects.forEach(::saveReleaseObjectRef); mappings.forEach(::saveDimensionMapping)
        timeAnchors.forEach(::saveTimeAnchorRule); capabilities.forEach(::saveMetricDimensionCapability); unavailable.forEach(::saveUnavailableReason)
    }
    fun existsRelease(id: String, version: String): Boolean {
        requireText(id, "subject_area_release.release_id"); requireText(version, "subject_area_release.release_version")
        return exists("Failed to check subject_area_release: $id#$version", "SELECT 1 FROM subject_area_release WHERE release_id=? AND release_version=?", listOf(id, version))
    }
    fun existsReleaseObjectRef(releaseId: String, releaseVersion: String, objectType: String, objectId: String, objectVersion: Long?): Boolean {
        requireText(releaseId, "release_object_ref.release_id"); requireText(releaseVersion, "release_object_ref.release_version")
        val type = ReleaseObjectKind.require(objectType).name
        requireText(objectId, "release_object_ref.object_id"); val version = requireVersion(objectVersion, "release_object_ref.object_version")
        return exists("Failed to check release_object_ref: $releaseId#$releaseVersion -> $type:$objectId@$version", "SELECT 1 FROM release_object_ref WHERE release_id=? AND release_version=? AND object_type=? AND object_id=? AND object_version=?", listOf(releaseId, releaseVersion, type, objectId, version))
    }
    fun findRelease(id: String, version: String): SubjectAreaReleaseRecord? {
        requireText(id, "subject_area_release.release_id"); requireText(version, "subject_area_release.release_version")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM subject_area_release WHERE release_id=? AND release_version=?", listOf(id, version), ::release).singleOrNull() }
        catch (failure: Exception) { throw IllegalStateException("Failed to find subject_area_release: $id#$version", failure) }
    }
    fun existsReleaseForSubjectArea(releaseId: String, releaseVersion: String, subjectAreaId: String, subjectAreaVersion: Long?): Boolean {
        requireText(releaseId, "subject_area_release.release_id"); requireText(releaseVersion, "subject_area_release.release_version"); requireText(subjectAreaId, "subject_area_release.subject_area_id")
        val version = requireVersion(subjectAreaVersion, "subject_area_release.subject_area_version")
        return exists("Failed to check subject_area_release ownership: $releaseId#$releaseVersion", "SELECT 1 FROM subject_area_release WHERE release_id=? AND release_version=? AND subject_area_id=? AND subject_area_version=?", listOf(releaseId, releaseVersion, subjectAreaId, version))
    }
    fun listReleasesBySubjectArea(id: String): List<SubjectAreaReleaseRecord> {
        requireText(id, "subject_area_release.subject_area_id")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM subject_area_release WHERE subject_area_id=? ORDER BY created_at DESC, release_version DESC", listOf(id), ::release) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list subject_area_release by subject area: $id", failure) }
    }
    fun saveRelease(r: SubjectAreaReleaseRecord) {
        requireText(r.id, "subject_area_release.id"); requireText(r.releaseId, "subject_area_release.release_id"); requireText(r.releaseDisplayName, "subject_area_release.release_display_name"); requireText(r.subjectAreaId, "subject_area_release.subject_area_id"); requireText(r.releaseVersion, "subject_area_release.release_version"); requireVersion(r.subjectAreaVersion, "subject_area_release.subject_area_version"); requireText(r.engineLoadStatus, "subject_area_release.engine_load_status"); requireText(r.createdBy, "subject_area_release.created_by")
        insert("subject_area_release", r.id, linkedMapOf("id" to r.id, "release_id" to r.releaseId, "release_display_name" to r.releaseDisplayName, "subject_area_id" to r.subjectAreaId, "release_version" to r.releaseVersion, "subject_area_version" to r.subjectAreaVersion, "engine_load_status" to r.engineLoadStatus, "created_by" to r.createdBy, "created_at" to r.createdAt))
    }
    fun saveReleaseObjectRef(r: ReleaseObjectRefRecord) {
        requireText(r.id, "release_object_ref.id"); requireText(r.releaseId, "release_object_ref.release_id"); requireText(r.releaseVersion, "release_object_ref.release_version")
        val kind = ReleaseObjectKind.require(r.objectType)
        requireText(r.objectId, "release_object_ref.object_id"); requireVersion(r.objectVersion, "release_object_ref.object_version"); requireText(r.objectCode, "release_object_ref.object_code"); requireText(r.objectDisplayName, "release_object_ref.object_display_name")
        val role = kind.requireRefRole(r.refRole)
        insert("release_object_ref", r.id, linkedMapOf("id" to r.id, "release_id" to r.releaseId, "release_version" to r.releaseVersion, "object_type" to kind.name, "object_id" to r.objectId, "object_version" to r.objectVersion, "object_code" to r.objectCode, "object_display_name" to r.objectDisplayName, "ref_role" to role, "snapshot_summary_json" to r.snapshotSummaryJson, "created_at" to r.createdAt))
    }
    fun saveMetricDimensionCapability(r: ReleaseMetricDimensionCapabilityRecord) {
        val table = "release_metric_dimension_capability"
        requireText(r.id, "$table.id"); requireText(r.releaseId, "$table.release_id"); requireText(r.releaseVersion, "$table.release_version"); requireText(r.subjectAreaId, "$table.subject_area_id")
        requireVersionedRefPair(r.scenarioId, r.scenarioVersion, "$table.scenario")
        requireText(r.metricId, "$table.metric_id"); requireText(r.adoptionId, "$table.adoption_id"); requireVersion(r.adoptionVersion, "$table.adoption_version"); requireVersion(r.metricVersion, "$table.metric_version")
        requireText(r.metricEventId, "$table.metric_event_id"); requireVersion(r.metricEventVersion, "$table.metric_event_version"); requireText(r.analysisDimensionId, "$table.analysis_dimension_id"); requireVersion(r.analysisDimensionVersion, "$table.analysis_dimension_version")
        insert(table, r.id, linkedMapOf("id" to r.id, "release_id" to r.releaseId, "release_version" to r.releaseVersion, "subject_area_id" to r.subjectAreaId, "scenario_id" to r.scenarioId, "scenario_version" to r.scenarioVersion, "adoption_id" to r.adoptionId, "adoption_version" to r.adoptionVersion, "metric_id" to r.metricId, "metric_version" to r.metricVersion, "metric_event_id" to r.metricEventId, "metric_event_version" to r.metricEventVersion, "analysis_dimension_id" to r.analysisDimensionId, "analysis_dimension_version" to r.analysisDimensionVersion, "level_code" to r.levelCode, "available" to r.available, "unavailable_code" to r.unavailableCode, "unavailable_reason" to r.unavailableReason, "producer_stage" to r.producerStage, "target_ref" to r.targetRef, "target_version_ref" to r.targetVersionRef, "blocking" to r.blocking, "page_blocking" to r.pageBlocking, "executable_blocking" to r.executableBlocking, "created_at" to r.createdAt))
    }
    fun saveTimeAnchorRule(r: ReleaseTimeAnchorRuleRecord) {
        val table = "release_time_anchor_rule"
        requireText(r.id, "$table.id"); requireText(r.releaseId, "$table.release_id"); requireText(r.releaseVersion, "$table.release_version"); requireText(r.subjectAreaId, "$table.subject_area_id")
        requireVersionedRefPair(r.scenarioId, r.scenarioVersion, "$table.scenario"); requireText(r.eventId, "$table.event_id"); requireVersion(r.eventVersion, "$table.event_version")
        requireText(r.topologyId, "$table.topology_id"); requireVersion(r.topologyVersion, "$table.topology_version"); requireText(r.topologyImplementationNamespaceCode, "$table.topology_implementation_namespace_code"); requireText(r.topologyImplementationNamespaceDisplayName, "$table.topology_implementation_namespace_display_name")
        requireText(r.effectiveTimeLogicUnitId, "$table.effective_time_logic_unit_id"); requireVersion(r.effectiveTimeLogicUnitVersion, "$table.effective_time_logic_unit_version"); requireText(r.effectiveTimeLogicUnitDisplayName, "$table.effective_time_logic_unit_display_name"); requireText(r.timeRole, "$table.time_role")
        requireVersionedRefPair(r.analysisDimensionId, r.analysisDimensionVersion, "$table.analysis_dimension"); requireVersionedRefPair(r.partitionLogicUnitId, r.partitionLogicUnitVersion, "$table.partition_logic_unit")
        insert(table, r.id, linkedMapOf("id" to r.id, "release_id" to r.releaseId, "release_version" to r.releaseVersion, "subject_area_id" to r.subjectAreaId, "scenario_id" to r.scenarioId, "scenario_version" to r.scenarioVersion, "event_id" to r.eventId, "event_version" to r.eventVersion, "topology_id" to r.topologyId, "topology_version" to r.topologyVersion, "topology_implementation_namespace_code" to r.topologyImplementationNamespaceCode, "topology_implementation_namespace_display_name" to r.topologyImplementationNamespaceDisplayName, "effective_time_logic_unit_id" to r.effectiveTimeLogicUnitId, "effective_time_logic_unit_version" to r.effectiveTimeLogicUnitVersion, "effective_time_logic_unit_display_name" to r.effectiveTimeLogicUnitDisplayName, "time_role" to r.timeRole, "analysis_dimension_id" to r.analysisDimensionId, "analysis_dimension_version" to r.analysisDimensionVersion, "level_code" to r.levelCode, "alias_symbols_json" to r.aliasSymbolsJson, "partition_logic_unit_id" to r.partitionLogicUnitId, "partition_logic_unit_version" to r.partitionLogicUnitVersion, "diagnostic_json" to r.diagnosticJson, "created_at" to r.createdAt))
    }
    fun timeAnchorRuleExists(id: String): Boolean {
        requireText(id, "release_time_anchor_rule.id")
        return exists("Failed to check release_time_anchor_rule: $id", "SELECT 1 FROM release_time_anchor_rule WHERE id=?", listOf(id))
    }
    fun saveDimensionMapping(r: ReleaseDimensionMappingRecord) {
        requireText(r.mappingId, "release_dimension_mapping.mapping_id")
        ValueAlignmentContract.requireEvidenceReference(r.valueAlignmentStatus, r.valueAlignmentKey, "release_dimension_mapping")
        try { GovernanceJdbcSupport.insert(database, "release_dimension_mapping", linkedMapOf("id" to r.id, "mapping_id" to r.mappingId, "release_id" to r.releaseId, "release_version" to r.releaseVersion, "subject_area_id" to r.subjectAreaId, "event_id" to r.eventId, "event_version" to r.eventVersion, "analysis_dimension_id" to r.analysisDimensionId, "analysis_dimension_version" to r.analysisDimensionVersion, "level_code" to r.levelCode, "level_display_name" to r.levelDisplayName, "dimension_id" to r.dimensionId, "dimension_version" to r.dimensionVersion, "dimension_code_namespace" to r.dimensionCodeNamespace, "dimension_code_namespace_display_name" to r.dimensionCodeNamespaceDisplayName, "dimension_display_name" to r.dimensionDisplayName, "binding_id" to r.bindingId, "binding_version" to r.bindingVersion, "value_alignment_key" to r.valueAlignmentKey, "value_alignment_status" to r.valueAlignmentStatus, "topology_id" to r.topologyId, "topology_version" to r.topologyVersion, "topology_implementation_namespace_code" to r.topologyImplementationNamespaceCode, "topology_implementation_namespace_display_name" to r.topologyImplementationNamespaceDisplayName, "logic_unit_id" to r.logicUnitId, "logic_unit_version" to r.logicUnitVersion, "logic_unit_display_name" to r.logicUnitDisplayName, "join_path_refs_json" to r.joinPathRefsJson, "expression_json" to r.expressionJson, "created_at" to r.createdAt)) }
        catch (failure: Exception) { throw IllegalStateException("cannot freeze exact dimension/event mapping: ${r.id}", failure) }
    }
    fun saveUnavailableReason(r: ReleaseUnavailableReasonRecord) {
        val table = "release_unavailable_reason"
        requireText(r.id, "$table.id"); requireText(r.releaseId, "$table.release_id"); requireText(r.releaseVersion, "$table.release_version"); requireText(r.scopeType, "$table.scope_type")
        requireVersionedRefPair(r.scenarioId, r.scenarioVersion, "$table.scenario"); requireVersionedRefPair(r.metricId, r.metricVersion, "$table.metric"); requireVersionedRefPair(r.analysisDimensionId, r.analysisDimensionVersion, "$table.analysis_dimension")
        requireText(r.code, "$table.code"); requireText(r.reason, "$table.reason"); requireText(r.producerStage, "$table.producer_stage"); requireText(r.targetRef, "$table.target_ref"); requireText(r.targetVersionRef, "$table.target_version_ref")
        insert(table, r.id, linkedMapOf("id" to r.id, "release_id" to r.releaseId, "release_version" to r.releaseVersion, "scope_type" to r.scopeType, "subject_area_id" to r.subjectAreaId, "scenario_id" to r.scenarioId, "scenario_version" to r.scenarioVersion, "metric_id" to r.metricId, "metric_version" to r.metricVersion, "analysis_dimension_id" to r.analysisDimensionId, "analysis_dimension_version" to r.analysisDimensionVersion, "level_code" to r.levelCode, "code" to r.code, "reason" to r.reason, "producer_stage" to r.producerStage, "target_ref" to r.targetRef, "target_version_ref" to r.targetVersionRef, "blocking" to r.blocking, "page_blocking" to r.pageBlocking, "executable_blocking" to r.executableBlocking, "created_at" to r.createdAt))
    }
    private fun insert(table: String, id: String, fields: Map<String, Any?>) {
        try { GovernanceJdbcSupport.insert(database, table, fields) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save $table: $id", failure) }
    }
    private fun exists(message: String, sql: String, values: List<Any?>): Boolean = try { GovernanceJdbcSupport.query(database, sql, values) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException(message, failure) }
    private fun release(rows: ResultSet): SubjectAreaReleaseRecord = SubjectAreaReleaseRecord(rows.getString("id"), rows.getString("release_id"), rows.getString("release_display_name"), rows.getString("subject_area_id"), rows.getString("release_version"), rows.getLong("subject_area_version"), rows.getString("engine_load_status"), rows.getString("created_by"), rows.getTimestamp("created_at").toInstant())

    data class SubjectAreaReleaseRecord(val id: String, val releaseId: String, val releaseDisplayName: String, val subjectAreaId: String, val releaseVersion: String, val subjectAreaVersion: Long, val engineLoadStatus: String, val createdBy: String, val createdAt: Instant)
    data class ReleaseObjectRefRecord(val id: String, val releaseId: String, val releaseVersion: String, val objectType: String, val objectId: String, val objectVersion: Long, val objectCode: String, val objectDisplayName: String, val refRole: String, val snapshotSummaryJson: String, val createdAt: Instant)
    data class ReleaseMetricDimensionCapabilityRecord(val id: String, val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val scenarioId: String?, val scenarioVersion: Long?, val adoptionId: String, val adoptionVersion: Long, val metricId: String, val metricVersion: Long, val metricEventId: String, val metricEventVersion: Long, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String?, val available: Boolean, val unavailableCode: String?, val unavailableReason: String?, val producerStage: String?, val targetRef: String?, val targetVersionRef: String?, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean, val createdAt: Instant)
    data class ReleaseTimeAnchorRuleRecord(val id: String, val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val scenarioId: String?, val scenarioVersion: Long?, val eventId: String, val eventVersion: Long, val topologyId: String, val topologyVersion: Long, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val effectiveTimeLogicUnitId: String, val effectiveTimeLogicUnitVersion: Long, val effectiveTimeLogicUnitDisplayName: String, val timeRole: String, val analysisDimensionId: String?, val analysisDimensionVersion: Long?, val levelCode: String?, val aliasSymbolsJson: String?, val partitionLogicUnitId: String?, val partitionLogicUnitVersion: Long?, val diagnosticJson: String?, val createdAt: Instant)
    data class ReleaseDimensionMappingRecord(val id: String, val mappingId: String, val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val eventId: String, val eventVersion: Long, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val levelDisplayName: String, val dimensionId: String, val dimensionVersion: Long, val dimensionCodeNamespace: String, val dimensionCodeNamespaceDisplayName: String, val dimensionDisplayName: String, val bindingId: String, val bindingVersion: Long, val valueAlignmentKey: String?, val valueAlignmentStatus: String, val topologyId: String, val topologyVersion: Long, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val logicUnitId: String, val logicUnitVersion: Long, val logicUnitDisplayName: String, val joinPathRefsJson: String, val expressionJson: String, val createdAt: Instant)
    data class ReleaseUnavailableReasonRecord(val id: String, val releaseId: String, val releaseVersion: String, val scopeType: String, val subjectAreaId: String?, val scenarioId: String?, val scenarioVersion: Long?, val metricId: String?, val metricVersion: Long?, val analysisDimensionId: String?, val analysisDimensionVersion: Long?, val levelCode: String?, val code: String, val reason: String, val producerStage: String, val targetRef: String, val targetVersionRef: String, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean, val createdAt: Instant)
}
