package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.execute
import com.olap.prototype.management.governance.GovernanceJdbcSupport.insert
import com.olap.prototype.management.governance.GovernanceJdbcSupport.instant
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableLong
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet

class AnalysisDimensionGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveAnalysisDimension(record: AnalysisDimensionRecord) = identities.saveAnalysisDimension(record)
    fun findAnalysisDimension(id: String): AnalysisDimensionRecord? {
        requireText(id, "analysis_dimension.id")
        return working.find("analysis_dimension_version", "analysis_dimension_id", id)?.let { snapshots.analysisDimension(it.objectId, it.version) }
    }
    fun listAnalysisDimensions(subjectAreaId: String): List<AnalysisDimensionRecord> {
        requireText(subjectAreaId, "analysis_dimension.subject_area_id")
        return working.list("analysis_dimension_version", "analysis_dimension_id").map { snapshots.analysisDimension(it.objectId, it.version) }.filter { it.subjectAreaId == subjectAreaId }
            .sortedWith(compareBy(AnalysisDimensionRecord::subjectAreaVersion, AnalysisDimensionRecord::code))
    }
    fun analysisDimensionIdentityExists(id: String): Boolean = identities.exists("analysis_dimension", id)
    fun analysisDimensionVersionExists(id: String, version: Long): Boolean {
        requireText(id, "analysis_dimension_version.analysis_dimension_id"); requireVersion(version, "analysis_dimension_version.version")
        return read("Failed to check analysis_dimension_version: $id#$version") { query(database, "SELECT 1 FROM analysis_dimension_version WHERE analysis_dimension_id=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
    }
    fun analysisDimensionLevelExists(id: String, version: Long, code: String): Boolean = findAnalysisDimensionLevel(id, version, code) != null
    fun findAnalysisDimensionLevel(id: String, version: Long, code: String): AnalysisDimensionLevelRecord? {
        owner(id, version, "analysis_dimension_level"); requireText(code, "analysis_dimension_level.level_code")
        return read("Failed to find analysis_dimension_level: $id#$version#$code") { query(database, "SELECT * FROM analysis_dimension_level WHERE analysis_dimension_id=? AND analysis_dimension_version=? AND level_code=?", listOf(id, version, code), ::level).singleOrNull() }
    }
    fun listAnalysisDimensionLevels(id: String, version: Long): List<AnalysisDimensionLevelRecord> {
        owner(id, version, "analysis_dimension_level")
        return read("Failed to list analysis_dimension_level: $id#$version") { query(database, "SELECT * FROM analysis_dimension_level WHERE analysis_dimension_id=? AND analysis_dimension_version=? ORDER BY sort_order,level_code", listOf(id, version), ::level) }
    }
    fun saveSubjectAreaAnalysisDimensionRef(record: SubjectAreaAnalysisDimensionRefRecord) {
        requireText(record.id, "subject_area_analysis_dimension_ref.id"); requireText(record.subjectAreaId, "subject_area_analysis_dimension_ref.subject_area_id")
        requireVersion(record.subjectAreaVersion, "subject_area_analysis_dimension_ref.subject_area_version"); requireText(record.analysisDimensionId, "subject_area_analysis_dimension_ref.analysis_dimension_id")
        requireVersion(record.analysisDimensionVersion, "subject_area_analysis_dimension_ref.analysis_dimension_version"); requireText(record.status, "subject_area_analysis_dimension_ref.status")
        requireText(record.selectedBy, "subject_area_analysis_dimension_ref.selected_by"); GovernanceContract.requireInstant(record.selectedAt, "subject_area_analysis_dimension_ref.selected_at")
        read("Failed to save subject_area_analysis_dimension_ref: ${record.id}") { insert(database, "subject_area_analysis_dimension_ref", linkedMapOf("id" to record.id, "subject_area_id" to record.subjectAreaId, "subject_area_version" to record.subjectAreaVersion,
            "analysis_dimension_id" to record.analysisDimensionId, "analysis_dimension_version" to record.analysisDimensionVersion, "status" to record.status, "selected_by" to record.selectedBy, "selected_at" to record.selectedAt)) }
    }
    fun replaceSubjectAreaAnalysisDimensionRef(record: SubjectAreaAnalysisDimensionRefRecord, sourceVersion: Long) {
        read("Failed to replace subject_area_analysis_dimension_ref: ${record.id}") {
            val count = execute(database, "UPDATE subject_area_analysis_dimension_ref SET id=?,analysis_dimension_version=?,status=?,selected_by=?,selected_at=? WHERE subject_area_id=? AND subject_area_version=? AND analysis_dimension_id=? AND analysis_dimension_version=?",
                listOf(record.id, record.analysisDimensionVersion, record.status, record.selectedBy, record.selectedAt, record.subjectAreaId, record.subjectAreaVersion, record.analysisDimensionId, sourceVersion))
            if (count != 1) throw IllegalStateException("exact source analysis dimension selection is missing or changed")
        }
    }
    fun listSubjectAreaAnalysisDimensionRefs(id: String, version: Long): List<SubjectAreaAnalysisDimensionRefRecord> {
        requireText(id, "subject_area_analysis_dimension_ref.subject_area_id"); requireVersion(version, "subject_area_analysis_dimension_ref.subject_area_version")
        return read("Failed to list subject_area_analysis_dimension_ref: $id#$version") { query(database, "SELECT * FROM subject_area_analysis_dimension_ref WHERE subject_area_id=? AND subject_area_version=? ORDER BY analysis_dimension_id,analysis_dimension_version", listOf(id, version)) {
            SubjectAreaAnalysisDimensionRefRecord(it.getString("id"), it.getString("subject_area_id"), requireNotNull(nullableLong(it, "subject_area_version")), it.getString("analysis_dimension_id"), requireNotNull(nullableLong(it, "analysis_dimension_version")), it.getString("status"), it.getString("selected_by"), requireNotNull(instant(it, "selected_at")))
        } }
    }
    fun saveAnalysisDimensionVersion(record: AnalysisDimensionVersionRecord) {
        GovernanceContract.requireVersionObject("analysis_dimension_version", "analysis_dimension_id", record.id, record.analysisDimensionId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentProto, "analysis_dimension_version.content_proto")
        read("Failed to save exact analysis_dimension_version") { insert(database, "analysis_dimension_version", linkedMapOf("id" to record.id, "analysis_dimension_id" to record.analysisDimensionId, "version" to record.version,
            "version_status" to record.versionStatus, "content_proto" to record.contentProto, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion,
            "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt,
            "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
    }
    fun saveAnalysisDimensionLevel(record: AnalysisDimensionLevelRecord) {
        requireText(record.id, "analysis_dimension_level.id"); owner(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_level")
        requireText(record.levelCode, "analysis_dimension_level.level_code"); requireText(record.displayName, "analysis_dimension_level.display_name")
        GovernanceContract.requireInteger(record.sortOrder, "analysis_dimension_level.sort_order"); requireText(record.status, "analysis_dimension_level.status")
        read("Failed to save analysis_dimension_level: ${record.id}") { insert(database, "analysis_dimension_level", linkedMapOf("id" to record.id, "analysis_dimension_id" to record.analysisDimensionId,
            "analysis_dimension_version" to record.analysisDimensionVersion, "level_code" to record.levelCode, "display_name" to record.displayName, "parent_level_code" to record.parentLevelCode, "sort_order" to record.sortOrder, "status" to record.status)) }
    }
    fun saveAnalysisDimensionValueProvider(record: AnalysisDimensionValueProviderRecord) {
        requireText(record.id, "analysis_dimension_value_provider.id"); owner(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_value_provider")
        requireText(record.levelCode, "analysis_dimension_value_provider.level_code"); requireText(record.providerType, "analysis_dimension_value_provider.provider_type")
        if (!record.sourceTableId.isNullOrBlank()) requireVersion(record.sourceTableVersion, "analysis_dimension_value_provider.source_table_version")
        if (!record.sourceLogicUnitId.isNullOrBlank()) requireVersion(record.sourceLogicUnitVersion, "analysis_dimension_value_provider.source_logic_unit_version")
        requireText(record.refreshPolicy, "analysis_dimension_value_provider.refresh_policy"); requireText(record.status, "analysis_dimension_value_provider.status")
        requireText(record.createdBy, "analysis_dimension_value_provider.created_by"); GovernanceContract.requireInstant(record.createdAt, "analysis_dimension_value_provider.created_at")
        read("Failed to save analysis_dimension_value_provider: ${record.id}") { insert(database, "analysis_dimension_value_provider", linkedMapOf("id" to record.id, "analysis_dimension_id" to record.analysisDimensionId,
            "analysis_dimension_version" to record.analysisDimensionVersion, "level_code" to record.levelCode, "provider_type" to record.providerType, "source_table_id" to record.sourceTableId,
            "source_table_version" to record.sourceTableVersion, "source_logic_unit_id" to record.sourceLogicUnitId, "source_logic_unit_version" to record.sourceLogicUnitVersion,
            "refresh_policy" to record.refreshPolicy, "status" to record.status, "created_by" to record.createdBy, "created_at" to record.createdAt)) }
    }
    fun findAnalysisDimensionValueProvider(id: String): AnalysisDimensionValueProviderRecord? {
        requireText(id, "analysis_dimension_value_provider.id")
        return read("Failed to find analysis_dimension_value_provider: $id") { query(database, "SELECT * FROM analysis_dimension_value_provider WHERE id=?", listOf(id), ::provider).singleOrNull() }
    }
    fun listAnalysisDimensionValueProviders(id: String, version: Long): List<AnalysisDimensionValueProviderRecord> {
        owner(id, version, "analysis_dimension_value_provider")
        return read("Failed to list analysis_dimension_value_provider: $id#$version") { query(database, "SELECT * FROM analysis_dimension_value_provider WHERE analysis_dimension_id=? AND analysis_dimension_version=? ORDER BY level_code,provider_type,id", listOf(id, version), ::provider) }
    }
    fun saveAnalysisDimensionEventMapping(record: AnalysisDimensionEventMappingRecord) {
        requireText(record.id, "analysis_dimension_event_mapping.id"); owner(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_event_mapping")
        requireText(record.levelCode, "analysis_dimension_event_mapping.level_code"); requireText(record.eventId, "analysis_dimension_event_mapping.event_id"); requireVersion(record.eventVersion, "analysis_dimension_event_mapping.event_version")
        requireText(record.dimensionId, "analysis_dimension_event_mapping.dimension_id"); requireVersion(record.dimensionVersion, "analysis_dimension_event_mapping.dimension_version")
        requireText(record.bindingId, "analysis_dimension_event_mapping.binding_id"); requireVersion(record.bindingVersion, "analysis_dimension_event_mapping.binding_version")
        requireText(record.alignmentStatus, "analysis_dimension_event_mapping.alignment_status"); GovernanceContract.requireInstant(record.createdAt, "analysis_dimension_event_mapping.created_at")
        read("Failed to save analysis_dimension_event_mapping: ${record.id}") { insert(database, "analysis_dimension_event_mapping", linkedMapOf("id" to record.id, "analysis_dimension_id" to record.analysisDimensionId,
            "analysis_dimension_version" to record.analysisDimensionVersion, "level_code" to record.levelCode, "event_id" to record.eventId, "event_version" to record.eventVersion,
            "dimension_id" to record.dimensionId, "dimension_version" to record.dimensionVersion, "binding_id" to record.bindingId, "binding_version" to record.bindingVersion,
            "alignment_status" to record.alignmentStatus, "alignment_id" to record.alignmentId, "created_at" to record.createdAt)) }
    }
    fun findAnalysisDimensionEventMapping(id: String, version: Long, code: String, eventId: String, eventVersion: Long): AnalysisDimensionEventMappingRecord? {
        owner(id, version, "analysis_dimension_event_mapping"); requireText(code, "analysis_dimension_event_mapping.level_code"); requireText(eventId, "analysis_dimension_event_mapping.event_id"); requireVersion(eventVersion, "analysis_dimension_event_mapping.event_version")
        return read("Failed to read the exact analysis dimension event mapping slot") { query(database, "SELECT * FROM analysis_dimension_event_mapping WHERE analysis_dimension_id=? AND analysis_dimension_version=? AND level_code=? AND event_id=? AND event_version=?", listOf(id, version, code, eventId, eventVersion), ::mapping).singleOrNull() }
    }
    fun listAnalysisDimensionEventMappings(id: String, version: Long): List<AnalysisDimensionEventMappingRecord> {
        owner(id, version, "analysis_dimension_event_mapping")
        return read("Failed to list analysis_dimension_event_mapping: $id#$version") { query(database, "SELECT * FROM analysis_dimension_event_mapping WHERE analysis_dimension_id=? AND analysis_dimension_version=? ORDER BY level_code,event_id,event_version", listOf(id, version), ::mapping) }
    }
    fun listAnalysisDimensionEventMappingsByDimension(id: String, version: Long): List<AnalysisDimensionEventMappingRecord> {
        requireText(id, "analysis_dimension_event_mapping.dimension_id"); requireVersion(version, "analysis_dimension_event_mapping.dimension_version")
        return read("Failed to list analysis_dimension_event_mapping by dimension: $id#$version") { query(database, "SELECT * FROM analysis_dimension_event_mapping WHERE dimension_id=? AND dimension_version=? ORDER BY analysis_dimension_id,analysis_dimension_version,level_code,event_id,event_version", listOf(id, version), ::mapping) }
    }
    fun saveAnalysisDimensionValueAlignment(record: AnalysisDimensionValueAlignmentRecord) {
        requireText(record.id, "analysis_dimension_value_alignment.id"); owner(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_value_alignment")
        requireText(record.levelCode, "analysis_dimension_value_alignment.level_code"); requireText(record.alignmentType, "analysis_dimension_value_alignment.alignment_type")
        requireText(record.diagnosticStatus, "analysis_dimension_value_alignment.diagnostic_status"); GovernanceContract.requireInstant(record.createdAt, "analysis_dimension_value_alignment.created_at")
        read("Failed to save analysis_dimension_value_alignment: ${record.id}") { insert(database, "analysis_dimension_value_alignment", linkedMapOf("id" to record.id, "analysis_dimension_id" to record.analysisDimensionId,
            "analysis_dimension_version" to record.analysisDimensionVersion, "level_code" to record.levelCode, "alignment_type" to record.alignmentType, "evidence_json" to record.evidenceJson,
            "confirmed_by" to record.confirmedBy, "confirmed_at" to record.confirmedAt, "diagnostic_status" to record.diagnosticStatus, "created_at" to record.createdAt)) }
    }
    fun findAnalysisDimensionValueAlignment(id: String): AnalysisDimensionValueAlignmentRecord? {
        requireText(id, "analysis_dimension_value_alignment.id")
        return read("Failed to find analysis_dimension_value_alignment: $id") { query(database, "SELECT * FROM analysis_dimension_value_alignment WHERE id=?", listOf(id), ::alignment).singleOrNull() }
    }
    fun listAnalysisDimensionValueAlignments(id: String, version: Long): List<AnalysisDimensionValueAlignmentRecord> {
        owner(id, version, "analysis_dimension_value_alignment")
        return read("Failed to read value alignments for $id@$version") { query(database, "SELECT * FROM analysis_dimension_value_alignment WHERE analysis_dimension_id=? AND analysis_dimension_version=? ORDER BY id", listOf(id, version), ::alignment) }
    }
    private fun level(rs: ResultSet) = AnalysisDimensionLevelRecord(rs.getString("id"), rs.getString("analysis_dimension_id"), requireNotNull(nullableLong(rs, "analysis_dimension_version")), rs.getString("level_code"), rs.getString("display_name"), rs.getString("parent_level_code"), rs.getInt("sort_order"), rs.getString("status"))
    private fun provider(rs: ResultSet) = AnalysisDimensionValueProviderRecord(rs.getString("id"), rs.getString("analysis_dimension_id"), requireNotNull(nullableLong(rs, "analysis_dimension_version")), rs.getString("level_code"), rs.getString("provider_type"), rs.getString("source_table_id"), nullableLong(rs, "source_table_version"), rs.getString("source_logic_unit_id"), nullableLong(rs, "source_logic_unit_version"), rs.getString("refresh_policy"), rs.getString("status"), rs.getString("created_by"), requireNotNull(instant(rs, "created_at")))
    private fun mapping(rs: ResultSet) = AnalysisDimensionEventMappingRecord(rs.getString("id"), rs.getString("analysis_dimension_id"), requireNotNull(nullableLong(rs, "analysis_dimension_version")), rs.getString("level_code"), rs.getString("event_id"), requireNotNull(nullableLong(rs, "event_version")), rs.getString("dimension_id"), requireNotNull(nullableLong(rs, "dimension_version")), rs.getString("binding_id"), requireNotNull(nullableLong(rs, "binding_version")), rs.getString("alignment_status"), rs.getString("alignment_id"), requireNotNull(instant(rs, "created_at")))
    private fun alignment(rs: ResultSet) = AnalysisDimensionValueAlignmentRecord(rs.getString("id"), rs.getString("analysis_dimension_id"), requireNotNull(nullableLong(rs, "analysis_dimension_version")), rs.getString("level_code"), rs.getString("alignment_type"), rs.getString("evidence_json"), rs.getString("confirmed_by"), instant(rs, "confirmed_at"), rs.getString("diagnostic_status"), requireNotNull(instant(rs, "created_at")))
    private fun owner(id: String, version: Long, table: String) { requireText(id, "$table.analysis_dimension_id"); requireVersion(version, "$table.analysis_dimension_version") }
    private fun <T> read(message: String, action: () -> T): T = try { action() } catch (failure: Exception) { throw IllegalStateException(message, failure) }
}
