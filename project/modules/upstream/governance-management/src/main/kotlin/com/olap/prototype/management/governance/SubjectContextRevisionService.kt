package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.contract.FilterLeafOperator
import com.olap.prototype.query.contract.FilterLeafOperatorPolicy
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** 一个事务内延续显式选择的主题上下文，复用共享定义，经原有作者服务重新核验引用。 */
class SubjectContextRevisionService(private val database: PlatformDatabase,
    private val subjects: SubjectAreaGovernanceService, private val analysis: AnalysisDimensionGovernanceService,
    private val adoptions: SubjectAreaAdoptionService, private val scenarios: ScenarioGovernanceService) {

    fun selection(request: SubjectContextSelectionRequest): SubjectContextSelectionView = database.inReadSnapshot {
        subjects.subjectAreaSnapshot(request.objectId, request.sourceVersion)
        SubjectContextSelectionView(request.objectId, request.sourceVersion, SubjectContextSelection(
            adoptions.listSubjectAreaMetricRefs(request.objectId, request.sourceVersion).map { it.id },
            adoptions.memberSources(request.objectId, request.sourceVersion).map { it.id },
            analysis.listSubjectAreaAnalysisDimensionRefs(request.objectId, request.sourceVersion).map { it.analysisDimensionId },
            subjects.listSubjectAreaScenarioRefs(request.objectId, request.sourceVersion).map { it.scenarioId }))
    }

    fun revise(request: SubjectContextRevisionRequest): SubjectContextRevisionResult = database.inTransaction {
        GovernanceDraftVersions(database).lock(GovernanceDraftVersions.Asset.SUBJECT_AREA, request.objectId)
        val source = subjects.subjectAreaSnapshot(request.objectId, request.sourceVersion)
        val carriesChildren = listOf(request.carry.metrics, request.carry.members, request.carry.dimensions, request.carry.scenarios).any { it.isNotEmpty() }
        if (carriesChildren && (request.definition.code != source.code || request.definition.businessLineId != source.businessLineId))
            throw ProducerContractException("context revision preserves the subject identity and business line; a different root requires an explicit root revision")
        val target = subjects.reviseSubjectArea(GovernanceRevisionRequest(request.objectId, request.sourceVersion, request.definition))
        val session = Revision(request, target.subjectArea.currentVersion)
        session.carry()
        SubjectContextRevisionResult(target, session.carried)
    }

    private inner class Revision(private val request: SubjectContextRevisionRequest, private val targetVersion: Long) {
        private val id = request.objectId
        private val actor = request.definition.createdBy
        private val sourceVersion = request.sourceVersion
        val carried = mutableListOf<ContextRevisionReference>()
        private val selectedMetrics = select(request.carry.metrics, adoptions.listSubjectAreaMetricRefs(id, sourceVersion), { it.id })
        private val selectedMembers = select(request.carry.members, adoptions.memberSources(id, sourceVersion), { it.id })
        private val selectedDimensions = select(request.carry.dimensions, analysis.listSubjectAreaAnalysisDimensionRefs(id, sourceVersion), { it.analysisDimensionId })
        private val selectedScenarios = select(request.carry.scenarios, subjects.listSubjectAreaScenarioRefs(id, sourceVersion), { it.scenarioId })
        private val dimensions = linkedMapOf<Pair<String, Long>, Long>()
        private val mappings = linkedMapOf<String, String>()
        private val providers = linkedMapOf<String, String>()
        private val metrics = linkedMapOf<MetricInstanceRef, MetricInstanceRef>()
        private val members = linkedMapOf<MemberRef, MemberRef>()
        private val active = linkedSetOf<String>()

        fun carry() {
            selectedDimensions.values.forEach(::dimension)
            selectedMembers.values.forEach { member(MemberRef(it.id, it.subjectAreaVersion)) }
            selectedMetrics.values.forEach { metric(it.instance) }
            selectedScenarios.values.forEach(::scenario)
        }

        private fun dimension(ref: SubjectAreaAnalysisDimensionRefRecord) {
            val source = analysis.analysisDimensionSnapshot(ref.analysisDimensionId, ref.analysisDimensionVersion)
            val result = analysis.reviseForContext(source, targetVersion, actor)
            val version = result.analysisDimension.currentVersion
            dimensions[source.id to source.currentVersion] = version
            carried.add(ContextRevisionReference("ANALYSIS_DIMENSION", source.id, source.currentVersion, version))
            analysis.listAnalysisDimensionValueAlignments(source.id, source.currentVersion).forEach { alignment ->
                val next = "${source.id}:v$version:level:${alignment.levelCode}:alignment:${alignment.alignmentType}"
                analysis.saveAnalysisDimensionValueAlignment(alignment.copy(id = next, analysisDimensionVersion = version, createdAt = Instant.now()))
                mappings[alignment.id] = next
            }
            analysis.listAnalysisDimensionEventMappings(source.id, source.currentVersion).forEach { mapping ->
                val created = analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(source.id, version,
                    mapping.levelCode, mapping.bindingId, mapping.bindingVersion, mapping.alignmentStatus,
                    mapping.alignmentId?.let { requireSelected(mappings[it], "alignment", it) })).mapping
                mappings[mapping.id] = created.id
            }
            analysis.listAnalysisDimensionValueProviders(source.id, source.currentVersion).forEach { provider ->
                val prefix = "${source.id}:v${source.currentVersion}:"
                require(provider.id.startsWith(prefix)) { "value provider identity differs from its exact owner" }
                val next = "${source.id}:v$version:${provider.id.removePrefix(prefix)}"
                analysis.saveAnalysisDimensionValueProvider(provider.copy(id = next, analysisDimensionVersion = version, status = "DRAFT", createdBy = actor, createdAt = Instant.now()))
                providers[provider.id] = next
            }
        }

        private fun dimension(ref: ScopedInputContract.DimensionRef): ScopedInputContract.DimensionRef =
            ref.copy(version = requireSelected(dimensions[ref.id to ref.version.toLong()], "analysis dimension", "${ref.id}@${ref.version}").toString())

        private fun dimension(binding: CalculationBindings.Dimension): CalculationBindings.Dimension = CalculationBindings.Dimension(
            dimension(binding.reference), binding.role, binding.mappingIds.map { requireSelected(mappings[it], "event mapping", it) })

        private fun argument(value: Calculation.Value): Calculation.Value =
            if (value is Calculation.Grouping) Calculation.Grouping(value.dimensions.map(::dimension)) else value

        private fun arguments(values: Map<String, Calculation.Value>): Map<String, Calculation.Value> = values.mapValues { argument(it.value) }

        private fun binding(value: CalculationBindings.Member): CalculationBindings.Member = when (value) {
            is CalculationBindings.SourceCall -> CalculationBindings.SourceCall(member(value.source), value.arguments.mapValues { (_, supplied) ->
                if (supplied is Calculation.Value) argument(supplied) else supplied
            }, value.outputSlots, value.members.mapValues { binding(it.value) }, value.relations)
            is CalculationBindings.LocalRef, is CalculationBindings.InputParameter -> value
        }

        private fun bindings(value: CalculationBindings) = CalculationBindings(value.dimensions.mapValues { dimension(it.value) },
            value.members.mapValues { binding(it.value) }, value.groupingCandidates.mapValues { it.value.map(::dimension) },
            value.measurements.mapValues { metric(it.value) }, value.relations)

        private fun metric(ref: MetricInstanceRef): MetricInstanceRef {
            metrics[ref]?.let { return it }
            val source = requireSelected(selectedMetrics.values.singleOrNull { it.instance == ref }, "metric adoption", ref.id)
            return visiting("metric:${ref.id}") {
                val old = adoptions.metricAdoption(source.id, sourceVersion)
                val next = adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(id, targetVersion,
                    source.code, source.displayName, source.businessMeaning, source.metricId, source.metricVersion,
                    bindings(old.bindings), arguments(old.fixedArguments), actor)).subjectAreaMetricRef.instance
                metrics[ref] = next
                carried.add(ContextRevisionReference("METRIC_ADOPTION", source.id, sourceVersion, targetVersion))
                next
            }
        }

        private fun member(ref: MemberRef): MemberRef {
            members[ref]?.let { return it }
            val source = requireSelected(selectedMembers[ref.id]?.takeIf { it.subjectAreaVersion == ref.version }, "member source", "${ref.id}@${ref.version}")
            return visiting("member:${ref.id}") {
                val bound = bindings(source.bindings)
                val fixed = arguments(source.fixedArguments)
                val next = when (val origin = source.origin) {
                    is MemberDefinitionOrigin.Local -> adoptions.createMemberSource(MemberSourceCreateRequest(id, targetVersion, source.code,
                        source.displayName, source.definition, bound, fixed, actor))
                    is MemberDefinitionOrigin.Shared -> adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest(id, targetVersion, source.code,
                        source.displayName, origin.reference, bound, fixed, actor))
                }
                MemberRef(next.id, next.subjectAreaVersion).also {
                    members[ref] = it
                    carried.add(ContextRevisionReference("MEMBER_SOURCE", source.id, sourceVersion, targetVersion))
                }
            }
        }

        private fun scenario(ref: SubjectAreaScenarioRefRecord) {
            val source = scenarios.scenarioSnapshot(ref.scenarioId, ref.scenarioVersion)
            val next = scenarios.reviseScenario(GovernanceRevisionRequest(source.id, source.currentVersion,
                ScenarioCreateRequest(id, targetVersion, source.code, source.displayName, source.owner, source.description, actor))).scenario
            val version = next.currentVersion
            scenarios.listScenarioMetricRefs(source.id, source.currentVersion).forEach { selected ->
                val old = adoptions.metricAdoption(selected.adoptionId, selected.adoptionVersion)
                metric(old.instance)
                scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(next.id, version, selected.adoptionId, targetVersion, selected.requiredByDefault, selected.sortOrder))
            }
            scenarios.listScenarioAnalysisDimensionRefs(source.id, source.currentVersion).forEach { selected ->
                val target = requireSelected(dimensions[selected.analysisDimensionId to selected.analysisDimensionVersion], "analysis dimension", selected.analysisDimensionId)
                scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(next.id, version, selected.analysisDimensionId,
                    target, selected.defaultLevelCode, selected.requiredByDefault, selected.sortOrder))
            }
            scenarios.listScenarioFilterRefs(source.id, source.currentVersion).forEach { selected ->
                val target = requireSelected(dimensions[selected.targetId to selected.targetVersion], "filter dimension", selected.targetId)
                scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(next.id, version, selected.filterTargetType, selected.targetId, target,
                    selected.valueProviderId?.let { requireSelected(providers[it], "value provider", it) },
                    FilterLeafOperatorPolicy.requireOperatorSet(selected.operatorPolicyJson, "operators").map(FilterLeafOperator::valueOf), selected.requiredByDefault, selected.sortOrder))
            }
            scenarios.memberSourceRefs(source.id, source.currentVersion).forEach { selected ->
                val target = member(MemberRef(selected.sourceId, selected.sourceVersion))
                scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(next.id, version, target.id, target.version, selected.permission))
            }
            scenarios.relationFilters(source.id, source.currentVersion).forEach { selected ->
                val filter = selected.definition
                val relation = filter.relation
                val target = member(MemberRef(relation.memberSourceId, relation.memberSourceVersion))
                scenarios.createScenarioRelationFilter(ScenarioRelationFilterCreateRequest(next.id, version, selected.code, filter.displayName,
                    relation.parent.eventId, relation.parent.eventVersion, relation.relationshipRole, target.id, target.version,
                    filter.permission, filter.requiredByDefault, filter.sortOrder))
            }
            scenarios.listScenarioPipelineRules(source.id, source.currentVersion).forEach { rule ->
                scenarios.saveScenarioPipelineRule(rule.copy(id = "${next.id}:v$version:pipeline:${rule.ruleType}", scenarioVersion = version,
                    status = "DRAFT", createdAt = Instant.now()))
            }
            carried.add(ContextRevisionReference("SCENARIO", source.id, source.currentVersion, version))
        }

        private fun <T> visiting(key: String, action: () -> T): T {
            if (!active.add(key)) throw ProducerContractException("context adoption dependency cycle: $active -> $key")
            return try { action() } finally { active.remove(key) }
        }
    }

    private fun <T> select(ids: List<String>, available: List<T>, identity: (T) -> String): Map<String, T> = ids.associateWith { id ->
        available.singleOrNull { identity(it) == id } ?: throw ProducerContractException("context selection is absent or ambiguous in the exact source version: $id")
    }

    private fun <T : Any> requireSelected(value: T?, kind: String, reference: String): T = value
        ?: throw ProducerContractException("carried context requires a selected $kind: $reference; include its exact source selection or omit the dependent selection")
}
