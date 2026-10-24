package com.olap.prototype.governance.contract

import com.olap.prototype.query.contract.StageAuthorization

import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.contract.FilterLeafOperator
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.PublishedRelationFilter
import com.olap.prototype.query.contract.RelationFilterPermission
import java.time.Instant

data class ScenarioCreateRequest(val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val owner: String, val description: String?, val createdBy: String)
data class ScenarioCreateResult(val scenario: ScenarioRecord, val scenarioVersion: ScenarioVersionRecord, val subjectAreaScenarioRef: SubjectAreaScenarioRefRecord)
data class ScenarioRecord(val id: String, val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class ScenarioVersionRecord(val id: String, val scenarioId: String, val version: Long, val versionStatus: String, val contentProto: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class ScenarioMetricRefCreateRequest(val scenarioId: String, val scenarioVersion: Long, val adoptionId: String, val adoptionVersion: Long, val requiredByDefault: Boolean?, val sortOrder: Int?)
data class ScenarioMetricRefCreateResult(val ref: ScenarioMetricRefRecord)
data class ScenarioMetricRefRecord(val id: String, val scenarioId: String, val scenarioVersion: Long, val adoptionId: String, val adoptionVersion: Long, val requiredByDefault: Boolean, val sortOrder: Int, val createdAt: Instant)
data class ScenarioMemberSourceRefCreateRequest(val scenarioId: String, val scenarioVersion: Long, val sourceId: String, val sourceVersion: Long, val permission: MemberQueryPermission)
data class ScenarioMemberSourceRefRecord(val scenarioId: String, val scenarioVersion: Long, val sourceId: String, val sourceVersion: Long, val permission: MemberQueryPermission)
data class ScenarioAnalysisDimensionRefCreateRequest(val scenarioId: String, val scenarioVersion: Long, val analysisDimensionId: String, val analysisDimensionVersion: Long, val defaultLevelCode: String?, val requiredByDefault: Boolean?, val sortOrder: Int?)
data class ScenarioAnalysisDimensionRefCreateResult(val ref: ScenarioAnalysisDimensionRefRecord)
data class ScenarioAnalysisDimensionRefRecord(val id: String, val scenarioId: String, val scenarioVersion: Long, val analysisDimensionId: String, val analysisDimensionVersion: Long, val defaultLevelCode: String?, val requiredByDefault: Boolean, val sortOrder: Int, val createdAt: Instant)
class ScenarioFilterRefCreateRequest(val scenarioId: String, val scenarioVersion: Long, val filterTargetType: String, val targetId: String, val targetVersion: Long, val valueProviderId: String?, operators: List<FilterLeafOperator>, val requiredByDefault: Boolean?, val sortOrder: Int?) {
    val operators: List<FilterLeafOperator> = java.util.List.copyOf(operators)
    companion object { @JvmField val TARGET_TYPES: Set<String> = java.util.Set.of("ANALYSIS_DIMENSION") }
    override fun equals(other: Any?): Boolean = other is ScenarioFilterRefCreateRequest && scenarioId == other.scenarioId && scenarioVersion == other.scenarioVersion && filterTargetType == other.filterTargetType && targetId == other.targetId && targetVersion == other.targetVersion && valueProviderId == other.valueProviderId && operators == other.operators && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(scenarioId, scenarioVersion, filterTargetType, targetId, targetVersion, valueProviderId, operators, requiredByDefault, sortOrder).hashCode()
}
data class ScenarioFilterRefCreateResult(val ref: ScenarioFilterRefRecord)
data class ScenarioFilterRefRecord(val id: String, val scenarioId: String, val scenarioVersion: Long, val filterTargetType: String, val targetId: String, val targetVersion: Long, val operators: List<String>, val valueProviderId: String?, val requiredByDefault: Boolean, val sortOrder: Int, val createdAt: Instant)
data class ScenarioRelationFilterCreateRequest(val scenarioId: String, val scenarioVersion: Long, val code: String, val displayName: String, val eventId: String, val eventVersion: Long, val relationshipRole: String, val memberSourceId: String, val memberSourceVersion: Long, val permission: RelationFilterPermission, val requiredByDefault: Boolean?, val sortOrder: Int?)
data class ScenarioRelationFilterRecord(val scenarioId: String, val scenarioVersion: Long, val code: String, val definition: PublishedRelationFilter) {
    init { require(definition.relation.version == scenarioVersion) { "relation filter belongs to its exact scenario version" } }
}
data class ScenarioPipelineRuleCreateRequest(val scenarioId: String, val scenarioVersion: Long, val ruleType: String, val sortOrder: Int?)
data class ScenarioPipelineRuleContractRefreshRequest(val scenarioId: String, val scenarioVersion: Long, val ruleType: String, val approvedAuthorization: StageAuthorization) {
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "identity" to "EXACT_EXISTING_SCENARIO_VERSION_AND_RULE_TYPE",
            "scenarioState" to "DRAFT_REQUIRED_UNDER_PARENT_SUBJECT_AREA_TRANSACTION_LOCK",
            "existingRule" to "EXACTLY_ONE_EXISTING_RULE_OF_RULE_TYPE_REQUIRED_CREATE_IS_A_SEPARATE_OPERATION",
            "approvedAuthorization" to "MUST_EQUAL_StageAuthorization.current(ruleType)_IN_FULL",
            "comparison" to "OPERATION_TYPE_JOIN_TYPES_AGGREGATIONS_AND_ALL_FIVE_EXPRESSION_SETS_ORDER_INDEPENDENT",
            "subsetOrSuperset" to "REJECTED_EVEN_IF_IT_COVERS_THIS_QUERY_OR_RETAINS_ALL_PREVIOUS_APPROVALS",
            "previousAuthorization" to "MAY_BE_OLDER_FROZEN_CANDIDATE_NOT_THE_NEW_APPROVAL_BASELINE",
            "effect" to "ONLY_THIS_EXISTING_DRAFT_RULE_AUTHORIZATION_REPLACED_IDENTITY_STATUS_SORT_ORDER_AND_OTHER_SCENARIOS_UNCHANGED",
            "publication" to "EXISTING_RELEASES_IMMUTABLE_ACTIVATION_AND_NEW_RELEASE_ARE_SEPARATE_EXPLICIT_OPERATIONS",
        )
    }
}
data class ScenarioPipelineRuleCreateResult(val rule: ScenarioPipelineRuleRecord)
data class ScenarioPipelineRuleRecord(val id: String, val scenarioId: String, val scenarioVersion: Long, val ruleType: String, val displayName: String, val authorization: StageAuthorization, val status: String, val sortOrder: Int, val createdAt: Instant) {
    init { ScenarioPipelineRulePolicy.requireScenarioPipelineRule(this) }
}
object ScenarioPipelineRulePolicy {
    @JvmStatic fun requireScenarioPipelineRule(record: ScenarioPipelineRuleRecord) {
        requireRuleType(record.ruleType, "scenario_pipeline_rule.rule_type")
        requireDisplayName(record.displayName, "scenario_pipeline_rule.display_name")
        require(record.authorization.operationType == record.ruleType) { "stage authorization differs from rule identity" }
        requireStatus(record.status, "scenario_pipeline_rule.status")
    }
    @JvmStatic fun requireRuleType(value: String?, field: String): String = PostOperationContract.requireType(value, field)
    @JvmStatic fun requireDisplayName(value: String?, field: String): String = ProducerContracts.requireText(value, field)
    @JvmStatic fun requireStatus(value: String?, field: String): String = GovernanceContract.requireGovernanceLifecycleStatus(value, field)
}
data class ScenarioMetricCandidate(val adoptionId: String, val adoptionVersion: Long, val code: String, val displayName: String, val businessMeaning: MetricBusinessMeaning, val definition: MetricRef, val status: String, val statusDisplayName: String, val enabled: Boolean, val disabledReason: String?)
data class ScenarioAnalysisDimensionCandidate(val analysisDimensionId: String, val analysisDimensionVersion: Long, val displayName: String, val status: String, val statusDisplayName: String, val defaultLevelCode: String, val enabled: Boolean, val disabledReason: String?)
data class ScenarioFilterValueProviderCandidate(val valueProviderId: String, val levelCode: String, val providerType: String, val providerTypeDisplayName: String, val sourceLogicUnitId: String, val sourceLogicUnitVersion: Long, val sourceLogicUnitDisplayName: String, val status: String, val statusDisplayName: String, val enabled: Boolean, val disabledReason: String?)
data class ScenarioPipelineRuleCandidate(val ruleType: String, val displayName: String, val authorization: StageAuthorization, val enabled: Boolean, val disabledReason: String?)
class ScenarioFilterCandidate(val analysisDimensionId: String, val analysisDimensionVersion: Long, val displayName: String, val status: String, val statusDisplayName: String, val defaultLevelCode: String, valueProviders: List<ScenarioFilterValueProviderCandidate>, val enabled: Boolean, val disabledReason: String?) {
    val valueProviders: List<ScenarioFilterValueProviderCandidate> = java.util.List.copyOf(valueProviders)
    override fun equals(other: Any?): Boolean = other is ScenarioFilterCandidate && analysisDimensionId == other.analysisDimensionId && analysisDimensionVersion == other.analysisDimensionVersion && displayName == other.displayName && status == other.status && statusDisplayName == other.statusDisplayName && defaultLevelCode == other.defaultLevelCode && valueProviders == other.valueProviders && enabled == other.enabled && disabledReason == other.disabledReason
    override fun hashCode(): Int = listOf(analysisDimensionId, analysisDimensionVersion, displayName, status, statusDisplayName, defaultLevelCode, valueProviders, enabled, disabledReason).hashCode()
}
class ScenarioConfigurationCandidatesView(metricCandidates: List<ScenarioMetricCandidate>, analysisDimensionCandidates: List<ScenarioAnalysisDimensionCandidate>, filterCandidates: List<ScenarioFilterCandidate>, pipelineRuleCandidates: List<ScenarioPipelineRuleCandidate>, val currentMetricCount: Int, val currentAnalysisDimensionCount: Int, val currentFilterCount: Int, val currentPipelineRuleCount: Int) {
    val metricCandidates: List<ScenarioMetricCandidate> = java.util.List.copyOf(metricCandidates)
    val analysisDimensionCandidates: List<ScenarioAnalysisDimensionCandidate> = java.util.List.copyOf(analysisDimensionCandidates)
    val filterCandidates: List<ScenarioFilterCandidate> = java.util.List.copyOf(filterCandidates)
    val pipelineRuleCandidates: List<ScenarioPipelineRuleCandidate> = java.util.List.copyOf(pipelineRuleCandidates)
    override fun equals(other: Any?): Boolean = other is ScenarioConfigurationCandidatesView && metricCandidates == other.metricCandidates && analysisDimensionCandidates == other.analysisDimensionCandidates && filterCandidates == other.filterCandidates && pipelineRuleCandidates == other.pipelineRuleCandidates && currentMetricCount == other.currentMetricCount && currentAnalysisDimensionCount == other.currentAnalysisDimensionCount && currentFilterCount == other.currentFilterCount && currentPipelineRuleCount == other.currentPipelineRuleCount
    override fun hashCode(): Int = listOf(metricCandidates, analysisDimensionCandidates, filterCandidates, pipelineRuleCandidates, currentMetricCount, currentAnalysisDimensionCount, currentFilterCount, currentPipelineRuleCount).hashCode()
}
data class ScenarioIndexItem(val id: String, val code: String, val displayName: String, val subjectAreaId: String, val subjectAreaVersion: Long, val subjectAreaDisplayName: String, val businessLineId: String, val businessLineDisplayName: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?, val adjustableMetricCount: Long, val adjustableAnalysisDimensionCount: Long, val filterCount: Long, val pipelineRuleCount: Long, val defaultQuerySummary: String?, val releaseStatus: String, val staleStatus: String, val indexStatus: String, val indexStatusDisplayName: String, val readinessState: String, val readinessLabel: String, val readinessDetail: String?, val blockingIssueCount: Long, val currentPublishedReleaseId: String?, val currentPublishedReleaseVersion: String?, val currentPublishedReleaseDisplayName: String?, val lastChangedAt: Instant)
