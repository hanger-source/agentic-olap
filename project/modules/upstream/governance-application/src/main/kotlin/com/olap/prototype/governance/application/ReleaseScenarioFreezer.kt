package com.olap.prototype.governance.application

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.AnalysisDimensionGovernanceService
import com.olap.prototype.management.governance.CanonicalReleaseRepository.ReleaseObjectRefRecord
import com.olap.prototype.management.governance.CanonicalReleaseRepository.SubjectAreaReleaseRecord
import com.olap.prototype.management.governance.ScenarioGovernanceService
import com.olap.prototype.query.contract.PostProcessCapability
import com.olap.prototype.query.contract.QueryScenarioRef
import com.olap.prototype.query.contract.SubjectAreaCapabilityContext
import com.olap.prototype.release.contract.ReleaseCatalogRepository
import com.olap.prototype.release.contract.ReleaseDiagnosticIssue
import com.olap.prototype.release.contract.ReleaseScenarioSnapshot
import com.olap.prototype.release.contract.ScenarioMetricReference

/** 冻结一次精确场景选择，不持有全发布依赖缓存或写入发布行。 */
internal class ReleaseScenarioFreezer(private val release: SubjectAreaReleaseRecord, private val scenarios: ScenarioGovernanceService,
    private val analysisDimensions: AnalysisDimensionGovernanceService, private val diagnostics: ReleaseCatalogRepository) {
    fun freeze(selected: SubjectAreaScenarioRefRecord, adoptedMetrics: List<SubjectAreaMetricRefRecord>, adoptedDimensions: List<SubjectAreaAnalysisDimensionRefRecord>,
        frozenObjects: List<ReleaseObjectRefRecord>): FrozenScenario {
        if (release.subjectAreaId != selected.subjectAreaId || release.subjectAreaVersion != selected.subjectAreaVersion)
            throw ProducerContractException("selected scenario belongs to another exact subject area")
        val scenario = scenarios.scenarioSnapshot(selected.scenarioId, selected.scenarioVersion)
        if (scenario.status != "ACTIVE") throw ProducerContractException("publication requires active authorization context: SCENARIO#${scenario.id}#${selected.scenarioVersion} status=${scenario.status}")
        if (release.subjectAreaId != scenario.subjectAreaId || release.subjectAreaVersion != scenario.subjectAreaVersion)
            throw ProducerContractException("frozen scenario belongs to another exact subject area")
        val metricRefs = scenarios.listScenarioMetricRefs(scenario.id, selected.scenarioVersion)
        val relations = scenarios.relationFilters(scenario.id, selected.scenarioVersion)
        scenarios.validateRelationFilters(scenario, metricRefs, relations)
        val objects = frozenObjects.asSequence().filter { it.releaseId == release.releaseId && it.releaseVersion == release.releaseVersion }
            .associateBy { Triple(it.objectType, it.objectId, it.objectVersion) }
        fun objectRef(kind: String, id: String, version: Long): ReleaseObjectRefRecord = objects[Triple(kind, id, version)]
            ?: throw ProducerContractException("scenario selection requires an already frozen exact object: $kind#$id@$version")
        val adopted = adoptedMetrics.associateBy { it.id to it.subjectAreaVersion }
        val dimensions = adoptedDimensions.associateBy { it.analysisDimensionId to it.analysisDimensionVersion }
        val coordinate = QueryScenarioRef(scenario.id, selected.scenarioVersion, scenario.displayName, release.releaseId, release.releaseVersion, release.releaseDisplayName)
        val selection = ReleaseScenarioSnapshot(ReleaseScenarioSnapshot.FORMAT_VERSION, coordinate,
            SubjectAreaCapabilityContext(release.subjectAreaId, release.subjectAreaVersion, selected.id, selected.status),
            metricRefs.map { ref ->
                val adoption = adopted[ref.adoptionId to ref.adoptionVersion] ?: throw ProducerContractException("scenario adoption is outside its exact subject area")
                ScenarioMetricReference(ref.adoptionId, ref.adoptionVersion, adoption.status, ref.id, objectRef("METRIC_ADOPTION", ref.adoptionId, ref.adoptionVersion).id, ref.requiredByDefault, ref.sortOrder)
            },
            scenarios.memberSourceRefs(scenario.id, selected.scenarioVersion).map { ReleaseScenarioSnapshot.MemberGrant(it.sourceId, it.sourceVersion, it.permission) },
            scenarios.listScenarioAnalysisDimensionRefs(scenario.id, selected.scenarioVersion).map { ref ->
                val adoption = dimensions[ref.analysisDimensionId to ref.analysisDimensionVersion] ?: throw ProducerContractException("scenario dimension is outside its exact subject area")
                dimension(ref, adoption, objectRef("ANALYSIS_DIMENSION", ref.analysisDimensionId, ref.analysisDimensionVersion))
            }, scenarios.listScenarioFilterRefs(scenario.id, selected.scenarioVersion).map(::filter),
            scenarios.listScenarioPipelineRules(scenario.id, selected.scenarioVersion).map(::stage))
        val issues = diagnostics.findReleaseDiagnostics(release.releaseId, release.releaseVersion, scenario.id, selected.scenarioVersion)
        return FrozenScenario(scenario, selection, relations, issues)
    }
    private fun dimension(ref: ScenarioAnalysisDimensionRefRecord, adopted: SubjectAreaAnalysisDimensionRefRecord, frozen: ReleaseObjectRefRecord): ReleaseScenarioSnapshot.DimensionGrant {
        val levelCode = ref.defaultLevelCode ?: throw ProducerContractException("scenario default level is required")
        val level = analysisDimensions.findAnalysisDimensionLevel(ref.analysisDimensionId, ref.analysisDimensionVersion, levelCode)
            ?: throw ProducerContractException("scenario default level is missing")
        return ReleaseScenarioSnapshot.DimensionGrant(frozen.objectId, ref.analysisDimensionVersion, frozen.objectDisplayName, frozen.objectCode, adopted.id, adopted.status,
            ref.id, frozen.id, levelCode, level.displayName, ref.requiredByDefault, ref.sortOrder)
    }
    private fun filter(ref: ScenarioFilterRefRecord): ReleaseScenarioSnapshot.FilterGrant {
        val provider = ref.valueProviderId?.let { analysisDimensions.findAnalysisDimensionValueProvider(it) ?: throw ProducerContractException("selected exact value provider is missing") }
        if (provider != null && (provider.analysisDimensionId != ref.targetId || provider.analysisDimensionVersion != ref.targetVersion))
            throw ProducerContractException("selected value provider differs from its exact dimension")
        return ReleaseScenarioSnapshot.FilterGrant(ref.id, ref.filterTargetType, ref.targetId, ref.targetVersion, ref.operators, ref.valueProviderId,
            provider?.providerType, provider?.sourceLogicUnitId, provider?.sourceLogicUnitVersion, ref.requiredByDefault, ref.sortOrder)
    }
    private fun stage(rule: ScenarioPipelineRuleRecord): PostProcessCapability {
        if (rule.status != "ACTIVE") throw ProducerContractException("frozen scenario stage must be approved in its exact subject area")
        return PostProcessCapability(rule.id, rule.ruleType, rule.displayName, rule.authorization, rule.sortOrder, null, null, null, null, null, true, false, false, false)
    }
    class FrozenScenario(val definition: ScenarioRecord, val selection: ReleaseScenarioSnapshot, relationFilters: List<ScenarioRelationFilterRecord>, diagnostics: List<ReleaseDiagnosticIssue>) {
        val relationFilters: List<ScenarioRelationFilterRecord> = java.util.List.copyOf(relationFilters)
        val diagnostics: List<ReleaseDiagnosticIssue> = java.util.List.copyOf(diagnostics)
        override fun equals(other: Any?): Boolean = other is FrozenScenario && definition == other.definition && selection == other.selection && relationFilters == other.relationFilters && diagnostics == other.diagnostics
        override fun hashCode(): Int = listOf(definition, selection, relationFilters, diagnostics).hashCode()
    }
}
