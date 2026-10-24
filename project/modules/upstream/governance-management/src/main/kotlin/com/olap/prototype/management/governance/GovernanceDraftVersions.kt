package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.GovernanceRevisionRequest
import com.olap.prototype.storage.PlatformDatabase
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/** 父聚合事务内的身份行锁、版本分配与编辑权限；不单独提交。 */
internal class GovernanceDraftVersions(private val database: PlatformDatabase) {
    enum class Asset(val table: String, val column: String) {
        PHYSICAL_TABLE("physical_table", "physical_table_id"), PHYSICAL_COLUMN("physical_column", "physical_column_id"),
        LOGICAL_TABLE("logical_table", "logical_table_id"), LOGIC_UNIT("logic_unit", "logic_unit_id"), TOPOLOGY("topology", "topology_id"),
        JOIN_PATH("join_path", "join_path_id"), EVENT("event", "event_id"), METRIC("metric", "metric_id"), MEMBER_DEFINITION("member_definition", "member_definition_id"),
        DIMENSION("dimension", "dimension_id"), DIMENSION_EVENT_BINDING("dimension_event_binding", "binding_id"), BUSINESS_LINE("business_line", "business_line_id"),
        SUBJECT_AREA("subject_area", "subject_area_id"), ANALYSIS_DIMENSION("analysis_dimension", "analysis_dimension_id"), SCENARIO("scenario", "scenario_id")
    }
    data class Draft(val version: Long, val sourceVersion: Long?)
    fun start(asset: Asset, id: String, revision: GovernanceRevisionRequest<*>?, actor: String): Draft {
        requireText(id, "objectId")
        if (revision == null) {
            if (GovernanceIdentityRepository(database).exists(asset.table, id)) throw ProducerContractException("governance identity already exists: $id")
            return Draft(1, null)
        }
        if (id != revision.objectId) throw ProducerContractException("revision requires exact identity and complete definition")
        requireVersion(revision.sourceVersion, "sourceVersion")
        return append(asset, id, revision.sourceVersion, actor, false)
    }
    fun owned(asset: Asset, id: String, actor: String): Draft =
        if (!GovernanceIdentityRepository(database).exists(asset.table, id)) Draft(1, null) else append(asset, id, null, actor, true)

    private fun append(asset: Asset, id: String, expected: Long?, actor: String, owned: Boolean): Draft {
        requireText(actor, "revision actor")
        return try {
            database.connection().use { connection ->
                lockIdentity(connection, asset, id)
                val (source, status) = connection.prepareStatement("SELECT version, version_status FROM ${asset.table}_version WHERE ${asset.column}=? ORDER BY version DESC LIMIT 1").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw ProducerContractException("identity has no source version: $id")
                        rows.getLong(1) to rows.getString(2)
                    }
                }
                if (expected != null && source != expected) throw ProducerContractException("stale sourceVersion: $id expected=$expected actual=$source")
                if (status != "DRAFT" && status != "ACTIVE" && !(owned && status == "ARCHIVED")) throw ProducerContractException("source version cannot be revised: $id status=$status")
                if (status == "DRAFT") connection.prepareStatement("UPDATE ${asset.table}_version SET version_status='ARCHIVED', archived_by=?, archived_at=? WHERE ${asset.column}=? AND version=? AND version_status='DRAFT'").use { statement ->
                    statement.setString(1, actor); statement.setTimestamp(2, Timestamp.from(Instant.now())); statement.setString(3, id); statement.setLong(4, source)
                    if (statement.executeUpdate() != 1) throw ProducerContractException("draft changed during revision")
                }
                Draft(Math.addExact(source, 1), source)
            }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("governance revision transaction failed", failure) }
    }
    fun requireEditable(asset: Asset, id: String, version: Long?) {
        requireText(id, "objectId"); val exact = requireVersion(version, "member parent version")
        try {
            database.connection().use { connection ->
                lockIdentity(connection, asset, id)
                connection.prepareStatement("SELECT version_status FROM ${asset.table}_version WHERE ${asset.column}=? AND version=?").use { statement ->
                    statement.setString(1, id); statement.setLong(2, exact)
                    statement.executeQuery().use { rows ->
                        val status = if (rows.next()) rows.getString(1) else null
                        if (status != "DRAFT") throw com.olap.prototype.governance.contract.GovernanceDraftRequiredException(asset.name, id, exact, status)
                    }
                }
            }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("governance edit transaction failed", failure) }
    }
    fun lock(asset: Asset, id: String) {
        try { database.connection().use { lockIdentity(it, asset, id) } }
        catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("governance identity lock failed", failure) }
    }
    companion object {
        fun lockIdentity(connection: Connection, asset: Asset, id: String) {
            check(!connection.autoCommit) { "governance writes require an aggregate transaction" }
            connection.prepareStatement("SELECT id FROM ${asset.table} WHERE id=? FOR UPDATE").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> if (!rows.next()) throw ProducerContractException("governance identity not found: $id") }
            }
        }
    }
}
