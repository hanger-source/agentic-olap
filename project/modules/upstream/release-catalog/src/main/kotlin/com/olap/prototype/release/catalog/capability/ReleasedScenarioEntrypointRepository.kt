package com.olap.prototype.release.catalog.capability

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.SqlConnectionProvider
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider.Scope
import java.sql.ResultSet
import java.time.Instant

/** 入口只来自不可变发布闭包，并与真实可用的能力、执行产物作交集。 */
class ReleasedScenarioEntrypointRepository(private val connections: SqlConnectionProvider) {
    fun list(subjectAreaId: String? = null, releaseId: String? = null, scopes: List<Scope> = listOf(Scope(null, null)), offset: Int = 0, limit: Int = Int.MAX_VALUE): List<ReleasedScenarioEntrypoint> {
        require(offset >= 0 && limit > 0) { "invalid released scenario page" }
        val groups = scopes.filter { !it.empty() }
        if (groups.isEmpty()) return emptyList()
        val parameters = mutableListOf<String>()
        val clauses = groups.map { "(${scopeColumn("sar.subject_area_id", it.subjectAreaIds, parameters)} AND ${scopeColumn("sar.release_id", it.releaseIds, parameters)})" }
        val scope = " AND (${clauses.joinToString(" OR ")})"
        try {
            connections.getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT sar.release_id, sar.release_version, sar.release_display_name,
                           sar.subject_area_id, sar.subject_area_version,
                           subject_ref.object_display_name AS subject_area_display_name,
                           scenario_ref.object_id AS scenario_id,
                           scenario_ref.object_version AS scenario_version,
                           scenario_ref.object_display_name AS scenario_display_name,
                           sar.created_at
                      FROM subject_area_release sar
                      JOIN release_object_ref scenario_ref
                        ON scenario_ref.release_id=sar.release_id
                       AND scenario_ref.release_version=sar.release_version
                       AND scenario_ref.object_type='SCENARIO'
                      JOIN release_object_ref subject_ref
                        ON subject_ref.release_id=sar.release_id
                       AND subject_ref.release_version=sar.release_version
                       AND subject_ref.object_type='SUBJECT_AREA'
                       AND subject_ref.object_id=sar.subject_area_id
                       AND subject_ref.object_version=sar.subject_area_version
                      JOIN release_artifact artifact
                        ON artifact.release_id=sar.release_id
                       AND artifact.release_version=sar.release_version
                      JOIN release_executable_catalog catalog
                        ON catalog.release_id=sar.release_id
                       AND catalog.release_version=sar.release_version
                       AND catalog.scenario_id=scenario_ref.object_id
                       AND catalog.scenario_version=scenario_ref.object_version
                      JOIN release_query_capability capability
                        ON capability.release_id=catalog.release_id
                       AND capability.release_version=catalog.release_version
                       AND capability.scenario_id=catalog.scenario_id
                       AND capability.scenario_version=catalog.scenario_version
                     WHERE (? IS NULL OR sar.subject_area_id=?) AND (? IS NULL OR sar.release_id=?)
                    """.trimIndent() + scope + "\n" + """
                     ORDER BY sar.created_at DESC, sar.release_id,
                              sar.release_version, scenario_ref.object_code, scenario_ref.object_id, scenario_ref.object_version
                     LIMIT ? OFFSET ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, subjectAreaId); statement.setString(2, subjectAreaId)
                    statement.setString(3, releaseId); statement.setString(4, releaseId)
                    var parameter = 5
                    parameters.forEach { statement.setString(parameter++, it) }
                    statement.setInt(parameter++, limit); statement.setInt(parameter, offset)
                    statement.executeQuery().use { result ->
                        val entries = mutableListOf<ReleasedScenarioEntrypoint>()
                        while (result.next()) entries.add(ReleasedScenarioEntrypoint(
                            text(result, "scenario_id"), version(result, "scenario_version"), text(result, "scenario_display_name"),
                            text(result, "subject_area_id"), version(result, "subject_area_version"), text(result, "subject_area_display_name"),
                            text(result, "release_id"), text(result, "release_version"), text(result, "release_display_name"), instant(result, "created_at"),
                        ))
                        return java.util.List.copyOf(entries)
                    }
                }
            }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to list exact released scenario entrypoints", failure) }
    }
    private fun scopeColumn(column: String, values: Set<String>?, parameters: MutableList<String>): String {
        if (values == null) return "1=1"
        val ordered = values.sorted()
        parameters.addAll(ordered)
        return "$column IN (${ordered.joinToString(",") { "?" }})"
    }
    private fun text(result: ResultSet, column: String): String = result.getString(column)?.takeIf { it.isNotBlank() } ?: throw ProducerContractException("$column is required")
    private fun version(result: ResultSet, column: String): Long = result.getLong(column).also { if (result.wasNull() || it <= 0) throw ProducerContractException("$column must be a positive version") }
    private fun instant(result: ResultSet, column: String): Instant = result.getTimestamp(column)?.toInstant() ?: throw ProducerContractException("$column is required")
}
