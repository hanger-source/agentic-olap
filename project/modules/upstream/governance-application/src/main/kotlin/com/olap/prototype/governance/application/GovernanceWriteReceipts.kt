package com.olap.prototype.governance.application

import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest
import com.olap.prototype.warehouse.onboarding.SourceDiscoveryReceipt

/** 本次成功写结果的读取坐标；状态是写结果的观察值，不是未来读取时的状态。 */
sealed interface GovernanceWriteEffect {
    val readOperation: String
    val status: String?

    data class AssetVersion(val reference: DefinitionImpact.Reference, override val status: String?) : GovernanceWriteEffect {
        override val readOperation: String = "read-asset-version"
    }

    /** 成员由精确 owner 版本及该版本内的身份定位，不伪装成独立版本资产。 */
    data class OwnedMember(val owner: DefinitionImpact.Reference, val kind: String, val id: String,
        override val readOperation: String, override val status: String?) : GovernanceWriteEffect

    data class Release(val reference: ReleaseIdentityRequest, override val status: String?) : GovernanceWriteEffect {
        override val readOperation: String = "read-release-identity"
    }

    data class SourceDiscovery(val snapshotId: String, override val status: String?) : GovernanceWriteEffect {
        override val readOperation: String = "read-source-discovery"
    }
}

