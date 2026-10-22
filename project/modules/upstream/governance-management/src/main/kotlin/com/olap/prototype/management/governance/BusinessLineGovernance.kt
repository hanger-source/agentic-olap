package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

class BusinessLineGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val workingVersions = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveBusinessLine(record: BusinessLineRecord) = identities.saveBusinessLine(record)
    fun findBusinessLine(id: String): BusinessLineRecord? = workingVersions.find("business_line_version", "business_line_id", requireText(id, "business_line.id"))?.let { snapshots.businessLine(it.objectId, it.version) }
    fun listBusinessLines(): List<BusinessLineRecord> = workingVersions.list("business_line_version", "business_line_id").map { snapshots.businessLine(it.objectId, it.version) }.sortedBy { it.code }
    fun businessLineIdentityExists(id: String): Boolean = identities.exists("business_line", id)
    fun businessLineVersionExists(id: String, version: Long): Boolean {
        requireText(id, "business_line_version.business_line_id"); requireVersion(version, "business_line_version.version")
        return try { GovernanceJdbcSupport.query(database, "SELECT 1 FROM business_line_version WHERE business_line_id=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check business_line_version: $id#$version", failure) }
    }
    fun saveBusinessLineVersion(record: BusinessLineVersionRecord) {
        GovernanceContract.requireVersionObject("business_line_version", "business_line_id", record.id, record.businessLineId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentProto, "business_line_version.content_proto")
        try { GovernanceJdbcSupport.insert(database, "business_line_version", linkedMapOf("id" to record.id, "business_line_id" to record.businessLineId, "version" to record.version, "version_status" to record.versionStatus, "content_proto" to record.contentProto, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion, "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt, "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save business_line_version: ${record.id}", failure) }
    }
}

class BusinessLineGovernanceService(private val database: PlatformDatabase) {
    private val repository = BusinessLineGovernanceRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)
    fun createBusinessLine(request: BusinessLineCreateRequest): BusinessLineCreateResult = database.inTransaction { write(request, null) }
    fun reviseBusinessLine(request: GovernanceRevisionRequest<BusinessLineCreateRequest>): BusinessLineCreateResult = database.inTransaction { write(request.definition, request) }
    private fun write(request: BusinessLineCreateRequest, revision: GovernanceRevisionRequest<BusinessLineCreateRequest>?): BusinessLineCreateResult {
        val code = requireText(request.code, "business_line.code").trim()
        if (!code.matches(Regex("[a-z][a-z0-9_]*"))) throw ProducerContractException("business_line.code must match [a-z][a-z0-9_]*")
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.BUSINESS_LINE, code, revision, request.createdBy)
        val actor = requireText(request.createdBy, "business_line.created_by").trim(); val now = Instant.now()
        val record = BusinessLineRecord(code, code, requireText(request.displayName, "business_line.display_name").trim(), request.description, requireText(request.owner, "business_line.owner").trim(), "DRAFT", draft.version, actor, now, actor, now)
        val content = try { com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(BusinessLineSnapshots.versionContent(record))) }
            catch (failure: Exception) { throw ProducerContractException("business line content_proto invalid", failure) }
        val version = BusinessLineVersionRecord("$code:v${draft.version}", code, draft.version, "DRAFT", content, "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        saveBusinessLine(record); saveBusinessLineVersion(version)
        return BusinessLineCreateResult(record, version)
    }
    internal fun saveBusinessLine(record: BusinessLineRecord) = repository.saveBusinessLine(record)
    internal fun saveBusinessLineVersion(record: BusinessLineVersionRecord) {
        if (!repository.businessLineIdentityExists(record.businessLineId)) throw ProducerContractException("business_line_version.business_line_id not found: ${record.businessLineId}")
        repository.saveBusinessLineVersion(record)
    }
    fun findBusinessLine(id: String): BusinessLineRecord? = repository.findBusinessLine(id)
    fun listBusinessLines(): List<BusinessLineRecord> = repository.listBusinessLines()
    fun businessLineSnapshot(id: String, version: Long): BusinessLineRecord = snapshots.businessLine(id, version)
    fun requireBusinessLineVersion(id: String, version: Long?, field: String) { businessLineSnapshot(requireText(id, "$field.business_line_id").trim(), requireVersion(version, field)) }
}
