package com.olap.prototype.release.contract

import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.contract.FilterLeafOperatorPolicy
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.PostProcessCapability
import com.olap.prototype.query.contract.QueryScenarioRef
import com.olap.prototype.query.contract.SubjectAreaCapabilityContext

data class ScenarioMetricReference(val adoptionId: String, val adoptionVersion: Long, val adoptionStatus: String, val scenarioMetricRefId: String, val releaseObjectRefId: String, val requiredByDefault: Boolean, val sortOrder: Int)

/** 本次发布的完整批准选择；执行类型由后续编译决定。 */
class ReleaseScenarioSnapshot(val formatVersion: String, val scenario: QueryScenarioRef, val subjectArea: SubjectAreaCapabilityContext, metrics: List<ScenarioMetricReference>, members: List<MemberGrant>, dimensions: List<DimensionGrant>, filters: List<FilterGrant>, stages: List<PostProcessCapability>) {
    val metrics: List<ScenarioMetricReference> = java.util.List.copyOf(metrics)
    val members: List<MemberGrant> = java.util.List.copyOf(members)
    val dimensions: List<DimensionGrant> = java.util.List.copyOf(dimensions)
    val filters: List<FilterGrant> = java.util.List.copyOf(filters)
    val stages: List<PostProcessCapability> = java.util.List.copyOf(stages)
    init {
        require(formatVersion == FORMAT_VERSION) { "exact frozen scenario format is required" }
        distinct(this.metrics.map { it.adoptionId })
        distinct(this.members.map { "${it.sourceId}@${it.sourceVersion}" })
        distinct(this.dimensions.map { "${it.id}@${it.version}" })
        distinct(this.filters.map { it.id }); distinct(this.stages.map { it.ruleId })
        require(this.metrics.all { it.adoptionVersion == subjectArea.subjectAreaVersion }) { "frozen metric adoption differs from its subject-area version" }
    }
    data class MemberGrant(val sourceId: String, val sourceVersion: Long, val permission: MemberQueryPermission) {
        init { ProducerContracts.requireText(sourceId, "member source"); ProducerContracts.requireVersion(sourceVersion, "member version") }
    }
    data class DimensionGrant(val id: String, val version: Long, val displayName: String, val outputAlias: String, val subjectAreaRefId: String, val subjectAreaRefStatus: String, val scenarioRefId: String, val releaseObjectRefId: String, val defaultLevelCode: String, val defaultLevelDisplayName: String, val requiredByDefault: Boolean, val sortOrder: Int) {
        init {
            ProducerContracts.requireVersion(version, "dimension version")
            listOf(id, displayName, outputAlias, subjectAreaRefId, subjectAreaRefStatus, scenarioRefId, releaseObjectRefId, defaultLevelCode, defaultLevelDisplayName).forEach { ProducerContracts.requireText(it, "frozen dimension choice") }
        }
    }
    data class FilterGrant(val id: String, val targetType: String, val targetId: String, val targetVersion: Long, val operatorsJson: String, val valueProviderId: String?, val valueProviderType: String?, val valueProviderLogicUnitId: String?, val valueProviderLogicUnitVersion: Long?, val requiredByDefault: Boolean, val sortOrder: Int) {
        init {
            ProducerContracts.requireText(id, "filter id")
            require(targetType == "ANALYSIS_DIMENSION") { "frozen filter requires an analysis dimension" }
            ProducerContracts.requireText(targetId, "filter target"); ProducerContracts.requireVersion(targetVersion, "filter target version")
            FilterLeafOperatorPolicy.requireOperatorSet(operatorsJson, "frozen filter operators")
            if (valueProviderId == null) {
                require(valueProviderType == null && valueProviderLogicUnitId == null && valueProviderLogicUnitVersion == null) { "absent frozen provider cannot carry source details" }
            } else {
                ProducerContracts.requireText(valueProviderId, "provider id")
                require(valueProviderType == "LOGIC_UNIT_DISTINCT") { "unsupported frozen value provider" }
                ProducerContracts.requireText(valueProviderLogicUnitId, "provider LogicUnit"); ProducerContracts.requireVersion(valueProviderLogicUnitVersion, "provider LogicUnit version")
            }
        }
    }
    override fun equals(other: Any?): Boolean = other is ReleaseScenarioSnapshot && formatVersion == other.formatVersion && scenario == other.scenario && subjectArea == other.subjectArea && metrics == other.metrics && members == other.members && dimensions == other.dimensions && filters == other.filters && stages == other.stages
    override fun hashCode(): Int = listOf(formatVersion, scenario, subjectArea, metrics, members, dimensions, filters, stages).hashCode()
    companion object {
        const val FORMAT_VERSION = "olap-release-scenario/v1"
        private fun distinct(values: List<String>) { require(values.toSet().size == values.size) { "duplicate frozen scenario choice" } }
    }
}
