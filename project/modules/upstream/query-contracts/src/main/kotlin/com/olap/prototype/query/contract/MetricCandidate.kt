package com.olap.prototype.query.contract

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.metric.*
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections

class MetricCandidate(val adoptionId: String, val adoptionVersion: Long, val subjectAreaId: String, val subjectAreaVersion: Long,
    val code: String, val displayName: String, val businessMeaning: MetricBusinessMeaning, val definition: MetricRef, val instance: MetricInstanceRef,
    val valueContract: MetricValueContract, val snapshotMeaning: MetricSnapshotRule.Meaning?, val adoptionStatus: String,
    val scenarioMetricRefId: String, val releaseObjectRefId: String, sources: List<MetricSource>, val definitionKind: String,
    fixedArguments: Map<String, Calculation.Value>, parameters: Map<String, Calculation.Parameter>,
    groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>, memberParameters: Map<String, Calculation.MemberSlot>, relationParameters: Map<String, Calculation.RelationSlot>,
    val requiredByDefault: Boolean, val sortOrder: Int) {
    val sources: List<MetricSource> = java.util.List.copyOf(sources)
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    val parameters: Map<String, Calculation.Parameter> = java.util.Map.copyOf(parameters)
    val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = Collections.unmodifiableMap(groupingCandidates.mapValuesTo(linkedMapOf()) { java.util.List.copyOf(it.value) })
    val memberParameters: Map<String, Calculation.MemberSlot> = java.util.Map.copyOf(memberParameters)
    val relationParameters: Map<String, Calculation.RelationSlot> = java.util.Map.copyOf(relationParameters)
    init {
        ProducerContracts.requireText(adoptionId, "adoptionId"); ProducerContracts.requireVersion(adoptionVersion, "adoptionVersion")
        ProducerContracts.requireText(subjectAreaId, "subjectAreaId"); ProducerContracts.requireVersion(subjectAreaVersion, "subjectAreaVersion")
        if (adoptionVersion != subjectAreaVersion) throw ProducerContractException("adoption version differs from its subject-area version")
        ProducerContracts.requireText(code, "code"); ProducerContracts.requireText(displayName, "displayName"); ProducerContracts.requireText(adoptionStatus, "adoptionStatus")
        ProducerContracts.requireText(scenarioMetricRefId, "scenarioMetricRefId"); ProducerContracts.requireText(releaseObjectRefId, "releaseObjectRefId")
        if (definitionKind !in setOf("FACT_MEASURE", "CALCULATION")) throw ProducerContractException("unknown metric definition kind")
        if (this.fixedArguments.keys.intersect(this.parameters.keys).isNotEmpty()) throw ProducerContractException("fixed and query arguments overlap")
    }
    override fun equals(other: Any?): Boolean = other is MetricCandidate && adoptionId == other.adoptionId && adoptionVersion == other.adoptionVersion && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && businessMeaning == other.businessMeaning && definition == other.definition && instance == other.instance && valueContract == other.valueContract && snapshotMeaning == other.snapshotMeaning && adoptionStatus == other.adoptionStatus && scenarioMetricRefId == other.scenarioMetricRefId && releaseObjectRefId == other.releaseObjectRefId && sources == other.sources && definitionKind == other.definitionKind && fixedArguments == other.fixedArguments && parameters == other.parameters && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(adoptionId, adoptionVersion, subjectAreaId, subjectAreaVersion, code, displayName, businessMeaning, definition, instance, valueContract, snapshotMeaning, adoptionStatus, scenarioMetricRefId, releaseObjectRefId, sources, definitionKind, fixedArguments, parameters, groupingCandidates, memberParameters, relationParameters, requiredByDefault, sortOrder).hashCode()
}
