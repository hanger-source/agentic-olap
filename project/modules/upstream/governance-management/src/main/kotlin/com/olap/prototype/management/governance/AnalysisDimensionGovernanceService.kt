package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.query.ValueAlignmentContract
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant

/** Analysis versions own their levels, mappings, candidate providers and explicit alignment proof. */
class AnalysisDimensionGovernanceService(private val database: PlatformDatabase, private val subjectAreas: SubjectAreaGovernanceService,
    private val logicalModel: LogicalModelGovernanceService, private val events: EventGovernanceService, private val dimensions: DimensionGovernanceService) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val repository = AnalysisDimensionGovernanceRepository(database)
    fun analysisDimensionSnapshot(id: String, version: Long): AnalysisDimensionRecord = snapshots.analysisDimension(id, version)
    fun createAnalysisDimension(request: AnalysisDimensionCreateRequest): AnalysisDimensionCreateResult = database.inTransaction { create(request, null) }
    fun reviseAnalysisDimension(request: GovernanceRevisionRequest<AnalysisDimensionCreateRequest>): AnalysisDimensionCreateResult = database.inTransaction { create(request.definition, request) }
    internal fun reviseForContext(source: AnalysisDimensionRecord, subjectVersion: Long, actor: String): AnalysisDimensionCreateResult {
        val levels = listAnalysisDimensionLevels(source.id, source.currentVersion)
        val root = requireNotNull(levels.firstOrNull { it.parentLevelCode == null }) { "source analysis dimension has no root level" }
        val request = AnalysisDimensionCreateRequest(source.subjectAreaId, subjectVersion, source.code, source.displayName,
            source.description, root.levelCode, root.displayName, actor)
        return create(request, GovernanceRevisionRequest(source.id, source.currentVersion, request), levels)
    }
    private fun create(request: AnalysisDimensionCreateRequest, revision: GovernanceRevisionRequest<AnalysisDimensionCreateRequest>?,
        carriedLevels: List<AnalysisDimensionLevelRecord>? = null): AnalysisDimensionCreateResult {
        val subject = text(request.subjectAreaId, "analysis_dimension.subject_area_id")
        val subjectVersion = positive(request.subjectAreaVersion, "analysis_dimension.subject_area_version")
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, subject, subjectVersion)
        subjectAreas.requireSubjectAreaVersion(subject, subjectVersion, "analysis_dimension.subject_area_version")
        val code = domainCode(request.code, "analysis_dimension.code")
        val id = revision?.objectId ?: "$subject.$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.ANALYSIS_DIMENSION, id, revision, request.createdBy)
        val selected = revision?.let {
            val source = analysisDimensionSnapshot(id, requireNotNull(draft.sourceVersion))
            matching(subject, source.subjectAreaId, "analysis_dimension.subject_area_id", "source subject area identity")
            draft.sourceVersion.takeIf { source.subjectAreaVersion == subjectVersion }
        }
        val actor = text(request.createdBy, "analysis_dimension.created_by")
        val now = Instant.now()
        val record = AnalysisDimensionRecord(id, subject, subjectVersion, code, text(request.displayName, "analysis_dimension.display_name"), request.description, "DRAFT", draft.version, actor, now, actor, now)
        val version = AnalysisDimensionVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", encodeDefinition(AnalysisDimensionSnapshots.analysisDimensionVersionContent(record)), "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        val levelCode = domainCode(request.levelCode, "analysis_dimension_level.level_code")
        val level = AnalysisDimensionLevelRecord("$id:v${draft.version}:level:$levelCode", id, draft.version, levelCode, text(request.levelDisplayName, "analysis_dimension_level.display_name"), null, 1, "DRAFT")
        val ref = SubjectAreaAnalysisDimensionRefRecord("$subject:v$subjectVersion:analysis-dimension:$id:v${draft.version}", subject, subjectVersion, id, draft.version, "DRAFT", actor, now)
        val levels = carriedLevels?.map { it.copy(id = "$id:v${draft.version}:level:${it.levelCode}", analysisDimensionVersion = draft.version, status = "DRAFT") } ?: listOf(level)
        saveAnalysisDimension(record); saveAnalysisDimensionVersion(version); levels.forEach(::saveAnalysisDimensionLevel); saveSelection(ref, selected)
        return AnalysisDimensionCreateResult(record, version, levels.single { it.levelCode == levelCode }, ref)
    }
    private fun saveSelection(record: SubjectAreaAnalysisDimensionRefRecord, sourceVersion: Long?) {
        subjectAreas.requireSubjectAreaVersion(record.subjectAreaId, record.subjectAreaVersion, "subject_area_analysis_dimension_ref.subject_area_version")
        val dimension = requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "subject_area_analysis_dimension_ref.analysis_dimension_version")
        matching(record.subjectAreaId, dimension.subjectAreaId, "subject_area_analysis_dimension_ref.subject_area_id", "analysis_dimension.subject_area_id")
        matching(record.subjectAreaVersion, dimension.subjectAreaVersion, "subject_area_analysis_dimension_ref.subject_area_version", "analysis_dimension.subject_area_version")
        if (sourceVersion == null) repository.saveSubjectAreaAnalysisDimensionRef(record) else repository.replaceSubjectAreaAnalysisDimensionRef(record, sourceVersion)
    }
    internal fun saveAnalysisDimension(record: AnalysisDimensionRecord) {
        subjectAreas.requireSubjectAreaVersion(record.subjectAreaId, record.subjectAreaVersion, "analysis_dimension.subject_area_version")
        repository.saveAnalysisDimension(record)
    }
    internal fun saveAnalysisDimensionVersion(record: AnalysisDimensionVersionRecord) {
        if (!repository.analysisDimensionIdentityExists(record.analysisDimensionId)) throw ProducerContractException("analysis_dimension_version.analysis_dimension_id not found: ${record.analysisDimensionId}")
        repository.saveAnalysisDimensionVersion(record)
    }
    internal fun saveAnalysisDimensionLevel(record: AnalysisDimensionLevelRecord) {
        requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_level.analysis_dimension_version")
        repository.saveAnalysisDimensionLevel(record)
    }
    fun findAnalysisDimension(id: String): AnalysisDimensionRecord? = repository.findAnalysisDimension(id)
    fun listAnalysisDimensions(id: String): List<AnalysisDimensionRecord> = repository.listAnalysisDimensions(id)
    fun listSubjectAreaAnalysisDimensionRefs(id: String, version: Long): List<SubjectAreaAnalysisDimensionRefRecord> = repository.listSubjectAreaAnalysisDimensionRefs(id, version)
    fun findAnalysisDimensionLevel(id: String, version: Long?, code: String): AnalysisDimensionLevelRecord? = repository.findAnalysisDimensionLevel(id, positive(version, "analysis_dimension_level.analysis_dimension_version"), code)
    fun listAnalysisDimensionLevels(id: String, version: Long): List<AnalysisDimensionLevelRecord> = repository.listAnalysisDimensionLevels(id, version)

    fun analysisDimensionEventMappingCandidates(id: String, version: Long): AnalysisDimensionEventMappingCandidatesView {
        requireAnalysisDimensionVersion(id, version, "analysis_dimension_event_mapping_candidates.analysis_dimension_version")
        val candidates = dimensions.listDimensions().mapNotNull { dimension ->
            val exact = positive(dimension.currentVersion, "analysis_dimension_event_mapping_candidates.dimension_version")
            val bindings = dimensions.listDimensionEventBindingCandidates(dimension.id, exact)
            if (bindings.isEmpty()) null else AnalysisDimensionMappingDimensionCandidate(text(dimension.id, "analysis_dimension_event_mapping_candidates.dimension_id"), exact,
                text(dimension.code, "analysis_dimension_event_mapping_candidates.dimension_code"), text(dimension.displayName, "analysis_dimension_event_mapping_candidates.dimension_display_name"),
                text(dimension.dimensionCodeNamespaceDisplayName, "analysis_dimension_event_mapping_candidates.dimension_code_namespace_display_name"), bindings)
        }
        return AnalysisDimensionEventMappingCandidatesView(listAnalysisDimensionLevels(id, version), candidates)
    }
    fun analysisDimensionValueProviderCandidates(id: String, version: Long): AnalysisDimensionValueProviderCandidatesView {
        requireAnalysisDimensionVersion(id, version, "analysis_dimension_value_provider_candidates.analysis_dimension_version")
        val bindings = listAnalysisDimensionEventMappings(id, version).map { mapping ->
            val dimension = dimensions.dimensionSnapshot(mapping.dimensionId, mapping.dimensionVersion)
            val binding = dimensions.dimensionEventBindingSnapshot(mapping.bindingId, mapping.bindingVersion)
            val event = events.eventSnapshot(binding.eventId, binding.eventVersion)
            val unit = logicalModel.logicUnitSnapshot(text(binding.logicUnitId, "analysis_dimension_value_provider.binding.logic_unit_id"),
                positive(binding.logicUnitVersion, "analysis_dimension_value_provider.binding.logic_unit_version"))
            AnalysisDimensionMappedBindingCandidate(mapping.id, mapping.levelCode, mapping.dimensionId, mapping.dimensionVersion, dimension.displayName, binding.id,
                binding.currentVersion, binding.eventId, binding.eventVersion, event.displayName, binding.logicUnitId, binding.logicUnitVersion, unit.displayName, mapping.alignmentStatus)
        }
        return AnalysisDimensionValueProviderCandidatesView(listAnalysisDimensionLevels(id, version), bindings)
    }

    fun createAnalysisDimensionValueProvider(request: AnalysisDimensionValueProviderCreateRequest): AnalysisDimensionValueProviderCreateResult = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.ANALYSIS_DIMENSION, request.analysisDimensionId, request.analysisDimensionVersion)
        val id = text(request.analysisDimensionId, "analysis_dimension_value_provider.analysis_dimension_id")
        val version = positive(request.analysisDimensionVersion, "analysis_dimension_value_provider.analysis_dimension_version")
        val level = domainCode(request.levelCode, "analysis_dimension_value_provider.level_code")
        requireAnalysisDimensionLevel(id, version, level, "analysis_dimension_value_provider.level_code")
        val bindingId = text(request.bindingId, "analysis_dimension_value_provider.binding_id")
        val bindingVersion = positive(request.bindingVersion, "analysis_dimension_value_provider.binding_version")
        val binding = dimensions.dimensionEventBindingSnapshot(bindingId, bindingVersion)
        if (repository.listAnalysisDimensionEventMappings(id, version).none { it.levelCode == level && it.bindingId == bindingId && it.bindingVersion == bindingVersion })
            throw ProducerContractException("analysis_dimension_value_provider requires an existing event mapping for binding: $bindingId@$bindingVersion")
        val provider = AnalysisDimensionValueProviderRecord("$id:v$version:level:$level:value-provider:$bindingId:v$bindingVersion", id, version, level, "LOGIC_UNIT_DISTINCT", null, null,
            binding.logicUnitId, binding.logicUnitVersion, text(request.refreshPolicy, "analysis_dimension_value_provider.refresh_policy"), "DRAFT", text(request.createdBy, "analysis_dimension_value_provider.created_by"), Instant.now())
        saveAnalysisDimensionValueProvider(provider)
        AnalysisDimensionValueProviderCreateResult(provider)
    }
    internal fun saveAnalysisDimensionValueProvider(record: AnalysisDimensionValueProviderRecord) {
        requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_value_provider.analysis_dimension_version")
        requireAnalysisDimensionLevel(record.analysisDimensionId, record.analysisDimensionVersion, record.levelCode, "analysis_dimension_value_provider.level_code")
        if (record.providerType != "LOGIC_UNIT_DISTINCT") throw ProducerContractException("analysis_dimension_value_provider.provider_type must be LOGIC_UNIT_DISTINCT: ${record.id}")
        if (!record.sourceTableId.isNullOrBlank() || record.sourceTableVersion != null) throw ProducerContractException("analysis_dimension_value_provider.source_table is not a supported value source: ${record.id}")
        val unitId = record.sourceLogicUnitId
        if (unitId.isNullOrBlank()) {
            if (record.sourceLogicUnitVersion != null) throw ProducerContractException("analysis_dimension_value_provider.source_logic_unit_id is required when source_logic_unit_version is set: ${record.id}")
            throw ProducerContractException("analysis_dimension_value_provider.source_logic_unit_id is required: ${record.id}")
        }
        logicalModel.requireLogicUnitVersion(unitId, record.sourceLogicUnitVersion, "analysis_dimension_value_provider.source_logic_unit_version")
        repository.saveAnalysisDimensionValueProvider(record)
    }

    fun createAnalysisDimensionEventMapping(request: AnalysisDimensionEventMappingCreateRequest): AnalysisDimensionEventMappingCreateResult = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.ANALYSIS_DIMENSION, request.analysisDimensionId, request.analysisDimensionVersion)
        val id = text(request.analysisDimensionId, "analysis_dimension_event_mapping.analysis_dimension_id")
        val version = positive(request.analysisDimensionVersion, "analysis_dimension_event_mapping.analysis_dimension_version")
        val level = domainCode(request.levelCode, "analysis_dimension_event_mapping.level_code")
        requireAnalysisDimensionLevel(id, version, level, "analysis_dimension_event_mapping.level_code")
        val bindingId = text(request.bindingId, "analysis_dimension_event_mapping.binding_id")
        val bindingVersion = positive(request.bindingVersion, "analysis_dimension_event_mapping.binding_version")
        val binding = dimensions.dimensionEventBindingSnapshot(bindingId, bindingVersion)
        val record = AnalysisDimensionEventMappingRecord("$id:v$version:level:$level:binding:$bindingId:v$bindingVersion", id, version, level, binding.eventId, binding.eventVersion,
            binding.dimensionId, binding.dimensionVersion, bindingId, bindingVersion, text(request.alignmentStatus, "analysis_dimension_event_mapping.alignment_status"), request.alignmentId, Instant.now())
        saveAnalysisDimensionEventMapping(record)
        AnalysisDimensionEventMappingCreateResult(record)
    }
    internal fun saveAnalysisDimensionEventMapping(record: AnalysisDimensionEventMappingRecord) {
        requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_event_mapping.analysis_dimension_version")
        requireAnalysisDimensionLevel(record.analysisDimensionId, record.analysisDimensionVersion, record.levelCode, "analysis_dimension_event_mapping.level_code")
        events.requireEvent(record.eventId, record.eventVersion)
        dimensions.requireDimensionVersion(record.dimensionId, record.dimensionVersion, "analysis_dimension_event_mapping.dimension_version")
        val binding = dimensions.dimensionEventBindingSnapshot(record.bindingId, record.bindingVersion)
        matching(record.dimensionId, binding.dimensionId, "analysis_dimension_event_mapping.dimension_id", "dimension_event_binding.dimension_id")
        matching(record.dimensionVersion, binding.dimensionVersion, "analysis_dimension_event_mapping.dimension_version", "dimension_event_binding.dimension_version")
        matching(record.eventId, binding.eventId, "analysis_dimension_event_mapping.event_id", "dimension_event_binding.event_id")
        matching(record.eventVersion, binding.eventVersion, "analysis_dimension_event_mapping.event_version", "dimension_event_binding.event_version")
        validateAnalysisDimensionAlignmentRef(record)
        val existing = repository.findAnalysisDimensionEventMapping(record.analysisDimensionId, record.analysisDimensionVersion, record.levelCode, record.eventId, record.eventVersion)
        if (existing != null) throw AnalysisDimensionEventMappingConflictException(existing, record)
        repository.saveAnalysisDimensionEventMapping(record)
    }
    fun createAnalysisDimensionValueAlignment(request: AnalysisDimensionValueAlignmentCreateRequest): AnalysisDimensionValueAlignmentRecord = database.inTransaction {
        val id = text(request.analysisDimensionId, "value_alignment.analysis_dimension_id")
        val version = positive(request.analysisDimensionVersion, "value_alignment.analysis_dimension_version")
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.ANALYSIS_DIMENSION, id, version)
        val level = domainCode(request.levelCode, "value_alignment.level_code")
        requireAnalysisDimensionLevel(id, version, level, "value_alignment.level_code")
        val status = ValueAlignmentContract.requireStatus(request.alignmentStatus, "value_alignment.alignment_status")
        if (status !in ValueAlignmentContract.crossEventStatuses()) throw ProducerContractException("value alignment requires an explicit cross-event proof status")
        val authored = text(request.evidenceJson, "value_alignment.evidence_json")
        val evidence = try {
            val node = OlapJsonUtils.mapper().readTree(authored)
            if (node == null || !node.isObject || node.isEmpty) throw ProducerContractException("value_alignment.evidence_json requires a non-empty JSON object")
            OlapJsonUtils.mapper().writeValueAsString(node)
        } catch (failure: java.io.IOException) { throw ProducerContractException("value_alignment.evidence_json is invalid", failure) }
        val now = Instant.now()
        AnalysisDimensionValueAlignmentRecord("$id:v$version:level:$level:alignment:$status", id, version, level, status, evidence, text(request.confirmedBy, "value_alignment.confirmed_by"), now, "PROVEN", now).also(::saveAnalysisDimensionValueAlignment)
    }
    internal fun saveAnalysisDimensionValueAlignment(record: AnalysisDimensionValueAlignmentRecord) {
        requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "analysis_dimension_value_alignment.analysis_dimension_version")
        requireAnalysisDimensionLevel(record.analysisDimensionId, record.analysisDimensionVersion, record.levelCode, "analysis_dimension_value_alignment.level_code")
        repository.saveAnalysisDimensionValueAlignment(record)
    }
    fun validateAnalysisDimensionAlignmentRef(record: AnalysisDimensionEventMappingRecord) {
        ValueAlignmentContract.requireEvidenceReference(record.alignmentStatus, record.alignmentId, "analysis_dimension_event_mapping")
        val id = record.alignmentId?.takeUnless { it.isBlank() } ?: return
        val alignment = repository.findAnalysisDimensionValueAlignment(id) ?: throw ProducerContractException("analysis_dimension_event_mapping.alignment_id not found: $id")
        matching(record.analysisDimensionId, alignment.analysisDimensionId, "analysis_dimension_event_mapping.analysis_dimension_id", "analysis_dimension_value_alignment.analysis_dimension_id")
        matching(record.analysisDimensionVersion, alignment.analysisDimensionVersion, "analysis_dimension_event_mapping.analysis_dimension_version", "analysis_dimension_value_alignment.analysis_dimension_version")
        matching(record.levelCode, alignment.levelCode, "analysis_dimension_event_mapping.level_code", "analysis_dimension_value_alignment.level_code")
        matching(record.alignmentStatus, alignment.alignmentType, "analysis_dimension_event_mapping.alignment_status", "analysis_dimension_value_alignment.alignment_type")
        matching("PROVEN", alignment.diagnosticStatus, "analysis_dimension_event_mapping.proof_status", "analysis_dimension_value_alignment.diagnostic_status")
    }
    fun listAnalysisDimensionEventMappings(id: String, version: Long): List<AnalysisDimensionEventMappingRecord> = repository.listAnalysisDimensionEventMappings(id, version)
    fun listAnalysisDimensionEventMappingsByDimension(id: String, version: Long): List<AnalysisDimensionEventMappingRecord> = repository.listAnalysisDimensionEventMappingsByDimension(id, version)
    fun listAnalysisDimensionValueAlignments(id: String, version: Long): List<AnalysisDimensionValueAlignmentRecord> {
        requireAnalysisDimensionVersion(id, version, "value_alignment.analysis_dimension_version")
        return repository.listAnalysisDimensionValueAlignments(id, version)
    }
    fun findAnalysisDimensionValueProvider(id: String): AnalysisDimensionValueProviderRecord? = repository.findAnalysisDimensionValueProvider(id)
    fun listAnalysisDimensionValueProviders(id: String, version: Long): List<AnalysisDimensionValueProviderRecord> = repository.listAnalysisDimensionValueProviders(id, version)
    fun requireAnalysisDimensionVersion(id: String, version: Long?, field: String): AnalysisDimensionRecord = analysisDimensionSnapshot(text(id, "$field.analysis_dimension_id"), positive(version, field))
    fun requireAnalysisDimensionLevel(id: String, version: Long?, code: String, field: String) {
        if (!repository.analysisDimensionLevelExists(id, positive(version, field), code)) throw ProducerContractException("$field not found: $id#$version#$code")
    }
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun domainCode(value: String, field: String): String = text(value, field).also { if (!it.matches(Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"))) throw ProducerContractException("$field must use lower-case letters, numbers, underscore, dash, or dot") }
    private fun matching(actual: Any?, expected: Any?, actualField: String, expectedField: String) { if (actual == null || actual != expected) throw ProducerContractException("$actualField must match $expectedField") }
    private fun encodeDefinition(value: GovernanceDefinition): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(value))
}
