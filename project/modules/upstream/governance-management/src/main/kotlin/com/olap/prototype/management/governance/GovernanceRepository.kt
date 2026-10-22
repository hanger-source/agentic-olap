package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet

class GovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)
    private val refs = GovernanceVersionRefRepository(database)
    private val identities = GovernanceIdentityRepository(database)
    fun savePhysicalTable(record: PhysicalTableRecord) = identities.savePhysicalTable(record)
    fun findPhysicalTable(id: String): PhysicalTableRecord? = working.find("physical_table_version", "physical_table_id", requireText(id, "physical_table.id"))?.let { snapshots.physicalTable(it.objectId, it.version) }
    fun listPhysicalTables(): List<PhysicalTableRecord> = working.list("physical_table_version", "physical_table_id").map { snapshots.physicalTable(it.objectId, it.version) }.sortedBy { it.code }
    fun physicalTableIdentityExists(id: String): Boolean = identities.exists("physical_table", id)
    fun savePhysicalColumn(record: PhysicalColumnRecord) = identities.savePhysicalColumn(record)
    fun findPhysicalColumn(id: String): PhysicalColumnRecord? = working.find("physical_column_version", "physical_column_id", requireText(id, "physical_column.id"))?.let { snapshots.physicalColumn(it.objectId, it.version) }
    fun listPhysicalColumnsByTable(id: String): List<PhysicalColumnRecord> {
        requireText(id, "physical_column.physical_table_id")
        val table = findPhysicalTable(id) ?: return emptyList()
        return snapshots.physicalTableColumns(table.id, table.currentVersion).filter { it.enabled }.sortedWith(compareBy(PhysicalColumnRecord::ordinalPosition, PhysicalColumnRecord::columnName))
    }
    fun physicalColumnIdentityExists(id: String): Boolean = identities.exists("physical_column", id)
    fun saveLogicalTable(record: LogicalTableRecord) = identities.saveLogicalTable(record)
    fun findLogicalTable(id: String): LogicalTableRecord? = working.find("logical_table_version", "logical_table_id", requireText(id, "logical_table.id"))?.let { snapshots.logicalTable(it.objectId, it.version) }
    fun listLogicalTables(): List<LogicalTableRecord> = working.list("logical_table_version", "logical_table_id").map { snapshots.logicalTable(it.objectId, it.version) }.sortedBy { it.code }
    fun logicalTableIdentityExists(id: String): Boolean = identities.exists("logical_table", id)
    fun listLogicalTablesByPhysicalTable(id: String): List<LogicalTableRecord> {
        requireText(id, "physical_logical_table_ref.physical_table_id")
        return listPhysicalLogicalTableRefsByPhysicalTable(id).filter { working.find("logical_table_version", "logical_table_id", it.logicalTableId)?.version == it.logicalTableVersion }
            .map { snapshots.logicalTable(it.logicalTableId, it.logicalTableVersion) }.distinct().sortedBy { it.code }
    }
    fun savePhysicalLogicalTableRef(r: PhysicalLogicalTableRefRecord) {
        val table = "physical_logical_table_ref"
        requireText(r.id, "$table.id"); requireText(r.physicalTableId, "$table.physical_table_id"); requireVersion(r.physicalTableVersion, "$table.physical_table_version"); requireText(r.logicalTableId, "$table.logical_table_id"); requireVersion(r.logicalTableVersion, "$table.logical_table_version"); requireText(r.refRole, "$table.ref_role")
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE physical_logical_table_ref SET physical_table_id=?, physical_table_version=?, logical_table_id=?, logical_table_version=?, ref_role=?, created_at=? WHERE id=?", listOf(r.physicalTableId, r.physicalTableVersion, r.logicalTableId, r.logicalTableVersion, r.refRole, r.createdAt, r.id))
            if (count == 0) GovernanceJdbcSupport.insert(database, table, linkedMapOf("id" to r.id, "physical_table_id" to r.physicalTableId, "physical_table_version" to r.physicalTableVersion, "logical_table_id" to r.logicalTableId, "logical_table_version" to r.logicalTableVersion, "ref_role" to r.refRole, "created_at" to r.createdAt))
        } catch (failure: Exception) { throw IllegalStateException("Failed to save $table: ${r.id}", failure) }
    }
    fun listPhysicalLogicalTableRefsByPhysicalTable(id: String): List<PhysicalLogicalTableRefRecord> {
        requireText(id, "physical_logical_table_ref.physical_table_id")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM physical_logical_table_ref WHERE physical_table_id=? ORDER BY ref_role, logical_table_id, logical_table_version", listOf(id), ::physicalRef) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list physical_logical_table_ref by physical table: $id", failure) }
    }
    fun listPhysicalLogicalTableRefsByLogicalTable(id: String): List<PhysicalLogicalTableRefRecord> {
        requireText(id, "physical_logical_table_ref.logical_table_id")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM physical_logical_table_ref WHERE logical_table_id=? ORDER BY ref_role, physical_table_id, physical_table_version", listOf(id), ::physicalRef) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list physical_logical_table_ref by logical table: $id", failure) }
    }
    fun saveLogicUnit(record: LogicUnitRecord) = identities.saveLogicUnit(record)
    fun findLogicUnit(id: String): LogicUnitRecord? = working.find("logic_unit_version", "logic_unit_id", requireText(id, "logic_unit.id"))?.let { snapshots.logicUnit(it.objectId, it.version) }
    fun listLogicUnitsByLogicalTable(id: String): List<LogicUnitRecord> {
        requireText(id, "logic_unit.logical_table_id")
        return working.list("logic_unit_version", "logic_unit_id").map { snapshots.logicUnit(it.objectId, it.version) }.filter { it.logicalTableId == id }.sortedBy { it.code }
    }
    fun logicUnitIdentityExists(id: String): Boolean = identities.exists("logic_unit", id)
    fun saveTopology(record: TopologyRecord) = identities.saveTopology(record)
    fun findTopology(id: String): TopologyRecord? = working.find("topology_version", "topology_id", requireText(id, "topology.id"))?.let { snapshots.topology(it.objectId, it.version) }
    fun listTopologies(): List<TopologyRecord> = working.list("topology_version", "topology_id").map { snapshots.topology(it.objectId, it.version) }.sortedBy { it.code }
    fun topologyIdentityExists(id: String): Boolean = identities.exists("topology", id)
    fun saveTopologyLogicalTableRef(r: TopologyLogicalTableRefRecord) {
        val table = "topology_logical_table_ref"
        requireText(r.id, "$table.id"); requireText(r.topologyId, "$table.topology_id"); requireVersion(r.topologyVersion, "$table.topology_version"); requireText(r.logicalTableId, "$table.logical_table_id"); requireVersion(r.logicalTableVersion, "$table.logical_table_version"); requireText(r.usageRole, "$table.usage_role")
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE topology_logical_table_ref SET topology_id=?, topology_version=?, logical_table_id=?, logical_table_version=?, usage_role=?, created_at=? WHERE id=?", listOf(r.topologyId, r.topologyVersion, r.logicalTableId, r.logicalTableVersion, r.usageRole, r.createdAt, r.id))
            if (count == 0) GovernanceJdbcSupport.insert(database, table, linkedMapOf("id" to r.id, "topology_id" to r.topologyId, "topology_version" to r.topologyVersion, "logical_table_id" to r.logicalTableId, "logical_table_version" to r.logicalTableVersion, "usage_role" to r.usageRole, "created_at" to r.createdAt))
        } catch (failure: Exception) { throw IllegalStateException("Failed to save $table: ${r.id}", failure) }
    }
    fun listTopologyLogicalTableRefs(id: String, version: Long): List<TopologyLogicalTableRefRecord> = refs.listTopologyLogicalTableRefs(id, version)
    fun listTopologyLogicalTableRefsByLogicalTable(id: String): List<TopologyLogicalTableRefRecord> = refs.listTopologyLogicalTableRefsByLogicalTable(id)
    fun topologyVersionExists(id: String, version: Long): Boolean = refs.topologyVersionExists(id, version)
    fun logicUnitVersionExists(id: String, version: Long): Boolean = refs.logicUnitVersionExists(id, version)
    fun saveJoinPath(record: JoinPathRecord) = identities.saveJoinPath(record)
    fun listJoinPaths(id: String, version: Long): List<JoinPathRecord> {
        requireText(id, "topology_join_path_ref.topology_id"); requireVersion(version, "topology_join_path_ref.topology_version")
        return try { refs.listTopologyJoinPathRefs(id, version).map { snapshots.joinPath(it.joinPathId, it.joinPathVersion) } }
        catch (failure: Exception) { throw IllegalStateException("Failed to list join_path: $id", failure) }
    }
    fun findJoinPath(id: String): JoinPathRecord? = working.find("join_path_version", "join_path_id", requireText(id, "join_path.id"))?.let { snapshots.joinPath(it.objectId, it.version) }
    fun joinPathIdentityExists(id: String): Boolean = identities.exists("join_path", id)
    fun saveEvent(record: EventRecord) = identities.saveEvent(record)
    fun findEvent(id: String): EventRecord? = working.find("event_version", "event_id", requireText(id, "event.id"))?.let { snapshots.event(it.objectId, it.version) }
    fun eventIdentityExists(id: String): Boolean = identities.exists("event", id)
    fun listEventsByTopology(id: String, version: Long): List<EventRecord> {
        requireText(id, "event.topology_id"); requireVersion(version, "event.topology_version")
        return listEvents().filter { it.topologyId == id && it.topologyVersion == version }
    }
    fun listEvents(): List<EventRecord> = working.list("event_version", "event_id").map { snapshots.event(it.objectId, it.version) }.sortedBy { it.code }
    fun saveEventVersion(r: EventVersionRecord) {
        GovernanceContract.requireVersionObject("event_version", "event_id", r.id, r.eventId, r.version, r.versionStatus, r.createdBy, r.createdAt); requireText(r.contentJson, "event_version.content_json")
        try { GovernanceJdbcSupport.insert(database, "event_version", linkedMapOf("id" to r.id, "event_id" to r.eventId, "version" to r.version, "version_status" to r.versionStatus, "content_json" to r.contentJson, "diagnostic_json" to r.diagnosticJson, "source_version" to r.sourceVersion, "created_by" to r.createdBy, "created_at" to r.createdAt, "activated_by" to r.activatedBy, "activated_at" to r.activatedAt, "deprecated_by" to r.deprecatedBy, "deprecated_at" to r.deprecatedAt, "archived_by" to r.archivedBy, "archived_at" to r.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save event_version: ${r.id}", failure) }
    }
    fun findEventVersion(id: String, version: Long): EventVersionRecord? {
        requireText(id, "event_version.event_id"); requireVersion(version, "event_version.version")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM event_version WHERE event_id=? AND version=?", listOf(id, version), ::eventVersion).singleOrNull() }
        catch (failure: Exception) { throw IllegalStateException("Failed to find event_version: $id#$version", failure) }
    }
    fun listEventVersions(id: String): List<EventVersionRecord> {
        requireText(id, "event_version.event_id")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM event_version WHERE event_id=? ORDER BY version", listOf(id), ::eventVersion) }
        catch (failure: Exception) { throw IllegalStateException("Failed to list event_version: $id", failure) }
    }
    fun saveEventLogicUnitRef(r: EventLogicUnitRefRecord) {
        val table = "event_logic_unit_ref"
        requireText(r.id, "$table.id"); requireText(r.eventId, "$table.event_id"); requireVersion(r.eventVersion, "$table.event_version"); requireText(r.logicUnitId, "$table.logic_unit_id"); requireVersion(r.logicUnitVersion, "$table.logic_unit_version"); requireText(r.refRole, "$table.ref_role")
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE event_logic_unit_ref SET event_id=?, event_version=?, logic_unit_id=?, logic_unit_version=?, ref_role=?, created_at=? WHERE id=?", listOf(r.eventId, r.eventVersion, r.logicUnitId, r.logicUnitVersion, r.refRole, r.createdAt, r.id))
            if (count == 0) GovernanceJdbcSupport.insert(database, table, linkedMapOf("id" to r.id, "event_id" to r.eventId, "event_version" to r.eventVersion, "logic_unit_id" to r.logicUnitId, "logic_unit_version" to r.logicUnitVersion, "ref_role" to r.refRole, "created_at" to r.createdAt))
        } catch (failure: Exception) { throw IllegalStateException("Failed to save $table: ${r.id}", failure) }
    }
    fun listEventLogicUnitRefs(id: String, version: Long): List<EventLogicUnitRefRecord> = refs.listEventLogicUnitRefs(id, version)
    fun saveEventSliceFilter(r: EventSliceFilterRecord) {
        val table = "event_slice_filter"
        requireText(r.id, "$table.id"); requireText(r.eventId, "$table.event_id"); requireVersion(r.eventVersion, "$table.event_version"); requireText(r.filterJson, "$table.filter_json")
        try {
            val count = GovernanceJdbcSupport.execute(database, "UPDATE event_slice_filter SET event_id=?, event_version=?, filter_json=?, created_at=? WHERE id=?", listOf(r.eventId, r.eventVersion, r.filterJson, r.createdAt, r.id))
            if (count == 0) GovernanceJdbcSupport.insert(database, table, linkedMapOf("id" to r.id, "event_id" to r.eventId, "event_version" to r.eventVersion, "filter_json" to r.filterJson, "created_at" to r.createdAt))
        } catch (failure: Exception) { throw IllegalStateException("Failed to save $table: ${r.id}", failure) }
    }
    fun listEventSliceFilters(id: String, version: Long): List<EventSliceFilterRecord> {
        requireText(id, "event_slice_filter.event_id"); requireVersion(version, "event_slice_filter.event_version")
        return try { GovernanceJdbcSupport.query(database, "SELECT * FROM event_slice_filter WHERE event_id=? AND event_version=? ORDER BY id", listOf(id, version)) { r -> EventSliceFilterRecord(r.getString("id"), r.getString("event_id"), r.getLong("event_version"), r.getString("filter_json"), r.getTimestamp("created_at").toInstant()) } }
        catch (failure: Exception) { throw IllegalStateException("Failed to list event_slice_filter: $id", failure) }
    }
    private fun physicalRef(r: ResultSet): PhysicalLogicalTableRefRecord = PhysicalLogicalTableRefRecord(r.getString("id"), r.getString("physical_table_id"), r.getLong("physical_table_version"), r.getString("logical_table_id"), r.getLong("logical_table_version"), r.getString("ref_role"), r.getTimestamp("created_at").toInstant())
    private fun eventVersion(r: ResultSet): EventVersionRecord = EventVersionRecord(r.getString("id"), r.getString("event_id"), r.getLong("version"), r.getString("version_status"), r.getString("content_json"), r.getString("diagnostic_json"), GovernanceJdbcSupport.nullableLong(r, "source_version"), r.getString("created_by"), r.getTimestamp("created_at").toInstant(), r.getString("activated_by"), GovernanceJdbcSupport.instant(r, "activated_at"), r.getString("deprecated_by"), GovernanceJdbcSupport.instant(r, "deprecated_at"), r.getString("archived_by"), GovernanceJdbcSupport.instant(r, "archived_at"))
}
