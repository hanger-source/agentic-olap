package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.execute
import com.olap.prototype.management.governance.GovernanceJdbcSupport.insert
import com.olap.prototype.management.governance.GovernanceJdbcSupport.instant
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableBoolean
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableLong
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.PublishedRelationFilter
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.GovernanceProto
import java.sql.ResultSet

class ScenarioGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveScenario(record: ScenarioRecord) = identities.saveScenario(record)
    fun findScenario(id: String): ScenarioRecord? {
        requireText(id, "scenario.id")
        return working.find("scenario_version", "scenario_id", id)?.let { snapshots.scenario(it.objectId, it.version) }
    }
    fun listScenarios(subject: String): List<ScenarioRecord> {
        requireText(subject, "scenario.subject_area_id")
        return working.list("scenario_version", "scenario_id").map { snapshots.scenario(it.objectId, it.version) }.filter { it.subjectAreaId == subject }.sortedWith(compareBy(ScenarioRecord::subjectAreaVersion, ScenarioRecord::code))
    }
    fun scenarioIdentityExists(id: String): Boolean = identities.exists("scenario", id)
    fun scenarioVersionExists(id: String, version: Long): Boolean {
        requireText(id, "scenario_version.scenario_id"); requireVersion(version, "scenario_version.version")
        return storage("Failed to check scenario_version: $id#$version") { query(database, "SELECT 1 FROM scenario_version WHERE scenario_id=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
    }
    fun saveScenarioVersion(record: ScenarioVersionRecord) {
        GovernanceContract.requireVersionObject("scenario_version", "scenario_id", record.id, record.scenarioId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentProto, "scenario_version.content_proto")
        storage("Failed to save exact scenario_version") { insert(database, "scenario_version", linkedMapOf("id" to record.id, "scenario_id" to record.scenarioId, "version" to record.version,
            "version_status" to record.versionStatus, "content_proto" to record.contentProto, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion,
            "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt,
            "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
    }
    fun saveScenarioMetricRef(record: ScenarioMetricRefRecord) {
        owned(record.id, record.scenarioId, record.scenarioVersion, "scenario_metric_ref")
        requireText(record.adoptionId, "scenario_metric_ref.adoption_id"); requireVersion(record.adoptionVersion, "scenario_metric_ref.adoption_version")
        GovernanceContract.requireBoolean(record.requiredByDefault, "scenario_metric_ref.required_by_default"); GovernanceContract.requireInteger(record.sortOrder, "scenario_metric_ref.sort_order"); GovernanceContract.requireInstant(record.createdAt, "scenario_metric_ref.created_at")
        storage("Failed to save scenario_metric_ref: ${record.id}") { insert(database, "scenario_metric_ref", linkedMapOf("id" to record.id, "scenario_id" to record.scenarioId, "scenario_version" to record.scenarioVersion,
            "adoption_id" to record.adoptionId, "adoption_version" to record.adoptionVersion, "required_by_default" to record.requiredByDefault, "sort_order" to record.sortOrder, "created_at" to record.createdAt)) }
    }
    fun listScenarioMetricRefs(id: String, version: Long): List<ScenarioMetricRefRecord> = listOwned("scenario_metric_ref", id, version, "sort_order,adoption_id,adoption_version") {
        ScenarioMetricRefRecord(it.getString("id"), it.getString("scenario_id"), requireNotNull(nullableLong(it, "scenario_version")), it.getString("adoption_id"), requireNotNull(nullableLong(it, "adoption_version")), requireNotNull(nullableBoolean(it, "required_by_default")), it.getInt("sort_order"), requireNotNull(instant(it, "created_at")))
    }
    fun saveScenarioAnalysisDimensionRef(record: ScenarioAnalysisDimensionRefRecord) {
        owned(record.id, record.scenarioId, record.scenarioVersion, "scenario_analysis_dimension_ref")
        requireText(record.analysisDimensionId, "scenario_analysis_dimension_ref.analysis_dimension_id"); requireVersion(record.analysisDimensionVersion, "scenario_analysis_dimension_ref.analysis_dimension_version")
        GovernanceContract.requireBoolean(record.requiredByDefault, "scenario_analysis_dimension_ref.required_by_default"); GovernanceContract.requireInteger(record.sortOrder, "scenario_analysis_dimension_ref.sort_order"); GovernanceContract.requireInstant(record.createdAt, "scenario_analysis_dimension_ref.created_at")
        storage("Failed to save scenario_analysis_dimension_ref: ${record.id}") { insert(database, "scenario_analysis_dimension_ref", linkedMapOf("id" to record.id, "scenario_id" to record.scenarioId, "scenario_version" to record.scenarioVersion,
            "analysis_dimension_id" to record.analysisDimensionId, "analysis_dimension_version" to record.analysisDimensionVersion, "default_level_code" to record.defaultLevelCode, "required_by_default" to record.requiredByDefault, "sort_order" to record.sortOrder, "created_at" to record.createdAt)) }
    }
    fun listScenarioAnalysisDimensionRefs(id: String, version: Long): List<ScenarioAnalysisDimensionRefRecord> = listOwned("scenario_analysis_dimension_ref", id, version, "sort_order,analysis_dimension_id,analysis_dimension_version") {
        ScenarioAnalysisDimensionRefRecord(it.getString("id"), it.getString("scenario_id"), requireNotNull(nullableLong(it, "scenario_version")), it.getString("analysis_dimension_id"), requireNotNull(nullableLong(it, "analysis_dimension_version")), it.getString("default_level_code"), requireNotNull(nullableBoolean(it, "required_by_default")), it.getInt("sort_order"), requireNotNull(instant(it, "created_at")))
    }
    fun saveScenarioFilterRef(record: ScenarioFilterRefRecord) {
        owned(record.id, record.scenarioId, record.scenarioVersion, "scenario_filter_ref")
        requireText(record.filterTargetType, "scenario_filter_ref.filter_target_type")
        require(record.filterTargetType in ScenarioFilterRefCreateRequest.TARGET_TYPES) { "scenario_filter_ref.filter_target_type must be ${ScenarioFilterRefCreateRequest.TARGET_TYPES.joinToString(", ")}: ${record.filterTargetType}" }
        requireText(record.targetId, "scenario_filter_ref.target_id"); requireVersion(record.targetVersion, "scenario_filter_ref.target_version")
        com.olap.prototype.query.contract.FilterLeafOperatorPolicy.requireOperatorSet(record.operators, "scenario_filter_ref.operators")
        record.valueProviderId?.let { requireText(it, "scenario_filter_ref.value_provider_id") }
        GovernanceContract.requireBoolean(record.requiredByDefault, "scenario_filter_ref.required_by_default"); GovernanceContract.requireInteger(record.sortOrder, "scenario_filter_ref.sort_order"); GovernanceContract.requireInstant(record.createdAt, "scenario_filter_ref.created_at")
        storage("Failed to save scenario_filter_ref: ${record.id}") { insert(database, "scenario_filter_ref", linkedMapOf("id" to record.id, "scenario_id" to record.scenarioId, "scenario_version" to record.scenarioVersion,
            "filter_target_type" to record.filterTargetType, "target_id" to record.targetId, "target_version" to record.targetVersion, "operators_proto" to ProtoBytes.storage(com.olap.prototype.wire.governance.FilterOperators.newBuilder().addAllOperators(record.operators).build()),
            "value_provider_id" to record.valueProviderId, "required_by_default" to record.requiredByDefault, "sort_order" to record.sortOrder, "created_at" to record.createdAt)) }
    }
    fun listScenarioFilterRefs(id: String, version: Long): List<ScenarioFilterRefRecord> = listOwned("scenario_filter_ref", id, version, "sort_order,filter_target_type,target_id") {
        ScenarioFilterRefRecord(it.getString("id"), it.getString("scenario_id"), requireNotNull(nullableLong(it, "scenario_version")), it.getString("filter_target_type"), it.getString("target_id"), requireNotNull(nullableLong(it, "target_version")), ProtoBytes.decode(ProtoBytes.stored(it.getString("operators_proto")), com.olap.prototype.wire.governance.FilterOperators.getDefaultInstance()).operatorsList, it.getString("value_provider_id"), requireNotNull(nullableBoolean(it, "required_by_default")), it.getInt("sort_order"), requireNotNull(instant(it, "created_at")))
    }
    fun saveScenarioPipelineRule(record: ScenarioPipelineRuleRecord) {
        owned(record.id, record.scenarioId, record.scenarioVersion, "scenario_pipeline_rule")
        val type = ScenarioPipelineRulePolicy.requireRuleType(record.ruleType, "scenario_pipeline_rule.rule_type")
        val display = ScenarioPipelineRulePolicy.requireDisplayName(record.displayName, "scenario_pipeline_rule.display_name")
        val config = ProtoBytes.storage(GovernanceProto.toProto(record.authorization))
        val status = ScenarioPipelineRulePolicy.requireStatus(record.status, "scenario_pipeline_rule.status")
        GovernanceContract.requireInteger(record.sortOrder, "scenario_pipeline_rule.sort_order"); GovernanceContract.requireInstant(record.createdAt, "scenario_pipeline_rule.created_at")
        storage("Failed to save scenario_pipeline_rule: ${record.id}") { insert(database, "scenario_pipeline_rule", linkedMapOf("id" to record.id, "scenario_id" to record.scenarioId, "scenario_version" to record.scenarioVersion,
            "rule_type" to type, "display_name" to display, "authorization_proto" to config, "status" to status, "sort_order" to record.sortOrder, "created_at" to record.createdAt)) }
    }
    fun listScenarioPipelineRules(id: String, version: Long): List<ScenarioPipelineRuleRecord> = listOwned("scenario_pipeline_rule", id, version, "sort_order,rule_type") {
        ScenarioPipelineRuleRecord(it.getString("id"), it.getString("scenario_id"), requireNotNull(nullableLong(it, "scenario_version")), it.getString("rule_type"), it.getString("display_name"), GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(it.getString("authorization_proto")), com.olap.prototype.wire.governance.StageAuthorization.getDefaultInstance())), it.getString("status"), it.getInt("sort_order"), requireNotNull(instant(it, "created_at")))
    }
    fun updateScenarioPipelineRuleContract(rule: ScenarioPipelineRuleRecord) {
        ScenarioPipelineRulePolicy.requireScenarioPipelineRule(rule)
        storage("Failed to update scenario_pipeline_rule contract: ${rule.id}") {
            if (execute(database, "UPDATE scenario_pipeline_rule SET authorization_proto=? WHERE id=? AND scenario_id=? AND scenario_version=? AND rule_type=?", listOf(ProtoBytes.storage(GovernanceProto.toProto(rule.authorization)), rule.id, rule.scenarioId, rule.scenarioVersion, rule.ruleType)) != 1)
                throw IllegalStateException("scenario pipeline contract update requires one exact stage: ${rule.id}")
        }
    }
    fun saveMemberSourceRef(reference: ScenarioMemberSourceRefRecord) {
        storage("cannot persist exact scenario member permission") { insert(database, "scenario_member_source_ref", linkedMapOf("scenario_id" to reference.scenarioId, "scenario_version" to reference.scenarioVersion,
            "source_id" to reference.sourceId, "source_version" to reference.sourceVersion, "permission_proto" to ProtoBytes.storage(GovernanceProto.toProto(reference.permission)))) }
    }
    fun memberSourceRefs(id: String, version: Long): List<ScenarioMemberSourceRefRecord> = storage("cannot read exact scenario member permissions") {
        query(database, "SELECT * FROM scenario_member_source_ref WHERE scenario_id=? AND scenario_version=? ORDER BY source_id", listOf(id, version)) {
            ScenarioMemberSourceRefRecord(id, version, it.getString("source_id"), it.getLong("source_version"), GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(it.getString("permission_proto")), com.olap.prototype.wire.governance.MemberQueryPermission.getDefaultInstance())))
        }
    }
    fun saveRelationFilter(record: ScenarioRelationFilterRecord) {
        val definition = record.definition
        requireText(record.scenarioId, "relation filter scenario"); requireVersion(record.scenarioVersion, "relation filter scenario version")
        require(record.scenarioId == definition.scenarioId && record.scenarioVersion == definition.relation.version) { "relation filter owner differs from its scenario" }
        storage("cannot persist exact relation filter grant") {
            execute(database, "MERGE INTO scenario_relation_filter_ref (id,scenario_id,scenario_version,code,event_id,event_version,member_source_id,member_source_version,definition_proto) KEY(scenario_id,scenario_version,code) VALUES (?,?,?,?,?,?,?,?,?)",
                listOf(definition.relation.id, record.scenarioId, record.scenarioVersion, record.code, definition.relation.parent.eventId, definition.relation.parent.eventVersion,
                    definition.relation.memberSourceId, definition.relation.memberSourceVersion, ProtoBytes.storage(GovernanceProto.toProto(definition))))
        }
    }
    fun relationFilters(id: String, version: Long): List<ScenarioRelationFilterRecord> = storage("cannot read exact relation filter grants") {
        query(database, "SELECT * FROM scenario_relation_filter_ref WHERE scenario_id=? AND scenario_version=? ORDER BY code,id", listOf(id, version), ::relation)
    }
    fun findRelationFilter(id: String, version: Long): ScenarioRelationFilterRecord? {
        requireText(id, "relation filter id"); requireVersion(version, "relation filter version")
        return storage("cannot read exact relation filter grant") { query(database, "SELECT * FROM scenario_relation_filter_ref WHERE id=? AND scenario_version=?", listOf(id, version), ::relation).singleOrNull() }
    }
    private fun relation(rows: ResultSet): ScenarioRelationFilterRecord {
        val definition = GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(rows.getString("definition_proto")), com.olap.prototype.wire.governance.PublishedRelationFilter.getDefaultInstance()))
        val relation = definition.relation
        check(relation.id == rows.getString("id") && relation.version == rows.getLong("scenario_version") && definition.scenarioId == rows.getString("scenario_id") &&
            relation.parent.eventId == rows.getString("event_id") && relation.parent.eventVersion == rows.getLong("event_version") && relation.memberSourceId == rows.getString("member_source_id") && relation.memberSourceVersion == rows.getLong("member_source_version")) { "relation filter definition differs from its dependency index" }
        return ScenarioRelationFilterRecord(rows.getString("scenario_id"), rows.getLong("scenario_version"), rows.getString("code"), definition)
    }
    private fun owned(id: String, scenario: String, version: Long, table: String) { requireText(id, "$table.id"); requireText(scenario, "$table.scenario_id"); requireVersion(version, "$table.scenario_version") }
    private fun <T> listOwned(table: String, id: String, version: Long, order: String, map: (ResultSet) -> T): List<T> {
        requireText(id, "$table.scenario_id"); requireVersion(version, "$table.scenario_version")
        return storage("Failed to list $table: $id#$version") { query(database, "SELECT * FROM $table WHERE scenario_id=? AND scenario_version=? ORDER BY $order", listOf(id, version), map) }
    }
    private fun <T> storage(message: String, action: () -> T): T = try { action() } catch (failure: Exception) { throw IllegalStateException(message, failure) }
}
