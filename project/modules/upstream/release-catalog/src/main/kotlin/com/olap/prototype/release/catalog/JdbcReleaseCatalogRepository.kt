package com.olap.prototype.release.catalog

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.contract.SqlConnectionProvider
import com.olap.prototype.release.contract.*
import com.olap.prototype.util.OlapJsonUtils
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/** 发布闭包SQL映射；不在读取过程中重做治理或推导候选能力。 */
class JdbcReleaseCatalogRepository(private val connections: SqlConnectionProvider) : ReleaseCatalogRepository {
    override fun findReleaseDisplayName(releaseId: String, releaseVersion: String): String = singleText(
        "SELECT release_display_name FROM subject_area_release WHERE release_id=? AND release_version=?", releaseId, releaseVersion,
    )
    override fun findScenarioDisplayName(scenarioId: String, scenarioVersion: Long, releaseId: String, releaseVersion: String): String = singleText(
        "SELECT object_display_name FROM release_object_ref WHERE release_id=? AND release_version=? AND object_type='SCENARIO' AND object_id=? AND object_version=?",
        releaseId, releaseVersion, scenarioId, scenarioVersion,
    )
    override fun findReleaseObjectVersionRefs(releaseId: String, releaseVersion: String): List<ReleaseObjectRef> = query(
        """
        SELECT id,release_id,release_version,object_type,object_id,object_version,
               object_code,object_display_name,ref_role,snapshot_summary_json,created_at
          FROM release_object_ref WHERE release_id=? AND release_version=?
         ORDER BY ref_role,object_type,object_id,object_version
        """.trimIndent(),
        { row -> ReleaseObjectRef(text(row, "id"), text(row, "release_id"), text(row, "release_version"), text(row, "object_type"),
            text(row, "object_id"), version(row, "object_version"), text(row, "object_code"), text(row, "object_display_name"),
            text(row, "ref_role"), row.getString("snapshot_summary_json"), instant(row, "created_at")) },
        releaseId, releaseVersion,
    )
    override fun findReleaseMetricDimensionCapabilities(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseMetricDimensionCapability> = query(
        """
        SELECT c.id,c.release_id,c.release_version,c.subject_area_id,c.scenario_id,c.scenario_version,
               c.adoption_id,c.adoption_version,c.metric_id,c.metric_version,mr.object_display_name AS metric_display_name,
               c.metric_event_id,c.metric_event_version,c.analysis_dimension_id,c.analysis_dimension_version,
               ar.object_display_name AS analysis_dimension_display_name,c.level_code,
               (SELECT min(r.level_display_name) FROM release_dimension_mapping r
                 WHERE r.release_id=c.release_id AND r.release_version=c.release_version
                   AND r.event_id=c.metric_event_id AND r.event_version=c.metric_event_version
                   AND r.analysis_dimension_id=c.analysis_dimension_id
                   AND r.analysis_dimension_version=c.analysis_dimension_version AND r.level_code=c.level_code) AS level_display_name,
               c.available,c.unavailable_code,c.unavailable_reason,c.producer_stage,c.target_ref,c.target_version_ref,
               c.blocking,c.page_blocking,c.executable_blocking,c.created_at
          FROM release_metric_dimension_capability c
          LEFT JOIN release_object_ref mr ON mr.release_id=c.release_id AND mr.release_version=c.release_version
           AND mr.object_type='METRIC' AND mr.object_id=c.metric_id AND mr.object_version=c.metric_version
          LEFT JOIN release_object_ref ar ON ar.release_id=c.release_id AND ar.release_version=c.release_version
           AND ar.object_type='ANALYSIS_DIMENSION' AND ar.object_id=c.analysis_dimension_id AND ar.object_version=c.analysis_dimension_version
         WHERE c.release_id=? AND c.release_version=?
           AND (c.scenario_id IS NULL OR (c.scenario_id=? AND c.scenario_version=?))
         ORDER BY c.metric_id,c.analysis_dimension_id,c.level_code
        """.trimIndent(),
        { row -> ReleaseMetricDimensionCapability(
            text(row, "id"), text(row, "release_id"), text(row, "release_version"), text(row, "subject_area_id"),
            row.getString("scenario_id"), nullableLong(row, "scenario_version"), text(row, "adoption_id"), version(row, "adoption_version"),
            text(row, "metric_id"), version(row, "metric_version"), text(row, "metric_display_name"), text(row, "metric_event_id"), version(row, "metric_event_version"),
            text(row, "analysis_dimension_id"), version(row, "analysis_dimension_version"), text(row, "analysis_dimension_display_name"),
            row.getString("level_code"), row.getString("level_display_name"), row.getBoolean("available"), row.getString("unavailable_code"),
            row.getString("unavailable_reason"), row.getString("producer_stage"), row.getString("target_ref"), row.getString("target_version_ref"),
            row.getBoolean("blocking"), row.getBoolean("page_blocking"), row.getBoolean("executable_blocking"), instant(row, "created_at"),
        ) }, releaseId, releaseVersion, scenarioId, scenarioVersion,
    )
    override fun findReleaseTimeAnchorRules(releaseId: String, releaseVersion: String): List<ReleaseTimeAnchorRule> = query(
        """
        SELECT r.id,r.release_id,r.release_version,r.subject_area_id,r.scenario_id,r.scenario_version,
               r.event_id,r.event_version,r.topology_id,r.topology_version,r.topology_implementation_namespace_code,
               r.topology_implementation_namespace_display_name,r.effective_time_logic_unit_id,r.effective_time_logic_unit_version,
               r.effective_time_logic_unit_display_name,r.time_role,r.analysis_dimension_id,r.analysis_dimension_version,
               ar.object_display_name AS analysis_dimension_display_name,r.level_code,r.alias_symbols_json,
               r.partition_logic_unit_id,r.partition_logic_unit_version,r.diagnostic_json,r.created_at
          FROM release_time_anchor_rule r
          LEFT JOIN release_object_ref ar ON ar.release_id=r.release_id AND ar.release_version=r.release_version
           AND ar.object_type='ANALYSIS_DIMENSION' AND ar.object_id=r.analysis_dimension_id AND ar.object_version=r.analysis_dimension_version
         WHERE r.release_id=? AND r.release_version=? ORDER BY r.event_id,r.time_role
        """.trimIndent(),
        { row -> ReleaseTimeAnchorRule(text(row, "id"), text(row, "release_id"), text(row, "release_version"), text(row, "subject_area_id"),
            row.getString("scenario_id"), nullableLong(row, "scenario_version"), text(row, "event_id"), version(row, "event_version"),
            text(row, "topology_id"), version(row, "topology_version"), text(row, "topology_implementation_namespace_code"),
            text(row, "topology_implementation_namespace_display_name"), text(row, "effective_time_logic_unit_id"), version(row, "effective_time_logic_unit_version"),
            text(row, "effective_time_logic_unit_display_name"), text(row, "time_role"), row.getString("analysis_dimension_id"), version(row, "analysis_dimension_version"),
            row.getString("analysis_dimension_display_name"), row.getString("level_code"), row.getString("alias_symbols_json"),
            row.getString("partition_logic_unit_id"), nullableLong(row, "partition_logic_unit_version"), row.getString("diagnostic_json"), instant(row, "created_at")) },
        releaseId, releaseVersion,
    )
    override fun findReleaseDimensionMappings(releaseId: String, releaseVersion: String): List<ReleaseDimensionMapping> = query(
        """
        SELECT r.*,ar.object_display_name AS analysis_dimension_display_name FROM release_dimension_mapping r
          LEFT JOIN release_object_ref ar ON ar.release_id=r.release_id AND ar.release_version=r.release_version
           AND ar.object_type='ANALYSIS_DIMENSION' AND ar.object_id=r.analysis_dimension_id AND ar.object_version=r.analysis_dimension_version
         WHERE r.release_id=? AND r.release_version=? ORDER BY r.analysis_dimension_id,r.level_code,r.event_id
        """.trimIndent(),
        { row -> ReleaseDimensionMapping(text(row, "id"), text(row, "mapping_id"), text(row, "release_id"), text(row, "release_version"),
            text(row, "subject_area_id"), text(row, "event_id"), version(row, "event_version"), text(row, "analysis_dimension_id"),
            version(row, "analysis_dimension_version"), text(row, "analysis_dimension_display_name"), text(row, "level_code"), text(row, "level_display_name"),
            text(row, "dimension_id"), version(row, "dimension_version"), text(row, "dimension_code_namespace"), text(row, "dimension_code_namespace_display_name"),
            text(row, "dimension_display_name"), text(row, "binding_id"), version(row, "binding_version"), row.getString("value_alignment_key"), text(row, "value_alignment_status"),
            text(row, "topology_id"), version(row, "topology_version"), text(row, "topology_implementation_namespace_code"), text(row, "topology_implementation_namespace_display_name"),
            text(row, "logic_unit_id"), version(row, "logic_unit_version"), text(row, "logic_unit_display_name"), releaseJoinPathRefs(text(row, "join_path_refs_json")),
            text(row, "expression_json"), instant(row, "created_at")) }, releaseId, releaseVersion,
    )
    override fun findReleaseUnavailableReasons(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseUnavailableReason> = query(
        """
        SELECT id,release_id,release_version,scope_type,scenario_id,scenario_version,metric_id,metric_version,
               analysis_dimension_id,analysis_dimension_version,level_code,code,reason,producer_stage,target_ref,target_version_ref,
               blocking,page_blocking,executable_blocking,created_at FROM release_unavailable_reason
         WHERE release_id=? AND release_version=? AND (scenario_id IS NULL OR (scenario_id=? AND scenario_version=?))
         ORDER BY scope_type,metric_id,analysis_dimension_id,level_code,code
        """.trimIndent(),
        { row -> ReleaseUnavailableReason(text(row, "id"), text(row, "release_id"), text(row, "release_version"), text(row, "scope_type"),
            row.getString("scenario_id"), nullableLong(row, "scenario_version"), row.getString("metric_id"), nullableLong(row, "metric_version"),
            row.getString("analysis_dimension_id"), nullableLong(row, "analysis_dimension_version"), row.getString("level_code"), text(row, "code"), text(row, "reason"),
            text(row, "producer_stage"), text(row, "target_ref"), text(row, "target_version_ref"), row.getBoolean("blocking"), row.getBoolean("page_blocking"),
            row.getBoolean("executable_blocking"), instant(row, "created_at")) }, releaseId, releaseVersion, scenarioId, scenarioVersion,
    )
    override fun findReleaseDiagnostics(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseDiagnosticIssue> = query(
        """
        SELECT dr.id AS diagnostic_run_id,i.id AS issue_id,i.severity,i.code,i.message,i.stage,i.target,i.target_version_ref,
               i.blocker,i.page_blocking,i.executable_blocking FROM diagnostic_run dr
          JOIN diagnostic_issue i ON i.diagnostic_run_id=dr.id
         WHERE i.target IS NOT NULL AND i.target_version_ref IS NOT NULL
           AND ((dr.target_object_type='RELEASE' AND dr.target_object_id=? AND i.target_version_ref=?)
             OR (dr.target_object_type='SCENARIO' AND dr.target_object_id=? AND dr.target_object_version=?))
         ORDER BY i.blocker DESC,i.severity,i.code
        """.trimIndent(),
        { row -> ReleaseDiagnosticIssue(text(row, "diagnostic_run_id"), text(row, "issue_id"), ProducerContracts.requireDiagnosticSeverity(row.getString("severity"), "diagnostic_issue.severity"),
            text(row, "code"), text(row, "message"), text(row, "stage"), text(row, "target"), text(row, "target_version_ref"),
            row.getBoolean("blocker"), row.getBoolean("page_blocking"), row.getBoolean("executable_blocking")) }, releaseId, releaseVersion, scenarioId, scenarioVersion,
    )
    private fun releaseJoinPathRefs(json: String): List<ReleaseJoinPathRef> = try {
        OlapJsonUtils.mapper().readValue(json, OlapJsonUtils.mapper().typeFactory.constructCollectionType(List::class.java, ReleaseJoinPathRef::class.java))
    } catch (failure: Exception) { throw ProducerContractException("release_dimension_mapping.join_path_refs_json invalid") }
    private fun singleText(sql: String, vararg parameters: Any?): String {
        val rows = query(sql, { row -> row.getString(1)?.takeIf { it.isNotBlank() } ?: throw ProducerContractException("release catalog field is required at column index: 1") }, *parameters)
        if (rows.isEmpty()) throw ProducerContractException("canonical row missing for query: ${sql.trim()}")
        if (rows.size > 1) throw ProducerContractException("canonical query returned multiple rows: ${sql.trim()}")
        return rows.single()
    }
    private fun <T> query(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): List<T> {
        try {
            connections.getConnection().use { connection -> connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    val result = mutableListOf<T>()
                    while (rows.next()) result.add(mapper(rows))
                    return result
                }
            } }
        } catch (failure: SQLException) { throw ProducerContractException("canonical catalog query failed: ${failure.message}", failure) }
    }
    private fun text(row: ResultSet, column: String): String = row.getString(column)?.takeIf { it.isNotBlank() } ?: throw ProducerContractException("release catalog field is required: $column")
    private fun version(row: ResultSet, column: String): Long = row.getLong(column).also { if (row.wasNull() || it <= 0) throw ProducerContractException("release catalog version field is required: $column") }
    private fun nullableLong(row: ResultSet, column: String): Long? = row.getLong(column).let { if (row.wasNull()) null else it }
    private fun instant(row: ResultSet, column: String): Instant = row.getTimestamp(column)?.toInstant() ?: throw ProducerContractException("release catalog timestamp field is required: $column")
}
