package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant

class SubjectAreaGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val workingVersions = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveSubjectArea(record: SubjectAreaRecord) = identities.saveSubjectArea(record)
    fun findSubjectArea(id: String): SubjectAreaRecord? = workingVersions.find("subject_area_version", "subject_area_id", requireText(id, "subject_area.id"))?.let { snapshots.subjectArea(it.objectId, it.version) }
    fun listSubjectAreas(): List<SubjectAreaRecord> = workingVersions.list("subject_area_version", "subject_area_id").map { snapshots.subjectArea(it.objectId, it.version) }.sortedWith(compareBy(SubjectAreaRecord::businessLineId, SubjectAreaRecord::businessLineVersion, SubjectAreaRecord::code))
    fun subjectAreaIdentityExists(id: String): Boolean = identities.exists("subject_area", id)
    fun subjectAreaVersionExists(id: String, version: Long): Boolean {
        requireText(id, "subject_area_version.subject_area_id"); requireVersion(version, "subject_area_version.version")
        return try { GovernanceJdbcSupport.query(database, "SELECT 1 FROM subject_area_version WHERE subject_area_id=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check subject_area_version: $id#$version", failure) }
    }
    fun saveSubjectAreaVersion(r: SubjectAreaVersionRecord) {
        GovernanceContract.requireVersionObject("subject_area_version", "subject_area_id", r.id, r.subjectAreaId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentJson, "subject_area_version.content_json")
        try { GovernanceJdbcSupport.insert(database, "subject_area_version", linkedMapOf("id" to r.id, "subject_area_id" to r.subjectAreaId, "version" to r.version, "version_status" to r.versionStatus, "content_json" to r.contentJson, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save subject_area_version: ${r.id}", failure) }
    }
    fun saveSubjectAreaScenarioRef(r: SubjectAreaScenarioRefRecord) {
        requireText(r.id, "subject_area_scenario_ref.id"); requireText(r.subjectAreaId, "subject_area_scenario_ref.subject_area_id"); requireVersion(r.subjectAreaVersion, "subject_area_scenario_ref.subject_area_version")
        requireText(r.scenarioId, "subject_area_scenario_ref.scenario_id"); requireVersion(r.scenarioVersion, "subject_area_scenario_ref.scenario_version"); requireText(r.status, "subject_area_scenario_ref.status"); requireText(r.selectedBy, "subject_area_scenario_ref.selected_by")
        try { GovernanceJdbcSupport.insert(database, "subject_area_scenario_ref", linkedMapOf("id" to r.id, "subject_area_id" to r.subjectAreaId, "subject_area_version" to r.subjectAreaVersion, "scenario_id" to r.scenarioId, "scenario_version" to r.scenarioVersion, "status" to r.status, "selected_by" to r.selectedBy, "selected_at" to r.selectedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save subject_area_scenario_ref: ${r.id}", failure) }
    }
    fun replaceSubjectAreaScenarioRef(r: SubjectAreaScenarioRefRecord, sourceVersion: Long) {
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE subject_area_scenario_ref SET id=?, scenario_version=?, status=?, selected_by=?, selected_at=? WHERE subject_area_id=? AND subject_area_version=? AND scenario_id=? AND scenario_version=?", listOf(r.id, r.scenarioVersion, r.status, r.selectedBy, r.selectedAt, r.subjectAreaId, r.subjectAreaVersion, r.scenarioId, sourceVersion))
            check(count == 1) { "exact source scenario selection is missing or changed" }
        } catch (failure: Exception) { throw IllegalStateException("Failed to replace subject_area_scenario_ref: ${r.id}", failure) }
    }
    fun listSubjectAreaScenarioRefs(id: String, version: Long): List<SubjectAreaScenarioRefRecord> {
        requireText(id, "subject_area_scenario_ref.subject_area_id"); requireVersion(version, "subject_area_scenario_ref.subject_area_version")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM subject_area_scenario_ref WHERE subject_area_id=? AND subject_area_version=? ORDER BY scenario_id, scenario_version", listOf(id, version)) { r -> SubjectAreaScenarioRefRecord(r.getString("id"), r.getString("subject_area_id"), r.getLong("subject_area_version"), r.getString("scenario_id"), r.getLong("scenario_version"), r.getString("status"), r.getString("selected_by"), r.getTimestamp("selected_at").toInstant()) } }
        catch (failure: Exception) { throw IllegalStateException("Failed to list subject_area_scenario_ref: $id#$version", failure) }
    }
}

class SubjectAreaGovernanceService(private val database: PlatformDatabase, private val businessLines: BusinessLineGovernanceService) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val repository = SubjectAreaGovernanceRepository(database)
    fun subjectAreaSnapshot(id: String, version: Long): SubjectAreaRecord = snapshots.subjectArea(id, version)
    internal fun saveSubjectArea(record: SubjectAreaRecord) {
        businessLines.requireBusinessLineVersion(record.businessLineId, record.businessLineVersion, "subject_area.business_line_version")
        repository.saveSubjectArea(record)
    }
    fun createSubjectArea(request: SubjectAreaCreateRequest): SubjectAreaCreateResult = database.inTransaction { write(request, null) }
    fun reviseSubjectArea(request: GovernanceRevisionRequest<SubjectAreaCreateRequest>): SubjectAreaCreateResult = database.inTransaction { write(request.definition, request) }
    private fun write(request: SubjectAreaCreateRequest, revision: GovernanceRevisionRequest<SubjectAreaCreateRequest>?): SubjectAreaCreateResult {
        val business = requireText(request.businessLineId, "subject_area.business_line_id").trim()
        val businessVersion = positive(request.businessLineVersion, "subject_area.business_line_version")
        businessLines.requireBusinessLineVersion(business, businessVersion, "subject_area.business_line_version")
        val code = requireText(request.code, "subject_area.code").trim()
        if (!code.matches(Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"))) throw ProducerContractException("subject_area.code must use lower-case letters, numbers, underscore, dash, or dot")
        val id = revision?.objectId ?: "$business.$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.SUBJECT_AREA, id, revision, request.createdBy)
        val actor = requireText(request.createdBy, "subject_area.created_by").trim(); val now = Instant.now()
        val record = SubjectAreaRecord(id, code, requireText(request.displayName, "subject_area.display_name").trim(), business, businessVersion, request.description, requireText(request.owner, "subject_area.owner").trim(), "DRAFT", draft.version, actor, now, actor, now)
        val content = try { OlapJsonUtils.mapper().writeValueAsString(SubjectAreaSnapshots.subjectAreaVersionContent(record)) }
            catch (failure: Exception) { throw ProducerContractException("canonical governance content_json invalid", failure) }
        val version = SubjectAreaVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", content, "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        saveSubjectArea(record); saveSubjectAreaVersion(version)
        return SubjectAreaCreateResult(record, version)
    }
    internal fun saveSubjectAreaVersion(record: SubjectAreaVersionRecord) {
        if (!repository.subjectAreaIdentityExists(record.subjectAreaId)) throw ProducerContractException("subject_area_version.subject_area_id not found: ${record.subjectAreaId}")
        repository.saveSubjectAreaVersion(record)
    }
    fun findSubjectArea(id: String): SubjectAreaRecord? = repository.findSubjectArea(id)
    fun listSubjectAreas(): List<SubjectAreaRecord> = repository.listSubjectAreas()
    internal fun persistSubjectAreaScenarioRef(record: SubjectAreaScenarioRefRecord, selectedSourceVersion: Long?) {
        requireSubjectAreaVersion(record.subjectAreaId, record.subjectAreaVersion, "subject_area_scenario_ref.subject_area_version")
        if (selectedSourceVersion == null) repository.saveSubjectAreaScenarioRef(record) else repository.replaceSubjectAreaScenarioRef(record, selectedSourceVersion)
    }
    fun listSubjectAreaScenarioRefs(id: String, version: Long): List<SubjectAreaScenarioRefRecord> = repository.listSubjectAreaScenarioRefs(id, version)
    fun requireSubjectAreaVersion(id: String, version: Long?, field: String) { subjectAreaSnapshot(requireText(id, "$field.subject_area_id").trim(), positive(version, field)) }
    private fun positive(version: Long?, field: String): Long = version?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
}
