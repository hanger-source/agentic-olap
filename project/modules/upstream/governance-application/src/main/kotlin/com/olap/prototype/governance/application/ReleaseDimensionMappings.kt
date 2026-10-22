package com.olap.prototype.governance.application

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.management.governance.CanonicalReleaseRepository.ReleaseDimensionMappingRecord
import com.olap.prototype.management.governance.CanonicalReleaseRepository.ReleaseTimeAnchorRuleRecord
import com.olap.prototype.release.contract.ReleaseJoinPathRef
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant

/** 选择和冻结精确维度映射，不写数据库。 */
internal class ReleaseDimensionMappings(private val analysisDimensions: AnalysisDimensionGovernanceService, private val dimensions: DimensionGovernanceService,
    private val events: EventGovernanceService, private val topologies: TopologyGovernanceService, private val logicalModel: LogicalModelGovernanceService) {
    fun freeze(release: SubjectAreaReleaseBuildRequest, mapping: AnalysisDimensionEventMappingRecord, at: Instant): ReleaseDimensionMappingRecord {
        val analysis = analysisDimensions.analysisDimensionSnapshot(mapping.analysisDimensionId, mapping.analysisDimensionVersion)
        if (analysis.subjectAreaId != release.subjectAreaId || analysis.subjectAreaVersion != release.subjectAreaVersion)
            throw ProducerContractException("frozen mapping belongs to another exact subject area")
        val level = analysisDimensions.findAnalysisDimensionLevel(mapping.analysisDimensionId, mapping.analysisDimensionVersion, mapping.levelCode)
            ?: throw ProducerContractException("dimension mapping level is missing")
        val event = events.eventSnapshot(mapping.eventId, mapping.eventVersion)
        val dimension = dimensions.dimensionSnapshot(mapping.dimensionId, mapping.dimensionVersion)
        val binding = dimensions.dimensionEventBindingSnapshot(mapping.bindingId, mapping.bindingVersion)
        if (binding.eventId != event.id || binding.eventVersion != mapping.eventVersion || binding.dimensionId != dimension.id || binding.dimensionVersion != mapping.dimensionVersion)
            throw ProducerContractException("frozen mapping differs from its exact event/dimension binding")
        val topology = topologies.topologySnapshot(event.topologyId, event.topologyVersion)
        val unitId = requireNotNull(binding.logicUnitId) { "dimension binding logic unit is required" }
        val unitVersion = requireNotNull(binding.logicUnitVersion) { "dimension binding logic unit version is required" }
        val unit = logicalModel.logicUnitSnapshot(unitId, unitVersion)
        analysisDimensions.validateAnalysisDimensionAlignmentRef(mapping)
        return ReleaseDimensionMappingRecord(ReleaseFreezer.releaseObjectId("rdm", release.releaseId, release.releaseVersion, mapping.id), mapping.id,
            release.releaseId, release.releaseVersion, release.subjectAreaId, mapping.eventId, mapping.eventVersion, mapping.analysisDimensionId, mapping.analysisDimensionVersion,
            mapping.levelCode, level.displayName, mapping.dimensionId, mapping.dimensionVersion, dimension.dimensionCodeNamespace, dimension.dimensionCodeNamespaceDisplayName,
            dimension.displayName, mapping.bindingId, mapping.bindingVersion, mapping.alignmentId, mapping.alignmentStatus, event.topologyId, event.topologyVersion,
            topology.topologyImplementationNamespaceCode, topology.topologyImplementationNamespaceDisplayName, unitId, unitVersion, unit.displayName,
            joinPathRefsJson(event, binding), executableExpression(binding), at)
    }
    fun timeAnchor(mapping: ReleaseDimensionMappingRecord): ReleaseTimeAnchorRuleRecord {
        fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        val role = "ANALYSIS_DIMENSION:${safe(mapping.analysisDimensionId)}:${safe(mapping.levelCode)}"
        return ReleaseTimeAnchorRuleRecord(ReleaseFreezer.releaseObjectId("rta", mapping.releaseId, mapping.releaseVersion, mapping.eventId, mapping.eventVersion,
            mapping.analysisDimensionId, mapping.analysisDimensionVersion, mapping.levelCode, role), mapping.releaseId, mapping.releaseVersion, mapping.subjectAreaId, null, null,
            mapping.eventId, mapping.eventVersion, mapping.topologyId, mapping.topologyVersion, mapping.topologyImplementationNamespaceCode, mapping.topologyImplementationNamespaceDisplayName,
            mapping.logicUnitId, mapping.logicUnitVersion, mapping.logicUnitDisplayName, role, mapping.analysisDimensionId, mapping.analysisDimensionVersion, mapping.levelCode,
            "[]", null, null, "{\"producer\":\"release_dimension_mapping\"}", mapping.createdAt)
    }
    private fun executableExpression(binding: DimensionEventBindingRecord): String {
        val expression = binding.expressionJson?.takeUnless(String::isBlank)
            ?: throw ProducerContractException("dimension_event_binding.expression_json is required: ${binding.id}")
        return try { SemanticExpressionCodec.write(SemanticExpressionCodec.parse(expression, "dimension_event_binding.expression_json")) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException(failure.message ?: "invalid dimension expression", failure) }
    }
    private fun joinPathRefsJson(event: EventRecord, binding: DimensionEventBindingRecord): String {
        val path = dimensions.bindingAccessPath(binding.id, binding.currentVersion)
        topologies.requirePositionedLogicUnit(event.topologyId, event.topologyVersion, path,
            requireNotNull(binding.logicUnitId) { "dimension binding logic unit is required" },
            requireNotNull(binding.logicUnitVersion) { "dimension binding logic unit version is required" }, "frozen dimension binding position")
        val refs = path.joinPathRefs.map { ReleaseJoinPathRef(it.joinPathId, it.joinPathVersion) }
        return try { OlapJsonUtils.mapper().writeValueAsString(refs) }
        catch (failure: Exception) { throw ProducerContractException("failed to encode release join path refs", failure) }
    }
}