/** 正式写 owner 从实际返回值提炼一条有界引用；完整定义和成员仍走已有读取权限与分页。 */
object GovernanceWriteReceipts {
    fun project(value: Any): GovernanceWriteEffect = when (value) {
        is SubjectContextRevisionResult -> project(value.subjectArea)
        is PhysicalTableCreateResult -> with(value.physicalTableVersion) { asset("PHYSICAL_TABLE", physicalTableId, version, versionStatus) }
        is PhysicalTableColumnsUpdateResult -> with(value.physicalTableVersion) { asset("PHYSICAL_TABLE", physicalTableId, version, versionStatus) }
        is PhysicalTableMetadataUpdateResult -> with(value.physicalTableVersion) { asset("PHYSICAL_TABLE", physicalTableId, version, versionStatus) }
        is BusinessLineCreateResult -> with(value.businessLineVersion) { asset("BUSINESS_LINE", businessLineId, version, versionStatus) }
        is LogicalTableCreateResult -> with(value.logicalTableVersion) { asset("LOGICAL_TABLE", logicalTableId, version, versionStatus) }
        is TopologyCreateResult -> with(value.topologyVersion) { asset("TOPOLOGY", topologyId, version, versionStatus) }
        is JoinPathCreateResult -> with(value.joinPathVersion) { asset("JOIN_PATH", joinPathId, version, versionStatus) }
        is EventCreateResult -> with(value.eventVersion) { asset("EVENT", eventId, version, versionStatus) }
        is MetricCreateResult -> with(value.metricVersion) { asset("METRIC", metricId, version, versionStatus) }
        is MetricCalculationCreateResult -> with(value.metricVersion) { asset("METRIC", metricId, version, versionStatus) }
        is DimensionCreateResult -> with(value.dimensionVersion) { asset("DIMENSION", dimensionId, version, versionStatus) }
        is DimensionEventBindingCreateResult -> with(value.bindingVersion) { asset("DIMENSION_EVENT_BINDING", bindingId, version, versionStatus) }
        is SubjectAreaCreateResult -> with(value.subjectAreaVersion) { asset("SUBJECT_AREA", subjectAreaId, version, versionStatus) }
        is AnalysisDimensionCreateResult -> with(value.analysisDimensionVersion) { asset("ANALYSIS_DIMENSION", analysisDimensionId, version, versionStatus) }
        is ScenarioCreateResult -> with(value.scenarioVersion) { asset("SCENARIO", scenarioId, version, versionStatus) }
        is MemberDefinitionRecord -> asset("MEMBER_DEFINITION", value.id, value.currentVersion, value.status)
        is MemberSourceRecord -> asset("MEMBER_SOURCE", value.id, value.subjectAreaVersion, null)
        is SubjectAreaMetricRefCreateResult -> with(value.subjectAreaMetricRef) {
            member("SUBJECT_AREA", subjectAreaId, subjectAreaVersion, "METRIC_ADOPTION", id, "read-subject-area-metrics", status)
        }
        is TopologyLogicalTableRefCreateResult -> with(value.topologyLogicalTableRef) {
            member("TOPOLOGY", topologyId, topologyVersion, "TOPOLOGY_LOGICAL_TABLE_REF", id, "read-topology-tables", null)
        }
        is AnalysisDimensionEventMappingCreateResult -> with(value.mapping) {
            member("ANALYSIS_DIMENSION", analysisDimensionId, analysisDimensionVersion, "ANALYSIS_DIMENSION_EVENT_MAPPING", id, "read-analysis-dimension-mappings", null)
        }
        is AnalysisDimensionValueProviderCreateResult -> with(value.provider) {
            member("ANALYSIS_DIMENSION", analysisDimensionId, analysisDimensionVersion, "ANALYSIS_DIMENSION_VALUE_PROVIDER", id, "read-analysis-dimension-providers", status)
        }
        is AnalysisDimensionValueAlignmentRecord -> with(value) {
            member("ANALYSIS_DIMENSION", analysisDimensionId, analysisDimensionVersion, "ANALYSIS_DIMENSION_VALUE_ALIGNMENT", id, "read-analysis-dimension-alignments", diagnosticStatus)
        }
        is ScenarioMetricRefCreateResult -> with(value.ref) {
            member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_METRIC_REF", id, "read-scenario-metrics", null)
        }
        is ScenarioAnalysisDimensionRefCreateResult -> with(value.ref) {
            member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_ANALYSIS_DIMENSION_REF", id, "read-scenario-dimensions", null)
        }
        is ScenarioFilterRefCreateResult -> with(value.ref) {
            member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_FILTER_REF", id, "read-scenario-filters", null)
        }
        is ScenarioPipelineRuleCreateResult -> pipelineRule(value.rule)
        is ScenarioPipelineRuleRecord -> pipelineRule(value)
        is ScenarioMemberSourceRefRecord -> with(value) {
            member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_MEMBER_SOURCE_REF", sourceId, "read-scenario-member-sources", null)
        }
        is ScenarioRelationFilterRecord -> with(value) {
            member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_RELATION_FILTER", code, "read-scenario-relation-filters", null)
        }
        is GovernanceVersionActivationResult -> with(value) { asset(objectType, objectId, objectVersion, status) }
        is GovernanceVersionDeprecationResult -> with(value) { asset(objectType, objectId, objectVersion, status) }
        is SubjectAreaReleaseBuildResult -> GovernanceWriteEffect.Release(ReleaseIdentityRequest(value.releaseId, value.releaseVersion), value.buildStatus)
        is SourceDiscoveryReceipt -> GovernanceWriteEffect.SourceDiscovery(value.snapshotId, value.status)
        else -> error("governance write result has no effect projection: ${value.javaClass.name}")
    }

    private fun asset(type: String, id: String, version: Long, status: String?) =
        GovernanceWriteEffect.AssetVersion(coordinate(type, id, version), status)

    private fun member(ownerType: String, ownerId: String, ownerVersion: Long, kind: String, id: String, read: String, status: String?) =
        GovernanceWriteEffect.OwnedMember(coordinate(ownerType, ownerId, ownerVersion), kind, id, read, status)

    private fun pipelineRule(value: ScenarioPipelineRuleRecord) = with(value) {
        member("SCENARIO", scenarioId, scenarioVersion, "SCENARIO_PIPELINE_RULE", id, "read-scenario-pipeline-rules", status)
    }

    private fun coordinate(type: String, id: String, version: Long) = DefinitionImpact.Reference(type, id, version.toString())
}
