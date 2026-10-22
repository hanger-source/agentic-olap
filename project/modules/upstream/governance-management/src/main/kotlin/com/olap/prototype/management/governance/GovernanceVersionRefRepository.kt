package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet

/** 精确物理/逻辑/拓扑版本及其依赖索引；不会按工作版本重定向依赖。 */
class GovernanceVersionRefRepository(private val database: PlatformDatabase) {
    fun savePhysicalTableVersion(r: PhysicalTableVersionRecord) {
        GovernanceContract.requireVersionObject("physical_table_version", "physical_table_id", r.id, r.physicalTableId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "physical_table_version.content_proto")
        insert("physical_table_version", r.id, linkedMapOf("id" to r.id, "physical_table_id" to r.physicalTableId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun savePhysicalColumnVersion(r: PhysicalColumnVersionRecord) {
        GovernanceContract.requireVersionObject("physical_column_version", "physical_column_id", r.id, r.physicalColumnId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "physical_column_version.content_proto")
        insert("physical_column_version", r.id, linkedMapOf("id" to r.id, "physical_column_id" to r.physicalColumnId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun saveLogicalTableVersion(r: LogicalTableVersionRecord) {
        GovernanceContract.requireVersionObject("logical_table_version", "logical_table_id", r.id, r.logicalTableId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "logical_table_version.content_proto")
        insert("logical_table_version", r.id, linkedMapOf("id" to r.id, "logical_table_id" to r.logicalTableId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun saveLogicUnitVersion(r: LogicUnitVersionRecord) {
        GovernanceContract.requireVersionObject("logic_unit_version", "logic_unit_id", r.id, r.logicUnitId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "logic_unit_version.content_proto")
        insert("logic_unit_version", r.id, linkedMapOf("id" to r.id, "logic_unit_id" to r.logicUnitId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun saveTopologyVersion(r: TopologyVersionRecord) {
        GovernanceContract.requireVersionObject("topology_version", "topology_id", r.id, r.topologyId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "topology_version.content_proto")
        insert("topology_version", r.id, linkedMapOf("id" to r.id, "topology_id" to r.topologyId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun saveJoinPathVersion(r: JoinPathVersionRecord) {
        GovernanceContract.requireVersionObject("join_path_version", "join_path_id", r.id, r.joinPathId, r.version, r.versionStatus, r.createdBy, r.createdAt)
        requireText(r.contentProto, "join_path_version.content_proto"); requireText(r.joinType, "join_path_version.join_type"); requireText(r.cardinality, "join_path_version.cardinality")
        insert("join_path_version", r.id, linkedMapOf("id" to r.id, "join_path_id" to r.joinPathId, "version" to r.version, "version_status" to r.versionStatus, "content_proto" to r.contentProto, "join_type" to r.joinType, "cardinality" to r.cardinality, "temporal_config_proto" to com.olap.prototype.query.TemporalJoinCodec.encode(r.temporal), "null_policy" to r.nullPolicy, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt))
    }
    fun physicalTableVersionExists(id: String, version: Long): Boolean = versionExists("physical_table_version", "physical_table_id", id, version)
    fun physicalColumnVersionExists(id: String, version: Long): Boolean = versionExists("physical_column_version", "physical_column_id", id, version)
    fun logicalTableVersionExists(id: String, version: Long): Boolean = versionExists("logical_table_version", "logical_table_id", id, version)
    fun logicUnitVersionExists(id: String, version: Long): Boolean = versionExists("logic_unit_version", "logic_unit_id", id, version)
    fun topologyVersionExists(id: String, version: Long): Boolean = versionExists("topology_version", "topology_id", id, version)
    fun joinPathVersionExists(id: String, version: Long): Boolean = versionExists("join_path_version", "join_path_id", id, version)

    fun saveLogicalTableLogicUnitRef(r: LogicalTableLogicUnitRefRecord) {
        requireRef("logical_table_logic_unit_ref", r.id, r.logicalTableId, r.logicalTableVersion, r.logicUnitId, r.logicUnitVersion)
        insert("logical_table_logic_unit_ref", r.id, linkedMapOf("id" to r.id, "logical_table_id" to r.logicalTableId, "logical_table_version" to r.logicalTableVersion, "logic_unit_id" to r.logicUnitId, "logic_unit_version" to r.logicUnitVersion, "created_at" to r.createdAt))
    }
    fun listLogicalTableLogicUnitRefs(id: String, version: Long): List<LogicalTableLogicUnitRefRecord> = refs("logical_table_logic_unit_ref", "logical_table_id", id, "logical_table_version", version, "logic_unit_id, logic_unit_version", ::logicalRef)
    fun findLogicalTableRefByLogicUnitVersion(id: String, version: Long): LogicalTableLogicUnitRefRecord {
        requireText(id, "logical_table_logic_unit_ref.logic_unit_id"); requireVersion(version, "logical_table_logic_unit_ref.logic_unit_version")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM logical_table_logic_unit_ref WHERE logic_unit_id=? AND logic_unit_version=?", listOf(id, version), ::logicalRef).firstOrNull() ?: throw ProducerContractException("logical_table_logic_unit_ref not found: $id#$version") }
        catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to find logical_table_logic_unit_ref: $id#$version", failure) }
    }
    fun listTopologyLogicalTableRefs(id: String, version: Long): List<TopologyLogicalTableRefRecord> = refs("topology_logical_table_ref", "topology_id", id, "topology_version", version, "usage_role, logical_table_id", ::topologyRef)
    fun listTopologyLogicalTableRefsByLogicalTable(id: String): List<TopologyLogicalTableRefRecord> {
        requireText(id, "topology_logical_table_ref.logical_table_id")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM topology_logical_table_ref WHERE logical_table_id=? ORDER BY topology_id, topology_version, usage_role", listOf(id), ::topologyRef) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list topology_logical_table_ref by logical table: $id", failure) }
    }
    fun listEventLogicUnitRefs(id: String, version: Long): List<EventLogicUnitRefRecord> = refs("event_logic_unit_ref", "event_id", id, "event_version", version, "ref_role, logic_unit_id") { r -> EventLogicUnitRefRecord(r.getString("id"), r.getString("event_id"), r.getLong("event_version"), r.getString("logic_unit_id"), r.getLong("logic_unit_version"), r.getString("ref_role"), r.getTimestamp("created_at").toInstant()) }
    fun saveLogicUnitSourceRef(r: LogicUnitSourceRefRecord) {
        requireRef("logic_unit_source_ref", r.id, r.logicUnitId, r.logicUnitVersion, r.physicalColumnId, r.physicalColumnVersion)
        val role = GovernanceContract.requireLogicUnitSourceRefRole(r.refRole, "logic_unit_source_ref.ref_role")
        insert("logic_unit_source_ref", r.id, linkedMapOf("id" to r.id, "logic_unit_id" to r.logicUnitId, "logic_unit_version" to r.logicUnitVersion, "physical_column_id" to r.physicalColumnId, "physical_column_version" to r.physicalColumnVersion, "ref_role" to role, "created_at" to r.createdAt))
    }
    fun listLogicUnitSourceRefs(id: String, version: Long): List<LogicUnitSourceRefRecord> = refs("logic_unit_source_ref", "logic_unit_id", id, "logic_unit_version", version, "ref_role, physical_column_id, physical_column_version") { r -> LogicUnitSourceRefRecord(r.getString("id"), r.getString("logic_unit_id"), r.getLong("logic_unit_version"), r.getString("physical_column_id"), r.getLong("physical_column_version"), r.getString("ref_role"), r.getTimestamp("created_at").toInstant()) }
    fun saveTopologyJoinPathRef(r: TopologyJoinPathRefRecord) {
        requireRef("topology_join_path_ref", r.id, r.topologyId, r.topologyVersion, r.joinPathId, r.joinPathVersion)
        insert("topology_join_path_ref", r.id, linkedMapOf("id" to r.id, "topology_id" to r.topologyId, "topology_version" to r.topologyVersion, "join_path_id" to r.joinPathId, "join_path_version" to r.joinPathVersion, "created_at" to r.createdAt))
    }
    fun replaceTopologyJoinPathRef(r: TopologyJoinPathRefRecord, sourceVersion: Long) {
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE topology_join_path_ref SET id=?, join_path_version=?, created_at=? WHERE topology_id=? AND topology_version=? AND join_path_id=? AND join_path_version=?", listOf(r.id, r.joinPathVersion, r.createdAt, r.topologyId, r.topologyVersion, r.joinPathId, sourceVersion))
            check(count == 1) { "exact source JoinPath selection is missing or changed" }
        } catch (failure: Exception) { throw IllegalStateException("Failed to replace topology_join_path_ref: ${r.id}", failure) }
    }
    fun listTopologyJoinPathRefs(id: String, version: Long): List<TopologyJoinPathRefRecord> = refs("topology_join_path_ref", "topology_id", id, "topology_version", version, "join_path_id, join_path_version") { r -> TopologyJoinPathRefRecord(r.getString("id"), r.getString("topology_id"), r.getLong("topology_version"), r.getString("join_path_id"), r.getLong("join_path_version"), r.getTimestamp("created_at").toInstant()) }
    fun saveJoinPathKeyRef(r: JoinPathKeyRefRecord) {
        requireRef("join_path_key_ref", r.id, r.joinPathId, r.joinPathVersion, r.sourceLogicUnitId, r.sourceLogicUnitVersion)
        requireText(r.targetLogicUnitId, "join_path_key_ref.target_logic_unit_id"); requireVersion(r.targetLogicUnitVersion, "join_path_key_ref.target_logic_unit_version")
        insert("join_path_key_ref", r.id, linkedMapOf("id" to r.id, "join_path_id" to r.joinPathId, "join_path_version" to r.joinPathVersion, "key_order" to r.keyOrder, "source_logic_unit_id" to r.sourceLogicUnitId, "source_logic_unit_version" to r.sourceLogicUnitVersion, "target_logic_unit_id" to r.targetLogicUnitId, "target_logic_unit_version" to r.targetLogicUnitVersion, "created_at" to r.createdAt))
    }
    fun listJoinPathKeyRefs(id: String, version: Long): List<JoinPathKeyRefRecord> = refs("join_path_key_ref", "join_path_id", id, "join_path_version", version, "key_order") { r -> JoinPathKeyRefRecord(r.getString("id"), r.getString("join_path_id"), r.getLong("join_path_version"), r.getInt("key_order"), r.getString("source_logic_unit_id"), r.getLong("source_logic_unit_version"), r.getString("target_logic_unit_id"), r.getLong("target_logic_unit_version"), r.getTimestamp("created_at").toInstant()) }

    private fun logicalRef(r: ResultSet): LogicalTableLogicUnitRefRecord = LogicalTableLogicUnitRefRecord(r.getString("id"), r.getString("logical_table_id"), r.getLong("logical_table_version"), r.getString("logic_unit_id"), r.getLong("logic_unit_version"), r.getTimestamp("created_at").toInstant())
    private fun topologyRef(r: ResultSet): TopologyLogicalTableRefRecord = TopologyLogicalTableRefRecord(r.getString("id"), r.getString("topology_id"), r.getLong("topology_version"), r.getString("logical_table_id"), r.getLong("logical_table_version"), r.getString("usage_role"), r.getTimestamp("created_at").toInstant())
    private fun insert(table: String, id: String, fields: Map<String, Any?>) {
        try { GovernanceJdbcSupport.insert(database, table, fields) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save $table: $id", failure) }
    }
    private fun requireRef(table: String, id: String, sourceId: String, sourceVersion: Long, targetId: String, targetVersion: Long) {
        requireText(id, "$table.id"); requireText(sourceId, "$table source identity"); requireVersion(sourceVersion, "$table source version"); requireText(targetId, "$table target identity"); requireVersion(targetVersion, "$table target version")
    }
    private fun versionExists(table: String, column: String, id: String, version: Long): Boolean {
        requireText(id, "$table.$column"); requireVersion(version, "$table.version")
        return try { GovernanceJdbcSupport.query(database, "SELECT 1 FROM $table WHERE $column=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check $table: $id#$version", failure) }
    }
    private fun <T> refs(table: String, idColumn: String, id: String, versionColumn: String, version: Long, order: String, read: (ResultSet) -> T): List<T> {
        requireText(id, "$table.$idColumn"); requireVersion(version, "$table.$versionColumn")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM $table WHERE $idColumn=? AND $versionColumn=? ORDER BY $order", listOf(id, version), read) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list $table: $id@$version", failure) }
    }
}
