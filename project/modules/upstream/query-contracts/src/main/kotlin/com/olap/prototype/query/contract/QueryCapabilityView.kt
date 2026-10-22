package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContracts

class QueryCapabilityView(val scenario: QueryScenarioRef, val subjectAreaCapabilityContext: SubjectAreaCapabilityContext,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, metricCandidates: List<MetricCandidate>,
    memberCandidates: List<MemberCandidate>, analysisDimensionCandidates: List<AnalysisDimensionCandidate>, timeAnalysisDimensionCandidates: List<TimeAnalysisDimensionCandidate>,
    filterCandidates: List<FilterCandidate>, relationFilterCandidates: List<RelationFilterCandidate>, temporalClockCandidates: List<TemporalClockCandidate>,
    postProcessCapabilities: List<PostProcessCapability>, metricAnalysisDimensionMatrix: List<MetricAnalysisDimensionCapability>,
    disabledReasons: List<DisabledReason>, diagnostics: List<DiagnosticIssueRef>, val executionTypes: QueryExecutionTypes,
    val nativeDefinitions: QueryNativeDefinitions, val capabilityHash: String?) {
    val metricCandidates: List<MetricCandidate> = java.util.List.copyOf(metricCandidates)
    val memberCandidates: List<MemberCandidate> = java.util.List.copyOf(memberCandidates)
    val analysisDimensionCandidates: List<AnalysisDimensionCandidate> = java.util.List.copyOf(analysisDimensionCandidates)
    val timeAnalysisDimensionCandidates: List<TimeAnalysisDimensionCandidate> = java.util.List.copyOf(timeAnalysisDimensionCandidates)
    val filterCandidates: List<FilterCandidate> = java.util.List.copyOf(filterCandidates)
    val relationFilterCandidates: List<RelationFilterCandidate> = java.util.List.copyOf(relationFilterCandidates)
    val temporalClockCandidates: List<TemporalClockCandidate> = java.util.List.copyOf(temporalClockCandidates)
    val postProcessCapabilities: List<PostProcessCapability> = java.util.List.copyOf(postProcessCapabilities)
    val metricAnalysisDimensionMatrix: List<MetricAnalysisDimensionCapability> = java.util.List.copyOf(metricAnalysisDimensionMatrix)
    val disabledReasons: List<DisabledReason> = java.util.List.copyOf(disabledReasons)
    val diagnostics: List<DiagnosticIssueRef> = java.util.List.copyOf(diagnostics)
    init {
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(releaseDisplayName, "releaseDisplayName")
        require(this.metricCandidates.isNotEmpty() || this.memberCandidates.isNotEmpty()) { "published scenario requires at least one real query entry" }
        require(this.relationFilterCandidates.map { it.definition.relation.id }.distinct().size == this.relationFilterCandidates.size) { "relation filter candidates must have distinct exact references" }
        require(this.temporalClockCandidates.map { it.clockRef }.distinct().size == this.temporalClockCandidates.size) { "published temporal clock slots must be unique" }
        val metricKeys = this.metricCandidates.map { it.instance.id }.toSet()
        val memberKeys = this.memberCandidates.map { "${it.sourceId}@${it.sourceVersion}" }.toSet()
        require(metricKeys.size == this.metricCandidates.size && memberKeys.size == this.memberCandidates.size) { "public query entries require unique exact metric and member versions" }
        require(executionTypes.metricInstances.keys == metricKeys && executionTypes.members.keys == memberKeys) { "native execution types must exactly describe public query entries" }
        this.metricCandidates.forEach { metric -> requireContextParameters(executionTypes.metricInstances.getValue(metric.instance.id), metric.memberParameters.keys, metric.relationParameters.keys) }
        this.memberCandidates.forEach { member ->
            when (val context = executionTypes.members.getValue("${member.sourceId}@${member.sourceVersion}")) {
                is QueryExecutionTypes.ComputedMember -> requireContextParameters(context.context, member.memberParameters.keys, member.relationParameters.keys)
                is QueryExecutionTypes.ProjectionMember -> require(context.outputs.keys == member.outputs.keys) { "native member types must exactly describe granted outputs" }
            }
        }
        val expectedDimensions = this.analysisDimensionCandidates.flatMap { dimension -> dimension.eventMappings.map { mapping ->
            "${dimension.analysisDimensionId}@${dimension.analysisDimensionVersion}:${mapping.levelCode}:${mapping.eventId}@${mapping.eventVersion}"
        } }.toSet()
        val actualDimensions = executionTypes.dimensions.map { "${it.dimension.id}@${it.dimension.version}:${it.dimension.levelCode}:${it.eventId}@${it.eventVersion}" }
        require(actualDimensions.distinct().size == actualDimensions.size && actualDimensions.toSet() == expectedDimensions) { "native dimension types must exactly describe granted event mappings" }
    }
    override fun equals(other: Any?): Boolean = other is QueryCapabilityView && scenario == other.scenario && subjectAreaCapabilityContext == other.subjectAreaCapabilityContext && releaseId == other.releaseId && releaseVersion == other.releaseVersion && releaseDisplayName == other.releaseDisplayName && metricCandidates == other.metricCandidates && memberCandidates == other.memberCandidates && analysisDimensionCandidates == other.analysisDimensionCandidates && timeAnalysisDimensionCandidates == other.timeAnalysisDimensionCandidates && filterCandidates == other.filterCandidates && relationFilterCandidates == other.relationFilterCandidates && temporalClockCandidates == other.temporalClockCandidates && postProcessCapabilities == other.postProcessCapabilities && metricAnalysisDimensionMatrix == other.metricAnalysisDimensionMatrix && disabledReasons == other.disabledReasons && diagnostics == other.diagnostics && executionTypes == other.executionTypes && nativeDefinitions == other.nativeDefinitions && capabilityHash == other.capabilityHash
    override fun hashCode(): Int = listOf(scenario, subjectAreaCapabilityContext, releaseId, releaseVersion, releaseDisplayName, metricCandidates, memberCandidates, analysisDimensionCandidates, timeAnalysisDimensionCandidates, filterCandidates, relationFilterCandidates, temporalClockCandidates, postProcessCapabilities, metricAnalysisDimensionMatrix, disabledReasons, diagnostics, executionTypes, nativeDefinitions, capabilityHash).hashCode()
    private fun requireContextParameters(context: QueryExecutionTypes.ContextBinding, members: Set<String>, relations: Set<String>) {
        require(context !is QueryExecutionTypes.Inputs || context.memberParameters == members && context.relationParameters == relations) { "dynamic execution context must name exactly the remaining member and relation parameters" }
    }
}
