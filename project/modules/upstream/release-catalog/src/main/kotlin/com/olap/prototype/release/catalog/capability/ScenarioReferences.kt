package com.olap.prototype.release.catalog.capability

import com.olap.prototype.query.contract.AnalysisDimensionEventMappingCandidate
import com.olap.prototype.query.contract.MemberQueryPermission
import java.time.Instant

/** 场景批准本身，不携带事先推导的输出、类型或时钟候选。 */
data class ScenarioMemberReference(val sourceId: String, val sourceVersion: Long, val permission: MemberQueryPermission)

class ScenarioFilterReference(val filterRefId: String, val filterTargetType: String, val queryFieldKind: String,
    val filterTargetObjectId: String, val filterTargetObjectVersion: Long, val filterTargetDisplayName: String,
    val releaseObjectRefId: String, val operatorPolicyJson: String, val valueProviderId: String?, val valueProviderType: String?,
    val valueProviderLogicUnitId: String?, val valueProviderLogicUnitVersion: Long?, eventMappings: List<AnalysisDimensionEventMappingCandidate>,
    val requiredByDefault: Boolean, val sortOrder: Int) {
    val eventMappings: List<AnalysisDimensionEventMappingCandidate> = java.util.List.copyOf(eventMappings)
    override fun equals(other: Any?): Boolean = other is ScenarioFilterReference && filterRefId == other.filterRefId && filterTargetType == other.filterTargetType && queryFieldKind == other.queryFieldKind && filterTargetObjectId == other.filterTargetObjectId && filterTargetObjectVersion == other.filterTargetObjectVersion && filterTargetDisplayName == other.filterTargetDisplayName && releaseObjectRefId == other.releaseObjectRefId && operatorPolicyJson == other.operatorPolicyJson && valueProviderId == other.valueProviderId && valueProviderType == other.valueProviderType && valueProviderLogicUnitId == other.valueProviderLogicUnitId && valueProviderLogicUnitVersion == other.valueProviderLogicUnitVersion && eventMappings == other.eventMappings && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(filterRefId, filterTargetType, queryFieldKind, filterTargetObjectId, filterTargetObjectVersion, filterTargetDisplayName, releaseObjectRefId, operatorPolicyJson, valueProviderId, valueProviderType, valueProviderLogicUnitId, valueProviderLogicUnitVersion, eventMappings, requiredByDefault, sortOrder).hashCode()
}
data class ReleasedScenarioEntrypoint(val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String,
    val subjectAreaId: String, val subjectAreaVersion: Long, val subjectAreaDisplayName: String,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, val releasedAt: Instant)

fun interface QueryExecutionCapabilityPolicy { fun supportsPostProcess(operationType: String): Boolean }
