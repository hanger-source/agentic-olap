package com.olap.prototype.management.governance

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationFilter
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.contract.StageAuthorization
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.contract.FilterLeafOperatorPolicy
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.PublishedRelationFilter
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** Scenario grants are owned by an exact scenario; changing them requires the parent draft lock. */
class ScenarioGovernanceService(private val database: PlatformDatabase, private val subjectAreas: SubjectAreaGovernanceService,
    private val adoptions: SubjectAreaAdoptionService, private val analysisDimensions: AnalysisDimensionGovernanceService,
    private val logicalModel: LogicalModelGovernanceService, private val metrics: MetricGovernanceService) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val repository = ScenarioGovernanceRepository(database)
    fun scenarioSnapshot(id: String, version: Long): ScenarioRecord = snapshots.scenario(id, version)
    fun createScenario(request: ScenarioCreateRequest): ScenarioCreateResult = database.inTransaction { create(request, null) }
    fun reviseScenario(request: GovernanceRevisionRequest<ScenarioCreateRequest>): ScenarioCreateResult = database.inTransaction { create(request.definition, request) }
    private fun create(request: ScenarioCreateRequest, revision: GovernanceRevisionRequest<ScenarioCreateRequest>?): ScenarioCreateResult {
        val subject = text(request.subjectAreaId, "scenario.subject_area_id")
        val subjectVersion = positive(request.subjectAreaVersion, "scenario.subject_area_version")
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, subject, subjectVersion)
        subjectAreas.requireSubjectAreaVersion(subject, subjectVersion, "scenario.subject_area_version")
        val code = domain(request.code, "scenario.code")
        val id = revision?.objectId ?: "$subject.$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.SCENARIO, id, revision, request.createdBy)
        val selected = revision?.let {
            val source = scenarioSnapshot(id, requireNotNull(draft.sourceVersion))
            matching(subject, source.subjectAreaId, "scenario.subject_area_id", "source subject area identity")
            draft.sourceVersion.takeIf { source.subjectAreaVersion == subjectVersion }
        }
        val actor = text(request.createdBy, "scenario.created_by")
        val now = Instant.now()
        val scenario = ScenarioRecord(id, subject, subjectVersion, code, text(request.displayName, "scenario.display_name"), request.description, text(request.owner, "scenario.owner"), "DRAFT", draft.version, actor, now, actor, now)
        val version = ScenarioVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", encodeDefinition(ScenarioSnapshots.scenarioVersionContent(scenario)), "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        val ref = SubjectAreaScenarioRefRecord("$subject:v$subjectVersion:scenario:$id:v${draft.version}", subject, subjectVersion, id, draft.version, "DRAFT", actor, now)
        saveScenario(scenario); saveScenarioVersion(version); saveSubjectAreaScenarioRef(ref, selected)
        return ScenarioCreateResult(scenario, version, ref)
    }
    internal fun saveScenario(record: ScenarioRecord) {
        subjectAreas.requireSubjectAreaVersion(record.subjectAreaId, record.subjectAreaVersion, "scenario.subject_area_version")
        repository.saveScenario(record)
    }
    internal fun saveScenarioVersion(record: ScenarioVersionRecord) {
        if (!repository.scenarioIdentityExists(record.scenarioId)) throw ProducerContractException("scenario_version.scenario_id not found: ${record.scenarioId}")
        repository.saveScenarioVersion(record)
    }
    internal fun saveSubjectAreaScenarioRef(record: SubjectAreaScenarioRefRecord, sourceVersion: Long?) {
        val scenario = requireScenarioVersion(record.scenarioId, record.scenarioVersion, "subject_area_scenario_ref.scenario_version")
        matching(record.subjectAreaId, scenario.subjectAreaId, "subject_area_scenario_ref.subject_area_id", "scenario.subject_area_id")
        matching(record.subjectAreaVersion, scenario.subjectAreaVersion, "subject_area_scenario_ref.subject_area_version", "scenario.subject_area_version")
        subjectAreas.persistSubjectAreaScenarioRef(record, sourceVersion)
    }
    fun findScenario(id: String): ScenarioRecord? = repository.findScenario(id)
    fun listScenarios(id: String): List<ScenarioRecord> = repository.listScenarios(id)
    fun requireScenarioVersion(id: String, version: Long?, field: String): ScenarioRecord = scenarioSnapshot(text(id, "$field.scenario_id"), positive(version, field))
    private fun editable(id: String, version: Long) {
        val scenario = scenarioSnapshot(id, version)
        val drafts = GovernanceDraftVersions(database)
        // 场景授权与成员采用修订共享所属聚合锁，不能在修订校验之后提交基于旧成员的权限。
        drafts.lock(GovernanceDraftVersions.Asset.SUBJECT_AREA, scenario.subjectAreaId)
        drafts.requireEditable(GovernanceDraftVersions.Asset.SCENARIO, id, version)
    }

    fun createScenarioMemberSourceRef(request: ScenarioMemberSourceRefCreateRequest): ScenarioMemberSourceRefRecord = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val scenario = scenarioSnapshot(request.scenarioId, request.scenarioVersion)
        val source = adoptions.memberSource(request.sourceId, request.sourceVersion)
        requireMemberSourcePermission(scenario, source, request.permission)
        ScenarioMemberSourceRefRecord(request.scenarioId, request.scenarioVersion, request.sourceId, request.sourceVersion, request.permission).also(repository::saveMemberSourceRef)
    }
    fun validateMemberSourceRefs(scenario: ScenarioRecord) {
        memberSourceRefs(scenario.id, scenario.currentVersion).forEach { reference ->
            requireMemberSourcePermission(scenario, adoptions.memberSource(reference.sourceId, reference.sourceVersion), reference.permission)
        }
    }
    private fun requireMemberSourcePermission(scenario: ScenarioRecord, source: MemberSourceRecord, permission: MemberQueryPermission) {
        if (source.subjectAreaId != scenario.subjectAreaId || source.subjectAreaVersion != scenario.subjectAreaVersion) throw ProducerContractException("scenario member source belongs to another subject-area version")
        val result = source.definition.result
        if (!result.outputs.keys.containsAll(permission.outputs) || !result.outputs.keys.containsAll(permission.values) || !result.filterOperators.keys.containsAll(permission.filters.keys) ||
            !result.clockSlots.keys.containsAll(permission.clocks) || permission.timeRange && result.timeSlot == null) throw ProducerContractException("scenario member permission exceeds its registered projection, filters or clocks")
        permission.filters.forEach { (slot, operators) -> if (!requireNotNull(result.filterOperators[slot]).containsAll(operators)) throw ProducerContractException("scenario member operators exceed the registered source permission") }
        permission.values.forEach { if (result.outputs[it] !is MemberSourceDefinition.ScalarOutput) throw ProducerContractException("candidate values require a scalar member output; expand a container before scalar consumption") }
    }
    fun createScenarioRelationFilter(request: ScenarioRelationFilterCreateRequest): ScenarioRelationFilterRecord = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val scenario = scenarioSnapshot(request.scenarioId, request.scenarioVersion)
        val code = ScopedInputContract.requireId(request.code)
        val id = "${request.scenarioId}:v${request.scenarioVersion}:relation:$code"
        val parent = EventRef(request.eventId, request.eventVersion)
        val relation = RelationFilter(id, request.scenarioVersion, parent, request.relationshipRole, request.memberSourceId, request.memberSourceVersion,
            adoptions.relationClockSlots(id, parent, request.relationshipRole, request.memberSourceId, request.memberSourceVersion))
        val definition = PublishedRelationFilter(relation, request.scenarioId, request.displayName, request.permission,
            requireNotNull(request.requiredByDefault) { "relation default requirement must be explicit" }, requireNotNull(request.sortOrder) { "relation order must be explicit" })
        adoptions.requireRelationFilter(scenario.subjectAreaId, scenario.subjectAreaVersion, definition)
        ScenarioRelationFilterRecord(request.scenarioId, request.scenarioVersion, code, definition).also(repository::saveRelationFilter)
    }
    fun relationFilters(id: String, version: Long): List<ScenarioRelationFilterRecord> = repository.relationFilters(id, version)
    fun memberSourceRefs(id: String, version: Long): List<ScenarioMemberSourceRefRecord> = repository.memberSourceRefs(id, version)
    fun requireRelationFilterVersion(id: String, version: Long?, field: String): ScenarioRelationFilterRecord {
        val filter = repository.findRelationFilter(text(id, field), positive(version, field)) ?: throw ProducerContractException("$field not found: $id@$version")
        requireScenarioVersion(filter.scenarioId, filter.scenarioVersion, field)
        return filter
    }
    fun validateRelationFilters(scenario: ScenarioRecord, selectedMetrics: List<ScenarioMetricRefRecord>, selectedFilters: List<ScenarioRelationFilterRecord>) {
        selectedMetrics.forEach { if (it.scenarioId != scenario.id || it.scenarioVersion != scenario.currentVersion) throw ProducerContractException("selected metric belongs to another exact scenario") }
        selectedFilters.forEach { if (it.scenarioId != scenario.id || it.scenarioVersion != scenario.currentVersion) throw ProducerContractException("selected relation filter belongs to another exact scenario") }
        val events = selectedMetrics.map { adoptions.metricAdoption(it.adoptionId, it.adoptionVersion) }.mapNotNull {
            (metrics.metricSnapshot(it.definition.id, it.definition.version.toLong()).definition as? MetricDefinition.FactMeasure)?.event
        }.toSet()
        selectedFilters.forEach {
            adoptions.requireRelationFilter(scenario.subjectAreaId, scenario.subjectAreaVersion, it.definition)
            if (it.definition.relation.parent !in events) throw ProducerContractException("published relation filter needs a direct fact invocation of its exact parent event in this scenario")
        }
    }

    fun createScenarioMetricRef(request: ScenarioMetricRefCreateRequest): ScenarioMetricRefCreateResult = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val id = text(request.scenarioId, "scenario_metric_ref.scenario_id")
        val version = positive(request.scenarioVersion, "scenario_metric_ref.scenario_version")
        val adoption = text(request.adoptionId, "scenario_metric_ref.adoption_id")
        val adoptionVersion = positive(request.adoptionVersion, "scenario_metric_ref.adoption_version")
        val record = ScenarioMetricRefRecord("$id:v$version:adoption:$adoption:v$adoptionVersion", id, version, adoption, adoptionVersion,
            flag(request.requiredByDefault, "scenario_metric_ref.required_by_default"), order(request.sortOrder, "scenario_metric_ref.sort_order"), Instant.now())
        saveScenarioMetricRef(record)
        ScenarioMetricRefCreateResult(record)
    }
    internal fun saveScenarioMetricRef(record: ScenarioMetricRefRecord) {
        val scenario = requireScenarioVersion(record.scenarioId, record.scenarioVersion, "scenario_metric_ref.scenario_version")
        val adoption = adoptions.metricAdoption(record.adoptionId, record.adoptionVersion)
        if (adoption.subjectAreaId != scenario.subjectAreaId || adoption.subjectAreaVersion != scenario.subjectAreaVersion) throw ProducerContractException("scenario adoption belongs to another subject-area version")
        repository.saveScenarioMetricRef(record)
    }
    fun listScenarioMetricRefs(id: String, version: Long): List<ScenarioMetricRefRecord> = repository.listScenarioMetricRefs(id, version)
    fun createScenarioAnalysisDimensionRef(request: ScenarioAnalysisDimensionRefCreateRequest): ScenarioAnalysisDimensionRefCreateResult = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val id = text(request.scenarioId, "scenario_analysis_dimension_ref.scenario_id")
        val version = positive(request.scenarioVersion, "scenario_analysis_dimension_ref.scenario_version")
        val dimension = text(request.analysisDimensionId, "scenario_analysis_dimension_ref.analysis_dimension_id")
        val dimensionVersion = positive(request.analysisDimensionVersion, "scenario_analysis_dimension_ref.analysis_dimension_version")
        val level = request.defaultLevelCode?.takeUnless { it.isBlank() }?.let { domain(it, "scenario_analysis_dimension_ref.default_level_code") }
        val ref = ScenarioAnalysisDimensionRefRecord("$id:v$version:analysis-dimension:$dimension:v$dimensionVersion", id, version, dimension, dimensionVersion, level,
            flag(request.requiredByDefault, "scenario_analysis_dimension_ref.required_by_default"), order(request.sortOrder, "scenario_analysis_dimension_ref.sort_order"), Instant.now())
        saveScenarioAnalysisDimensionRef(ref)
        ScenarioAnalysisDimensionRefCreateResult(ref)
    }
    internal fun saveScenarioAnalysisDimensionRef(record: ScenarioAnalysisDimensionRefRecord) {
        val scenario = requireScenarioVersion(record.scenarioId, record.scenarioVersion, "scenario_analysis_dimension_ref.scenario_version")
        val dimension = analysisDimensions.requireAnalysisDimensionVersion(record.analysisDimensionId, record.analysisDimensionVersion, "scenario_analysis_dimension_ref.analysis_dimension_version")
        matching(scenario.subjectAreaId, dimension.subjectAreaId, "scenario.subject_area_id", "analysis_dimension.subject_area_id")
        matching(scenario.subjectAreaVersion, dimension.subjectAreaVersion, "scenario.subject_area_version", "analysis_dimension.subject_area_version")
        if (analysisDimensions.listSubjectAreaAnalysisDimensionRefs(scenario.subjectAreaId, scenario.subjectAreaVersion).none { it.analysisDimensionId == record.analysisDimensionId && it.analysisDimensionVersion == record.analysisDimensionVersion })
            throw ProducerContractException("scenario_analysis_dimension_ref.analysis_dimension_version must be opened by subject_area_version: ${scenario.subjectAreaId}#${scenario.subjectAreaVersion} -> ${record.analysisDimensionId}#${record.analysisDimensionVersion}")
        record.defaultLevelCode?.takeUnless { it.isBlank() }?.let { analysisDimensions.requireAnalysisDimensionLevel(record.analysisDimensionId, record.analysisDimensionVersion, it, "scenario_analysis_dimension_ref.default_level_code") }
        repository.saveScenarioAnalysisDimensionRef(record)
    }
    fun listScenarioAnalysisDimensionRefs(id: String, version: Long): List<ScenarioAnalysisDimensionRefRecord> = repository.listScenarioAnalysisDimensionRefs(id, version)
    private fun openedDimension(id: String, version: Long, dimension: String, dimensionVersion: Long, field: String): ScenarioAnalysisDimensionRefRecord =
        listScenarioAnalysisDimensionRefs(id, version).firstOrNull { it.analysisDimensionId == dimension && it.analysisDimensionVersion == dimensionVersion }
            ?: throw ProducerContractException("$field must be opened by scenario_version: $id#$version -> $dimension#$dimensionVersion")

    fun createScenarioFilterRef(request: ScenarioFilterRefCreateRequest): ScenarioFilterRefCreateResult = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val id = text(request.scenarioId, "scenario_filter_ref.scenario_id")
        val version = positive(request.scenarioVersion, "scenario_filter_ref.scenario_version")
        val type = text(request.filterTargetType, "scenario_filter_ref.filter_target_type")
        if (type !in ScenarioFilterRefCreateRequest.TARGET_TYPES) throw ProducerContractException("scenario_filter_ref.filter_target_type must be ${ScenarioFilterRefCreateRequest.TARGET_TYPES.joinToString(", ")}: $type")
        val target = text(request.targetId, "scenario_filter_ref.target_id")
        val targetVersion = positive(request.targetVersion, "scenario_filter_ref.target_version")
        request.valueProviderId?.let { text(it, "scenario_filter_ref.value_provider_id") }
        if (request.operators.isEmpty()) throw ProducerContractException("scenario_filter_ref.operators must be an explicit non-empty operator list")
        val policy = try { FilterLeafOperatorPolicy.requireOperatorSet(request.operators.map { it.name }, "scenario_filter_ref.operators").toList() }
            catch (invalid: IllegalArgumentException) { throw ProducerContractException(requireNotNull(invalid.message), invalid) }
        val ref = ScenarioFilterRefRecord("$id:v$version:filter:$type:$target:v$targetVersion", id, version, type, target, targetVersion, policy, request.valueProviderId,
            flag(request.requiredByDefault, "scenario_filter_ref.required_by_default"), order(request.sortOrder, "scenario_filter_ref.sort_order"), Instant.now())
        saveScenarioFilterRef(ref)
        ScenarioFilterRefCreateResult(ref)
    }
    internal fun saveScenarioFilterRef(record: ScenarioFilterRefRecord) {
        val scenario = requireScenarioVersion(record.scenarioId, record.scenarioVersion, "scenario_filter_ref.scenario_version")
        try { FilterLeafOperatorPolicy.requireOperatorSet(record.operators, "scenario_filter_ref.operators") }
        catch (invalid: IllegalArgumentException) { throw ProducerContractException("${invalid.message} in ${record.id}", invalid) }
        if (record.filterTargetType != "ANALYSIS_DIMENSION") throw ProducerContractException("scenario_filter_ref.filter_target_type unsupported: ${record.filterTargetType} in ${scenario.id}")
        val opened = openedDimension(record.scenarioId, record.scenarioVersion, record.targetId, record.targetVersion, "scenario_filter_ref.target_version")
        record.valueProviderId?.let { id ->
            val provider = analysisDimensions.findAnalysisDimensionValueProvider(id) ?: throw ProducerContractException("scenario_filter_ref.value_provider_id not found: $id")
            matching(record.targetId, provider.analysisDimensionId, "scenario_filter_ref.target_id", "analysis_dimension_value_provider.analysis_dimension_id")
            matching(record.targetVersion, provider.analysisDimensionVersion, "scenario_filter_ref.target_version", "analysis_dimension_value_provider.analysis_dimension_version")
            if (provider.providerType != "LOGIC_UNIT_DISTINCT") throw ProducerContractException("scenario filter candidate provider type unsupported: ${provider.providerType}")
            if (opened.defaultLevelCode.isNullOrBlank()) throw ProducerContractException("scenario_filter_ref.value_provider_id requires scenario_analysis_dimension_ref.default_level_code: ${record.id}")
            matching(provider.levelCode, opened.defaultLevelCode, "analysis_dimension_value_provider.level_code", "scenario_analysis_dimension_ref.default_level_code")
        }
        repository.saveScenarioFilterRef(record)
    }
    fun listScenarioFilterRefs(id: String, version: Long): List<ScenarioFilterRefRecord> = repository.listScenarioFilterRefs(id, version)

    private fun stageCandidates(): List<ScenarioPipelineRuleCandidate> = PostOperationContract.operationTypes().map { ScenarioPipelineRuleCandidate(it, PostOperationContract.displayName(it), StageAuthorization.current(it), true, null) }
    fun createScenarioPipelineRule(request: ScenarioPipelineRuleCreateRequest): ScenarioPipelineRuleCreateResult = database.inTransaction {
        editable(request.scenarioId, request.scenarioVersion)
        val id = text(request.scenarioId, "scenario_pipeline_rule.scenario_id")
        val version = positive(request.scenarioVersion, "scenario_pipeline_rule.scenario_version")
        val type = ScenarioPipelineRulePolicy.requireRuleType(request.ruleType, "scenario_pipeline_rule.rule_type")
        val candidate = stageCandidates().firstOrNull { it.ruleType == type } ?: throw ProducerContractException("scenario_pipeline_rule candidate not found: $type")
        val rule = ScenarioPipelineRuleRecord("$id:v$version:pipeline:$type", id, version, type, candidate.displayName, candidate.authorization, "DRAFT", order(request.sortOrder, "scenario_pipeline_rule.sort_order"), Instant.now())
        saveScenarioPipelineRule(rule)
        ScenarioPipelineRuleCreateResult(rule)
    }
    internal fun saveScenarioPipelineRule(record: ScenarioPipelineRuleRecord) {
        requireScenarioVersion(record.scenarioId, record.scenarioVersion, "scenario_pipeline_rule.scenario_version")
        ScenarioPipelineRulePolicy.requireScenarioPipelineRule(record)
        repository.saveScenarioPipelineRule(record)
    }
    fun listScenarioPipelineRules(id: String, version: Long): List<ScenarioPipelineRuleRecord> = repository.listScenarioPipelineRules(id, version)
    fun refreshScenarioPipelineRuleContract(request: ScenarioPipelineRuleContractRefreshRequest): ScenarioPipelineRuleRecord = database.inTransaction {
        val id = text(request.scenarioId, "scenario_pipeline_rule.scenario_id")
        val version = positive(request.scenarioVersion, "scenario_pipeline_rule.scenario_version")
        editable(id, version)
        val type = ScenarioPipelineRulePolicy.requireRuleType(request.ruleType, "scenario_pipeline_rule.rule_type")
        val existing = listScenarioPipelineRules(id, version).filter { it.ruleType == type }
        if (existing.size != 1) throw ProducerContractException("contract refresh requires exactly one existing scenario stage: $type")
        val candidate = stageCandidates().first { it.ruleType == type }
        require(request.approvedAuthorization == candidate.authorization) { "approved stage authorization must match the deployed explicit language candidate: $type" }
        existing.single().copy(authorization = candidate.authorization).also(repository::updateScenarioPipelineRuleContract)
    }

    fun scenarioConfigurationCandidates(id: String, version: Long): ScenarioConfigurationCandidatesView {
        val scenario = requireScenarioVersion(id, version, "scenario_configuration_candidates.scenario_version")
        val metricRefs = listScenarioMetricRefs(id, version)
        val dimensionRefs = listScenarioAnalysisDimensionRefs(id, version)
        val filters = listScenarioFilterRefs(id, version)
        val stages = listScenarioPipelineRules(id, version)
        val openedMetrics = metricRefs.mapTo(hashSetOf()) { key(it.adoptionId, it.adoptionVersion, "scenario_metric_ref.adoption_version") }
        val openedDimensions = dimensionRefs.mapTo(hashSetOf()) { key(it.analysisDimensionId, it.analysisDimensionVersion, "scenario_analysis_dimension_ref.analysis_dimension_version") }
        val openedFilters = filters.mapTo(hashSetOf()) { key(it.targetId, it.targetVersion, "scenario_filter_ref.target_version") }
        val openedStages = stages.mapTo(hashSetOf()) { ScenarioPipelineRulePolicy.requireRuleType(it.ruleType, "scenario_pipeline_rule.rule_type") }
        val metricCandidates = adoptions.listSubjectAreaMetricRefs(scenario.subjectAreaId, scenario.subjectAreaVersion).filter { key(it.id, it.subjectAreaVersion, "subject_area_metric_ref.adoption_version") !in openedMetrics }.map { ref ->
            metrics.requireMetricVersion(ref.metricId, ref.metricVersion, "scenario_configuration_candidates.metric_version")
            val metric = metrics.metricSnapshot(ref.metricId, ref.metricVersion)
            val status = text(metric.status, "scenario_configuration_candidates.metric_status")
            ScenarioMetricCandidate(ref.id, ref.subjectAreaVersion, ref.code, ref.displayName, ref.businessMeaning, MetricRef(ref.metricId, ref.metricVersion.toString()), status, statusName(status), true, null)
        }
        val dimensions = analysisDimensions.listSubjectAreaAnalysisDimensionRefs(scenario.subjectAreaId, scenario.subjectAreaVersion)
            .filter { key(it.analysisDimensionId, it.analysisDimensionVersion, "subject_area_analysis_dimension_ref.analysis_dimension_version") !in openedDimensions }
            .map { dimensionCandidate(it.analysisDimensionId, it.analysisDimensionVersion) }
        val filterCandidates = dimensionRefs.filter { key(it.analysisDimensionId, it.analysisDimensionVersion, "scenario_analysis_dimension_ref.analysis_dimension_version") !in openedFilters }
            .map { filterCandidate(it.analysisDimensionId, it.analysisDimensionVersion, it.defaultLevelCode) }
        return ScenarioConfigurationCandidatesView(metricCandidates, dimensions, filterCandidates, stageCandidates().filter { it.ruleType !in openedStages }, metricRefs.size, dimensionRefs.size, filters.size, stages.size)
    }
    private fun dimensionCandidate(id: String, version: Long): ScenarioAnalysisDimensionCandidate {
        val dimension = analysisDimensions.requireAnalysisDimensionVersion(id, version, "scenario_configuration_candidates.analysis_dimension_version")
        val level = analysisDimensions.listAnalysisDimensionLevels(id, version).firstOrNull { it.status in setOf("DRAFT", "ACTIVE") }?.levelCode
            ?: throw ProducerContractException("scenario_configuration_candidates.analysis_dimension_level not found: $id#$version")
        val status = text(dimension.status, "scenario_configuration_candidates.analysis_dimension_status")
        return ScenarioAnalysisDimensionCandidate(text(dimension.id, "scenario_configuration_candidates.analysis_dimension_id"), positive(version, "scenario_configuration_candidates.analysis_dimension_version"),
            text(dimension.displayName, "scenario_configuration_candidates.analysis_dimension_display_name"), status, statusName(status), domain(level, "scenario_configuration_candidates.analysis_dimension_default_level_code"), true, null)
    }
    private fun filterCandidate(id: String, version: Long, defaultLevel: String?): ScenarioFilterCandidate {
        val dimension = analysisDimensions.requireAnalysisDimensionVersion(id, version, "scenario_configuration_candidates.filter_analysis_dimension_version")
        val level = domain(defaultLevel, "scenario_configuration_candidates.filter_default_level_code")
        val providers = analysisDimensions.listAnalysisDimensionValueProviders(id, version).filter { it.levelCode == level }.map { providerCandidate(it, level) }
        val status = text(dimension.status, "scenario_configuration_candidates.filter_analysis_dimension_status")
        val disabled = if (providers.isEmpty()) "当前分析维度默认层级没有候选值来源" else null
        return ScenarioFilterCandidate(text(dimension.id, "scenario_configuration_candidates.filter_analysis_dimension_id"), positive(version, "scenario_configuration_candidates.filter_analysis_dimension_version"),
            text(dimension.displayName, "scenario_configuration_candidates.filter_analysis_dimension_display_name"), status, statusName(status), level, providers, disabled == null, disabled)
    }
    private fun providerCandidate(provider: AnalysisDimensionValueProviderRecord, level: String): ScenarioFilterValueProviderCandidate {
        val type = text(provider.providerType, "scenario_configuration_candidates.value_provider_type")
        val unit = text(provider.sourceLogicUnitId, "scenario_configuration_candidates.value_provider_source_logic_unit_id")
        val version = positive(provider.sourceLogicUnitVersion, "scenario_configuration_candidates.value_provider_source_logic_unit_version")
        logicalModel.requireLogicUnitVersion(unit, version, "scenario_configuration_candidates.value_provider_source_logic_unit_version")
        val logical = logicalModel.findLogicUnit(unit) ?: throw ProducerContractException("scenario_configuration_candidates.value_provider_source_logic_unit_id not found: $unit")
        val status = text(provider.status, "scenario_configuration_candidates.value_provider_status")
        val display = when (text(type, "relation.role")) { "DISTINCT_VALUES" -> "枚举候选值"; "MASTER_DATA" -> "主数据候选值"; else -> throw ProducerContractException("Unsupported relation role display name: $type") }
        return ScenarioFilterValueProviderCandidate(text(provider.id, "scenario_configuration_candidates.value_provider_id"), domain(level, "scenario_configuration_candidates.value_provider_level_code"), type, display,
            unit, version, text(logical.displayName, "scenario_configuration_candidates.value_provider_source_logic_unit_display_name"), status, statusName(status), true, null)
    }
    private fun key(id: String, version: Long, field: String): String = "${text(id, "$field.id")}#${positive(version, field)}"
    private fun statusName(status: String): String = when (text(status, "governance.status")) { "DRAFT" -> "草稿"; "ACTIVE" -> "已启用"; "DEPRECATED" -> "已废弃"; "ARCHIVED" -> "已归档"; else -> throw ProducerContractException("Unsupported governance status display name: $status") }
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun domain(value: String?, field: String): String = text(value, field).also { if (!it.matches(Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"))) throw ProducerContractException("$field must use lower-case letters, numbers, underscore, dash, or dot") }
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun flag(value: Boolean?, field: String): Boolean = value ?: throw ProducerContractException("$field is required")
    private fun order(value: Int?, field: String): Int = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field must be positive")
    private fun matching(actual: Any?, expected: Any?, actualField: String, expectedField: String) { if (actual == null || actual != expected) throw ProducerContractException("$actualField must match $expectedField") }
    private fun encodeDefinition(value: GovernanceDefinition): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(value))
}
