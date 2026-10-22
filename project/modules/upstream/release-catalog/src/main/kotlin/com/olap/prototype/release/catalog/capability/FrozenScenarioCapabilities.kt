package com.olap.prototype.release.catalog.capability

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.ValueAlignmentContract
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.ReleaseObjectIndex
import com.olap.prototype.release.contract.ReleaseCatalogView
import com.olap.prototype.release.contract.ReleaseDimensionMapping
import com.olap.prototype.release.contract.ReleaseScenarioSnapshot
import com.olap.prototype.release.contract.FrozenAnalysisDimensionMeaning
import com.olap.prototype.codec.JsonDocuments

/** 同一精确场景快照的批准投影，不读取工作目录或治理数据库。 */
internal class FrozenScenarioCapabilities(private val release: ReleaseCatalogView, private val selection: ReleaseScenarioSnapshot) {
    private val objects = ReleaseObjectIndex(release.objectVersionRefs)
    init {
        if (selection.scenario.scenarioId != release.scenarioId || selection.scenario.scenarioVersion != release.scenarioVersion ||
            selection.scenario.releaseId != release.releaseId || selection.scenario.releaseVersion != release.releaseVersion) throw ProducerContractException("frozen scenario differs from its exact release coordinate")
        selection.metrics.forEach { grant ->
            val adoption = objects.scenarioAdoption(grant)
            if (adoption.subjectAreaId != selection.subjectArea.subjectAreaId || adoption.subjectAreaVersion != selection.subjectArea.subjectAreaVersion) throw ProducerContractException("frozen scenario adoption differs from its exact subject area")
        }
        selection.members.forEach { grant ->
            val member = objects.member(MemberRef(grant.sourceId, grant.sourceVersion))
            if (member.subjectAreaId != selection.subjectArea.subjectAreaId || member.source.version != selection.subjectArea.subjectAreaVersion) throw ProducerContractException("frozen scenario member differs from its exact subject area")
        }
        release.dimensionMappings.forEach(::requireAlignmentEvidence)
    }
    private fun requireAlignmentEvidence(mapping: ReleaseDimensionMapping) {
        ValueAlignmentContract.requireEvidenceReference(mapping.valueAlignmentStatus, mapping.valueAlignmentKey, "frozen dimension alignment")
        val reference = mapping.valueAlignmentKey
        if (reference.isNullOrBlank()) return
        try {
            val content = objects.read("ANALYSIS_DIMENSION", mapping.analysisDimensionId, mapping.analysisDimensionVersion, FrozenAnalysisDimensionMeaning::class.java)
            val matches = content.valueAlignmentEvidence.filter { reference == it.id }
            require(matches.size == 1) { "alignment evidence must identify one exact proof" }
            val proof = matches.single()
            require(mapping.analysisDimensionId == proof.analysisDimensionId && mapping.analysisDimensionVersion == proof.analysisDimensionVersion && mapping.levelCode == proof.levelCode &&
                mapping.valueAlignmentStatus == proof.alignmentType && proof.diagnosticStatus == "PROVEN" &&
                !proof.confirmedBy.isNullOrBlank()) { "alignment differs from its exact confirmed evidence" }
            JsonDocuments.requireNonEmptyObject(proof.evidenceJson)
        } catch (failure: java.io.IOException) { throw ProducerContractException("frozen dimension alignment evidence is invalid", failure) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException("frozen dimension alignment evidence is invalid", failure) }
    }
    fun findScenarioRef(): QueryScenarioRef = selection.scenario
    fun findSubjectAreaCapabilityContext(): SubjectAreaCapabilityContext = selection.subjectArea
    fun findScenarioMemberReferences(): List<ScenarioMemberReference> = selection.members.map { ScenarioMemberReference(it.sourceId, it.sourceVersion, it.permission) }
    fun findScenarioAnalysisDimensionCandidates(): List<AnalysisDimensionCandidate> = selection.dimensions.mapNotNull { grant ->
        val levels = levelCodes(grant.id, grant.version)
        if (levels.isEmpty()) null else AnalysisDimensionCandidate(grant.id, grant.version, grant.displayName, grant.outputAlias, grant.subjectAreaRefId,
            grant.subjectAreaRefStatus, grant.scenarioRefId, grant.releaseObjectRefId, grant.defaultLevelCode, grant.defaultLevelDisplayName,
            levels, eventMappings(grant.id, grant.version), grant.requiredByDefault, grant.sortOrder)
    }
    fun findScenarioTimeAnalysisDimensionCandidates(): List<TimeAnalysisDimensionCandidate> = selection.dimensions.mapNotNull { grant ->
        val levels = timeLevelCodes(grant.id, grant.version)
        if (levels.isEmpty()) null else TimeAnalysisDimensionCandidate(grant.id, grant.version, grant.displayName, grant.outputAlias, grant.scenarioRefId,
            grant.releaseObjectRefId, grant.defaultLevelCode, grant.defaultLevelDisplayName, levels, timeEventAnchors(grant.id, grant.version),
            findReleaseMetricAnalysisDimensionMatrix().filter { it.analysisDimensionId == grant.id && it.analysisDimensionVersion == grant.version }, grant.requiredByDefault, grant.sortOrder)
    }
    fun findScenarioFilterReferences(): List<ScenarioFilterReference> = selection.filters.map { grant ->
        val dimension = selection.dimensions.firstOrNull { it.id == grant.targetId && it.version == grant.targetVersion }
            ?: throw ProducerContractException("frozen filter target is outside the granted exact dimensions")
        val mappings = eventMappings(grant.targetId, grant.targetVersion)
        val time = release.timeAnchorRule.any { anchor -> anchor.analysisDimensionId == grant.targetId && anchor.analysisDimensionVersion == grant.targetVersion &&
            mappings.any { it.eventId == anchor.eventId && it.eventVersion == anchor.eventVersion && it.levelCode == anchor.levelCode } }
        if (grant.valueProviderId != null) objects.require("LOGIC_UNIT", requireNotNull(grant.valueProviderLogicUnitId), requireNotNull(grant.valueProviderLogicUnitVersion))
        ScenarioFilterReference(grant.id, grant.targetType, if (time) "TIME_DIMENSION" else "DIMENSION", grant.targetId, grant.targetVersion, dimension.displayName,
            dimension.releaseObjectRefId, grant.operators, grant.valueProviderId, grant.valueProviderType, grant.valueProviderLogicUnitId, grant.valueProviderLogicUnitVersion,
            mappings, grant.requiredByDefault, grant.sortOrder)
    }
    fun findScenarioPostProcessCapabilities(): List<PostProcessCapability> = selection.stages
    fun findReleaseMetricAnalysisDimensionMatrix(): List<MetricAnalysisDimensionCapability> = release.metricAnalysisDimensionCapability.filter { value ->
        selection.metrics.any { it.adoptionId == value.adoptionId && it.adoptionVersion == value.adoptionVersion } &&
            selection.dimensions.any { it.id == value.analysisDimensionId && it.version == value.analysisDimensionVersion }
    }.map { value ->
        val grant = selection.metrics.first { it.adoptionId == value.adoptionId && it.adoptionVersion == value.adoptionVersion }
        val adoption = objects.scenarioAdoption(grant)
        if (adoption.definition.id != value.metricId || adoption.definition.version != value.metricVersion.toString()) throw ProducerContractException("frozen dimension matrix differs from its exact adoption definition")
        MetricAnalysisDimensionCapability(adoption.instance, value.eventId, value.eventVersion, value.analysisDimensionId, value.analysisDimensionVersion, value.levelCode,
            value.available, value.reasonCode, value.reasonMessage, value.producerStage, value.targetRef, value.targetVersionRef, value.blocking, value.pageBlocking, value.executableBlocking)
    }
    fun findReleaseDisabledReasons(): List<DisabledReason> = release.unavailableReason.map { value -> DisabledReason(value.id, value.releaseId, value.releaseVersion,
        value.scopeType, value.scenarioId, value.scenarioVersion, value.metricId, value.metricVersion, value.analysisDimensionId, value.analysisDimensionVersion, value.levelCode,
        value.reasonCode, value.reasonMessage, value.producerStage, value.targetRef, value.targetVersionRef, value.blocking, value.pageBlocking, value.executableBlocking) }
    fun findReleaseDiagnostics(): List<DiagnosticIssueRef> = release.diagnostics.map { value -> DiagnosticIssueRef(value.diagnosticRunId, value.issueId, value.severity,
        value.reasonCode, value.reasonMessage, value.producerStage, value.targetRef, value.targetVersionRef, value.blocking, value.pageBlocking, value.executableBlocking) }
    private fun levelCodes(id: String, version: Long): List<String> = release.dimensionMappings.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version }.map { it.levelCode }.distinct().sorted()
    private fun timeLevelCodes(id: String, version: Long): List<String> = release.timeAnchorRule.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version }.map { it.levelCode }.distinct().sorted()
    private fun timeEventAnchors(id: String, version: Long): List<TimeAnchorEventCandidate> = release.timeAnchorRule.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version }.map { anchor ->
        val matches = release.dimensionMappings.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version && it.levelCode == anchor.levelCode && it.eventId == anchor.eventId && it.eventVersion == anchor.eventVersion }
        if (matches.size != 1) throw ProducerContractException("time anchor requires one exact frozen dimension/event mapping")
        val mapping = matches.single()
        TimeAnchorEventCandidate(mapping.mappingId, id, version, anchor.levelCode, mapping.levelDisplayName, anchor.eventId, anchor.eventVersion,
            objectDisplayName("EVENT", anchor.eventId, anchor.eventVersion), anchor.topologyId, anchor.topologyVersion, anchor.topologyImplementationNamespaceCode,
            anchor.topologyImplementationNamespaceDisplayName, anchor.effectiveTimeLogicUnitId, anchor.effectiveTimeLogicUnitVersion, anchor.effectiveTimeLogicUnitDisplayName, anchor.timeRole)
    }
    private fun eventMappings(id: String, version: Long): List<AnalysisDimensionEventMappingCandidate> = release.dimensionMappings.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version }.map { mapping ->
        AnalysisDimensionEventMappingCandidate(mapping.mappingId, id, version, mapping.levelCode, mapping.levelDisplayName, mapping.eventId, mapping.eventVersion,
            objectDisplayName("EVENT", mapping.eventId, mapping.eventVersion), mapping.dimensionId, mapping.dimensionVersion, mapping.dimensionCodeNamespace,
            mapping.dimensionCodeNamespaceDisplayName, mapping.dimensionDisplayName, mapping.dimensionBindingId, mapping.dimensionBindingVersion, mapping.logicUnitId,
            mapping.logicUnitVersion, mapping.logicUnitDisplayName, mapping.valueAlignmentKey, mapping.valueAlignmentStatus)
    }
    private fun objectDisplayName(type: String, id: String, version: Long): String {
        val matches = release.objectVersionRefs.filter { it.objectType == type && it.objectId == id && it.objectVersion == version }
        if (matches.size != 1) throw ProducerContractException("exact frozen object display name is required")
        return matches.single().objectDisplayName
    }
}
