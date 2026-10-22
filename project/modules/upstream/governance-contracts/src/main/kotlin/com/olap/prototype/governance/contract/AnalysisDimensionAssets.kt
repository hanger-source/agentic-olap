package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.ValueAlignmentContract
import java.time.Instant

data class AnalysisDimensionCreateRequest(val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val description: String?, val levelCode: String, val levelDisplayName: String, val createdBy: String)
data class AnalysisDimensionCreateResult(val analysisDimension: AnalysisDimensionRecord, val analysisDimensionVersion: AnalysisDimensionVersionRecord, val defaultLevel: AnalysisDimensionLevelRecord, val subjectAreaRef: SubjectAreaAnalysisDimensionRefRecord)
data class AnalysisDimensionRecord(val id: String, val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val description: String?, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class AnalysisDimensionVersionRecord(val id: String, val analysisDimensionId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class AnalysisDimensionLevelRecord(val id: String, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val displayName: String, val parentLevelCode: String?, val sortOrder: Int, val status: String)
data class AnalysisDimensionEventMappingCreateRequest(val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val bindingId: String, val bindingVersion: Long, val alignmentStatus: String, val alignmentId: String?)
data class AnalysisDimensionEventMappingCreateResult(val mapping: AnalysisDimensionEventMappingRecord)
data class AnalysisDimensionEventMappingRecord(val id: String, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val eventId: String, val eventVersion: Long, val dimensionId: String, val dimensionVersion: Long, val bindingId: String, val bindingVersion: Long, val alignmentStatus: String, val alignmentId: String?, val createdAt: Instant) {
    init { ValueAlignmentContract.requireStatus(alignmentStatus, "analysis_dimension_event_mapping.alignment_status") }
}
class AnalysisDimensionEventMappingConflictException(val existing: AnalysisDimensionEventMappingRecord, val requested: AnalysisDimensionEventMappingRecord) : ProducerContractException("an event mapping already occupies this exact analysis dimension version and level; read the existing mapping before reusing it or explicitly revising the analysis dimension")
data class AnalysisDimensionValueAlignmentCreateRequest(val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val alignmentStatus: String, val evidenceJson: String, val confirmedBy: String)
data class AnalysisDimensionValueAlignmentRecord(val id: String, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val alignmentType: String, val evidenceJson: String, val confirmedBy: String?, val confirmedAt: Instant?, val diagnosticStatus: String, val createdAt: Instant)
data class AnalysisDimensionValueProviderCreateRequest(val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val bindingId: String, val bindingVersion: Long, val refreshPolicy: String, val createdBy: String)
data class AnalysisDimensionValueProviderCreateResult(val provider: AnalysisDimensionValueProviderRecord)
data class AnalysisDimensionValueProviderRecord(val id: String, val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String, val providerType: String, val sourceTableId: String?, val sourceTableVersion: Long?, val sourceLogicUnitId: String?, val sourceLogicUnitVersion: Long?, val mappingJson: String, val refreshPolicy: String, val status: String, val createdBy: String, val createdAt: Instant)
data class AnalysisDimensionMappedBindingCandidate(val mappingId: String, val levelCode: String, val dimensionId: String, val dimensionVersion: Long, val dimensionDisplayName: String, val bindingId: String, val bindingVersion: Long, val eventId: String, val eventVersion: Long, val eventDisplayName: String, val logicUnitId: String?, val logicUnitVersion: Long?, val logicUnitDisplayName: String?, val alignmentStatus: String)
class AnalysisDimensionMappingDimensionCandidate(val dimensionId: String, val dimensionVersion: Long, val dimensionCode: String, val dimensionDisplayName: String, val dimensionCodeNamespaceDisplayName: String, bindings: List<DimensionEventBindingCandidateRecord>) {
    val bindings: List<DimensionEventBindingCandidateRecord> = java.util.List.copyOf(bindings)
    override fun equals(other: Any?): Boolean = other is AnalysisDimensionMappingDimensionCandidate && dimensionId == other.dimensionId && dimensionVersion == other.dimensionVersion && dimensionCode == other.dimensionCode && dimensionDisplayName == other.dimensionDisplayName && dimensionCodeNamespaceDisplayName == other.dimensionCodeNamespaceDisplayName && bindings == other.bindings
    override fun hashCode(): Int = listOf(dimensionId, dimensionVersion, dimensionCode, dimensionDisplayName, dimensionCodeNamespaceDisplayName, bindings).hashCode()
}
class AnalysisDimensionEventMappingCandidatesView(levels: List<AnalysisDimensionLevelRecord>, dimensions: List<AnalysisDimensionMappingDimensionCandidate>) {
    val levels: List<AnalysisDimensionLevelRecord> = java.util.List.copyOf(levels)
    val dimensions: List<AnalysisDimensionMappingDimensionCandidate> = java.util.List.copyOf(dimensions)
    override fun equals(other: Any?): Boolean = other is AnalysisDimensionEventMappingCandidatesView && levels == other.levels && dimensions == other.dimensions
    override fun hashCode(): Int = listOf(levels, dimensions).hashCode()
}
class AnalysisDimensionValueProviderCandidatesView(levels: List<AnalysisDimensionLevelRecord>, mappedBindings: List<AnalysisDimensionMappedBindingCandidate>) {
    val levels: List<AnalysisDimensionLevelRecord> = java.util.List.copyOf(levels)
    val mappedBindings: List<AnalysisDimensionMappedBindingCandidate> = java.util.List.copyOf(mappedBindings)
    override fun equals(other: Any?): Boolean = other is AnalysisDimensionValueProviderCandidatesView && levels == other.levels && mappedBindings == other.mappedBindings
    override fun hashCode(): Int = listOf(levels, mappedBindings).hashCode()
}
