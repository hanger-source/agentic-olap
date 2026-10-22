package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireDiagnosticSeverity
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

class DiagnosticLifecycleRepository(private val database: PlatformDatabase) {
    fun existsDiagnosticRun(id: String): Boolean = findDiagnosticRun(requireText(id, "diagnostic_run.id")) != null
    fun findDiagnosticRun(id: String): DiagnosticRunRecord? {
        requireText(id, "diagnostic_run.id")
        return try { GovernanceJdbcSupport.query(database, "SELECT id,target_object_type,target_object_id,target_object_version,stage,status,blocker_count,warning_count,info_count,pass_count,requested_by,requested_at,finished_at FROM diagnostic_run WHERE id=?", listOf(id)) { r -> DiagnosticRunRecord(r.getString("id"), r.getString("target_object_type"), r.getString("target_object_id"), GovernanceJdbcSupport.nullableLong(r, "target_object_version"), r.getString("stage"), r.getString("status"), r.getInt("blocker_count"), r.getInt("warning_count"), r.getInt("info_count"), r.getInt("pass_count"), r.getString("requested_by"), r.getTimestamp("requested_at").toInstant(), GovernanceJdbcSupport.instant(r, "finished_at")) }.singleOrNull() }
        catch (failure: Exception) { throw IllegalStateException("Failed to read diagnostic_run: $id", failure) }
    }
    fun requireDiagnosticRunTarget(diagnosticRunId: String, targetObjectType: String, targetObjectId: String, targetObjectVersion: Long?, field: String) {
        val run = findDiagnosticRun(diagnosticRunId) ?: throw ProducerContractException("$field not found: $diagnosticRunId")
        if (run.targetObjectType != targetObjectType || run.targetObjectId != targetObjectId || run.targetObjectVersion != targetObjectVersion)
            throw ProducerContractException("$field must belong to target object: $targetObjectType#$targetObjectId#$targetObjectVersion, actual diagnostic_run target is ${run.targetObjectType}#${run.targetObjectId}#${run.targetObjectVersion}")
    }
    fun saveDiagnosticRun(r: DiagnosticRunRecord) {
        requireText(r.id, "diagnostic_run.id"); requireText(r.targetObjectType, "diagnostic_run.target_object_type"); requireText(r.targetObjectId, "diagnostic_run.target_object_id"); requireText(r.stage, "diagnostic_run.stage"); requireText(r.status, "diagnostic_run.status"); requireText(r.requestedBy, "diagnostic_run.requested_by")
        insert("diagnostic_run", r.id, linkedMapOf("id" to r.id, "target_object_type" to r.targetObjectType, "target_object_id" to r.targetObjectId, "target_object_version" to r.targetObjectVersion, "stage" to r.stage, "status" to r.status, "blocker_count" to r.blockerCount, "warning_count" to r.warningCount, "info_count" to r.infoCount, "pass_count" to r.passCount, "requested_by" to r.requestedBy, "requested_at" to r.requestedAt, "finished_at" to r.finishedAt))
    }
    fun saveDiagnosticIssue(r: DiagnosticIssueRecord) {
        requireText(r.id, "diagnostic_issue.id"); requireText(r.diagnosticRunId, "diagnostic_issue.diagnostic_run_id")
        if (!existsDiagnosticRun(r.diagnosticRunId)) throw ProducerContractException("diagnostic_issue.diagnostic_run_id not found: ${r.diagnosticRunId}")
        requireText(r.code, "diagnostic_issue.code"); val severity = requireDiagnosticSeverity(r.severity, "diagnostic_issue.severity")
        requireText(r.message, "diagnostic_issue.message"); requireText(r.stage, "diagnostic_issue.stage"); requireText(r.diagnosticType, "diagnostic_issue.diagnostic_type")
        insert("diagnostic_issue", r.id, linkedMapOf("id" to r.id, "diagnostic_run_id" to r.diagnosticRunId, "code" to r.code, "severity" to severity, "message" to r.message, "target" to r.target, "target_version_ref" to r.targetVersionRef, "source" to r.source, "stage" to r.stage, "diagnostic_type" to r.diagnosticType, "blocker" to r.blocker, "page_blocking" to r.pageBlocking, "executable_blocking" to r.executableBlocking, "related_objects_json" to r.relatedObjectsJson, "action_json" to r.actionJson, "created_at" to r.createdAt))
    }
    fun saveLifecycleEvent(r: LifecycleEventRecord) {
        val table = "object_lifecycle_event"
        requireText(r.id, "$table.id"); requireText(r.objectType, "$table.object_type"); requireText(r.objectId, "$table.object_id"); requireText(r.eventType, "$table.event_type"); requireText(r.createdBy, "$table.created_by")
        r.diagnosticRunId?.let { requireDiagnosticRunTarget(it, r.objectType, r.objectId, r.objectVersion, "$table.diagnostic_run_id") }
        insert(table, r.id, linkedMapOf("id" to r.id, "object_type" to r.objectType, "object_id" to r.objectId, "object_version" to r.objectVersion, "event_type" to r.eventType, "reason" to r.reason, "diagnostic_run_id" to r.diagnosticRunId, "created_by" to r.createdBy, "created_at" to r.createdAt))
    }
    fun saveArchiveRequest(r: ArchiveRequestRecord) {
        val table = "archive_request"
        requireText(r.id, "$table.id"); requireText(r.objectType, "$table.object_type"); requireText(r.objectId, "$table.object_id"); requireText(r.requestedBy, "$table.requested_by"); requireText(r.diagnosticRunId, "$table.diagnostic_run_id")
        requireDiagnosticRunTarget(r.diagnosticRunId, r.objectType, r.objectId, null, "$table.diagnostic_run_id"); requireText(r.decision, "$table.decision")
        insert(table, r.id, linkedMapOf("id" to r.id, "object_type" to r.objectType, "object_id" to r.objectId, "requested_by" to r.requestedBy, "requested_at" to r.requestedAt, "reason" to r.reason, "diagnostic_run_id" to r.diagnosticRunId, "decision" to r.decision, "decided_by" to r.decidedBy, "decided_at" to r.decidedAt))
    }
    fun saveVersionDiffCache(r: VersionDiffCacheRecord) {
        val table = "version_diff_cache"
        requireText(r.id, "$table.id"); requireText(r.objectType, "$table.object_type"); requireText(r.objectId, "$table.object_id"); requireVersion(r.fromVersion, "$table.from_version"); requireVersion(r.toVersion, "$table.to_version"); requireText(r.diffJson, "$table.diff_json")
        try { GovernanceJdbcSupport.execute(database, "MERGE INTO version_diff_cache (id,object_type,object_id,from_version,to_version,diff_json,created_at) KEY(id) VALUES (?,?,?,?,?,?,?)", listOf(r.id, r.objectType, r.objectId, r.fromVersion, r.toVersion, r.diffJson, r.createdAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save $table: ${r.id}", failure) }
    }
    fun saveOperationAudit(r: OperationAuditRecord) {
        val table = "operation_audit"
        requireText(r.id, "$table.id"); requireText(r.actor, "$table.actor"); requireText(r.operation, "$table.operation"); requireText(r.targetObjectType, "$table.target_object_type"); requireText(r.targetObjectId, "$table.target_object_id")
        r.diagnosticRunId?.let { requireDiagnosticRunTarget(it, r.targetObjectType, r.targetObjectId, r.targetObjectVersion, "$table.diagnostic_run_id") }
        insert(table, r.id, linkedMapOf("id" to r.id, "actor" to r.actor, "operation" to r.operation, "target_object_type" to r.targetObjectType, "target_object_id" to r.targetObjectId, "target_object_version" to r.targetObjectVersion, "before_summary_json" to r.beforeSummaryJson, "after_summary_json" to r.afterSummaryJson, "diagnostic_run_id" to r.diagnosticRunId, "created_at" to r.createdAt))
    }
    private fun insert(table: String, id: String, fields: Map<String, Any?>) {
        try { GovernanceJdbcSupport.insert(database, table, fields) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save $table: $id", failure) }
    }
    data class DiagnosticRunRecord(val id: String, val targetObjectType: String, val targetObjectId: String, val targetObjectVersion: Long?, val stage: String, val status: String, val blockerCount: Int, val warningCount: Int, val infoCount: Int, val passCount: Int, val requestedBy: String, val requestedAt: Instant, val finishedAt: Instant?)
    data class DiagnosticIssueRecord(val id: String, val diagnosticRunId: String, val code: String, val severity: String, val message: String, val target: String?, val targetVersionRef: String?, val source: String?, val stage: String, val diagnosticType: String, val blocker: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean, val relatedObjectsJson: String?, val actionJson: String?, val createdAt: Instant)
    data class LifecycleEventRecord(val id: String, val objectType: String, val objectId: String, val objectVersion: Long?, val eventType: String, val reason: String?, val diagnosticRunId: String?, val createdBy: String, val createdAt: Instant)
    data class ArchiveRequestRecord(val id: String, val objectType: String, val objectId: String, val requestedBy: String, val requestedAt: Instant, val reason: String?, val diagnosticRunId: String, val decision: String, val decidedBy: String?, val decidedAt: Instant?)
    data class VersionDiffCacheRecord(val id: String, val objectType: String, val objectId: String, val fromVersion: Long, val toVersion: Long, val diffJson: String, val createdAt: Instant)
    data class OperationAuditRecord(val id: String, val actor: String, val operation: String, val targetObjectType: String, val targetObjectId: String, val targetObjectVersion: Long?, val beforeSummaryJson: String?, val afterSummaryJson: String?, val diagnosticRunId: String?, val createdAt: Instant)
}
