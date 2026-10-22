package com.olap.prototype.management.warehouse

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.StructureProvenance
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** 数据库资源与字段映射归登记存储；模型构造拥有单个事实的不变量。 */
class WarehouseRegistryRepository(private val database: PlatformDatabase) : WarehouseDiscoveryReader {
    private val json = OlapJsonUtils.mapper()

    fun insertDataSource(record: WarehouseDataSourceRecord) = insert("warehouse_data_source registration rejected",
        "INSERT INTO warehouse_data_source(id,display_name,status,created_by,created_at) VALUES (?,?,?,?,?)",
        record.id, record.displayName, record.status, record.createdBy, record.createdAt)

    override fun findDataSource(id: String): WarehouseDataSourceRecord? {
        requireText(id, "warehouse_data_source.id")
        return query("SELECT * FROM warehouse_data_source WHERE id=?", listOf(id), ::dataSource).firstOrNull()
    }
    fun listDataSources(): List<WarehouseDataSourceRecord> = query("SELECT * FROM warehouse_data_source ORDER BY display_name,id", emptyList(), ::dataSource)

    fun insertSourceBinding(record: WarehouseSourceBindingRecord) {
        val reference = record.reference
        // 内容地址已存在时不覆盖最初保存的字节；凭据变量位置由当前授权部署解析。
        if (findSourceBinding(reference) != null) return
        insert("immutable warehouse source binding registration rejected",
            "INSERT INTO warehouse_source_binding(data_source_id,source_identity_hash,configuration_json,created_by,created_at) VALUES (?,?,?,?,?)",
            reference.dataSourceId, reference.sourceIdentityHash, json.writeValueAsString(record.source), requireText(record.createdBy, "source binding creator"), record.createdAt)
    }
    override fun findSourceBinding(reference: SourceBindingRef): WarehouseSourceBindingRecord? = query(
        "SELECT * FROM warehouse_source_binding WHERE data_source_id=? AND source_identity_hash=?", listOf(reference.dataSourceId, reference.sourceIdentityHash), ::sourceBinding).firstOrNull()
    fun listSourceBindings(dataSourceId: String): List<WarehouseSourceBindingRecord> {
        requireText(dataSourceId, "source binding data source")
        return query("SELECT * FROM warehouse_source_binding WHERE data_source_id=? ORDER BY source_identity_hash", listOf(dataSourceId), ::sourceBinding)
    }

    fun saveDiscoverySnapshot(record: WarehouseDiscoverySnapshotRecord) = save("warehouse_discovery_snapshot", record.id,
        "data_source" to record.dataSource, "dialect_id" to record.dialectId, "catalog_name" to record.catalogName, "schema_name" to record.schemaName,
        "database_product_name" to record.databaseProductName, "database_product_version" to record.databaseProductVersion, "driver_name" to record.driverName,
        "driver_version" to record.driverVersion, "read_only" to record.readOnly, "status" to record.status, "table_count" to record.tableCount,
        "error_message" to record.errorMessage, "requested_by" to record.requestedBy, "requested_at" to record.requestedAt, "discovered_at" to record.discoveredAt,
        "source_identity_hash" to record.sourceIdentityHash, "structure_provenance_json" to record.structureProvenance?.let(json::writeValueAsString))
    override fun findDiscoverySnapshot(id: String): WarehouseDiscoverySnapshotRecord? {
        requireText(id, "warehouse_discovery_snapshot.id")
        return query("SELECT * FROM warehouse_discovery_snapshot WHERE id=?", listOf(id), ::snapshot).firstOrNull()
    }
    fun listDiscoverySnapshots(dataSource: String, catalogName: String, schemaName: String): List<WarehouseDiscoverySnapshotRecord> {
        requireText(dataSource, "warehouse_discovery_snapshot.data_source"); requireText(schemaName, "warehouse_discovery_snapshot.schema_name")
        return query("SELECT * FROM warehouse_discovery_snapshot WHERE data_source=? AND catalog_name=? AND schema_name=? ORDER BY discovered_at DESC,id",
            listOf(dataSource, catalogName, schemaName), ::snapshot)
    }
    fun saveDiscoveredTable(record: WarehouseDiscoveredTableRecord) = save("warehouse_discovered_table", record.id,
        "discovery_snapshot_id" to record.discoverySnapshotId, "catalog_name" to record.catalogName, "schema_name" to record.schemaName, "table_name" to record.tableName,
        "table_comment" to record.tableComment, "row_count" to record.rowCount, "schema_hash" to record.schemaHash, "registered_physical_table_id" to record.registeredPhysicalTableId,
        "status" to record.status, "created_at" to record.createdAt, "constraints_json" to json.writeValueAsString(record.constraints))
    override fun findDiscoveredTable(id: String): WarehouseDiscoveredTableRecord? {
        requireText(id, "warehouse_discovered_table.id")
        return query("SELECT * FROM warehouse_discovered_table WHERE id=?", listOf(id), ::table).firstOrNull()
    }
    override fun listDiscoveredTables(snapshotId: String): List<WarehouseDiscoveredTableRecord> {
        requireText(snapshotId, "warehouse_discovered_table.discovery_snapshot_id")
        return query("SELECT * FROM warehouse_discovered_table WHERE discovery_snapshot_id=? ORDER BY schema_name,table_name", listOf(snapshotId), ::table)
    }
    fun saveDiscoveredColumn(record: WarehouseDiscoveredColumnRecord) = save("warehouse_discovered_column", record.id,
        "discovered_table_id" to record.discoveredTableId, "column_name" to record.columnName, "jdbc_type" to record.jdbcType, "type_name" to record.typeName,
        "precision_value" to record.precision, "scale_value" to record.scale, "auto_increment" to record.autoIncrement, "generated" to record.generated,
        "default_value" to record.defaultValue, "column_comment" to record.columnComment, "nullable" to record.nullable, "ordinal_position" to record.ordinalPosition, "created_at" to record.createdAt)
    override fun listDiscoveredColumns(discoveredTableId: String): List<WarehouseDiscoveredColumnRecord> {
        requireText(discoveredTableId, "warehouse_discovered_column.discovered_table_id")
        return query("SELECT * FROM warehouse_discovered_column WHERE discovered_table_id=? ORDER BY ordinal_position,column_name", listOf(discoveredTableId), ::column)
    }
    fun insertDiscoveredUniqueKey(record: WarehouseDiscoveredUniqueKeyRecord) = insert("cannot record immutable source unique proof",
        "INSERT INTO warehouse_discovered_unique_key(id,discovered_table_id,rule_name,proof_json) VALUES (?,?,?,?)",
        record.id, record.discoveredTableId, record.key.rule.name, json.writeValueAsString(record.key))
    override fun listDiscoveredUniqueKeys(discoveredTableId: String): List<WarehouseDiscoveredUniqueKeyRecord> {
        requireText(discoveredTableId, "unique proof table")
        return query("SELECT * FROM warehouse_discovered_unique_key WHERE discovered_table_id=? ORDER BY rule_name", listOf(discoveredTableId)) {
            WarehouseDiscoveredUniqueKeyRecord(it.text("id"), it.text("discovered_table_id"), json.readValue(it.text("proof_json"), SnapshotUniqueKey::class.java))
        }
    }
    fun insertSourceCollection(record: SourceCollectionRecord) {
        val existing = findSourceCollection(record.id)
        if (existing != null) {
            if (existing != record) throw ProducerContractException("immutable source collection identity conflict")
            return
        }
        insert("cannot register sealed source collection",
            "INSERT INTO warehouse_source_collection(id,data_source_id,source_identity_hash,declaration_sha256,collection_code,record_json) VALUES (?,?,?,?,?,?)",
            record.id, record.dataSourceId, record.sourceIdentityHash, record.declarationSha256, record.collection.code, json.writeValueAsString(record))
    }
    override fun findSourceCollection(id: String): SourceCollectionRecord? {
        requireText(id, "source collection id")
        return query("SELECT record_json FROM warehouse_source_collection WHERE id=?", listOf(id)) {
            json.readValue(it.text("record_json"), SourceCollectionRecord::class.java)
        }.firstOrNull()
    }
    fun listSourceCollections(dataSourceId: String): List<SourceCollectionRecord> {
        requireText(dataSourceId, "source collection datasource")
        return query("SELECT record_json FROM warehouse_source_collection WHERE data_source_id=? ORDER BY id", listOf(dataSourceId)) {
            json.readValue(it.text("record_json"), SourceCollectionRecord::class.java)
        }
    }
    fun savePhysicalColumnProfile(record: PhysicalColumnProfileRecord) = save("physical_column_profile", record.id,
        "physical_column_id" to record.physicalColumnId, "physical_column_version" to record.physicalColumnVersion, "profile_json" to record.profileJson,
        "sample_values_json" to record.sampleValuesJson, "null_rate" to record.nullRate, "distinct_count" to record.distinctCount, "min_value" to record.minValue,
        "max_value" to record.maxValue, "profiled_at" to record.profiledAt, "status" to record.status, "error_message" to record.errorMessage,
        "created_by" to record.createdBy, "created_at" to record.createdAt)
    fun listPhysicalColumnProfiles(physicalColumnId: String): List<PhysicalColumnProfileRecord> {
        requireText(physicalColumnId, "physical_column_profile.physical_column_id")
        return query("SELECT * FROM physical_column_profile WHERE physical_column_id=? ORDER BY physical_column_version DESC,profiled_at DESC", listOf(physicalColumnId), ::profile)
    }

    private fun dataSource(row: ResultSet) = WarehouseDataSourceRecord(row.text("id"), row.text("display_name"), row.text("status"), row.text("created_by"), row.instant("created_at"))
    private fun sourceBinding(row: ResultSet): WarehouseSourceBindingRecord {
        val record = WarehouseSourceBindingRecord(json.readValue(row.text("configuration_json"), SourceConfiguration::class.java), row.text("created_by"), row.instant("created_at"))
        if (record.reference != SourceBindingRef(row.text("data_source_id"), row.text("source_identity_hash")))
            throw ProducerContractException("persisted source binding differs from its content address")
        return record
    }
    private fun snapshot(row: ResultSet) = WarehouseDiscoverySnapshotRecord(row.text("id"), row.text("data_source"), row.text("dialect_id"), row.text("source_identity_hash"),
        row.text("catalog_name"), row.text("schema_name"), row.getString("database_product_name"), row.getString("database_product_version"), row.getString("driver_name"),
        row.getString("driver_version"), row.getObject("read_only", Boolean::class.javaObjectType), row.text("status"), row.requiredInt("table_count"), row.getString("error_message"),
        row.text("requested_by"), row.instant("requested_at"), row.instant("discovered_at"), row.getString("structure_provenance_json")?.let { json.readValue(it, StructureProvenance::class.java) })
    private fun table(row: ResultSet) = WarehouseDiscoveredTableRecord(row.text("id"), row.text("discovery_snapshot_id"), row.text("catalog_name"), row.text("schema_name"), row.text("table_name"),
        row.getString("table_comment"), row.getObject("row_count", Long::class.javaObjectType), row.getString("schema_hash"), json.readValue(row.text("constraints_json"), JdbcTableConstraints::class.java),
        row.getString("registered_physical_table_id"), row.text("status"), row.instant("created_at"))
    private fun column(row: ResultSet) = WarehouseDiscoveredColumnRecord(row.text("id"), row.text("discovered_table_id"), row.text("column_name"), row.requiredInt("jdbc_type"), row.text("type_name"),
        row.getObject("precision_value", Int::class.javaObjectType), row.getObject("scale_value", Int::class.javaObjectType), row.getObject("auto_increment", Boolean::class.javaObjectType),
        row.getObject("generated", Boolean::class.javaObjectType), row.getString("default_value"), row.getString("column_comment"), row.getObject("nullable", Boolean::class.javaObjectType),
        row.getObject("ordinal_position", Int::class.javaObjectType), row.instant("created_at"))
    private fun profile(row: ResultSet) = PhysicalColumnProfileRecord(row.text("id"), row.text("physical_column_id"), row.getObject("physical_column_version", Long::class.javaObjectType)
        ?: throw ProducerContractException("physical_column_profile.physical_column_version is required"), row.text("profile_json"), row.getString("sample_values_json"),
        row.getObject("null_rate", Double::class.javaObjectType), row.getObject("distinct_count", Long::class.javaObjectType), row.getString("min_value"), row.getString("max_value"),
        row.instant("profiled_at"), row.text("status"), row.getString("error_message"), row.text("created_by"), row.instant("created_at"))

    /** 所有名称仅由本类的固定调用给出；字段名和值并列，退出两份 SQL 与索引偏移的手工同步。 */
    private fun save(table: String, id: String, vararg fields: Pair<String, Any?>) {
        requireText(id, "$table.id")
        try {
            database.connection().use { connection ->
                val names = fields.map { it.first }
                val values = fields.map { it.second }
                connection.prepareStatement("UPDATE $table SET ${names.joinToString { "$it=?" }} WHERE id=?").use { statement ->
                    statement.bind(values + id)
                    if (statement.executeUpdate() > 0) return
                }
                connection.prepareStatement("INSERT INTO $table (id,${names.joinToString()}) VALUES (${List(names.size + 1) { "?" }.joinToString()})").use { statement ->
                    statement.bind(listOf(id) + values); statement.executeUpdate()
                }
            }
        } catch (failure: Exception) { throw IllegalStateException("Failed to save $table: $id", failure) }
    }
    private fun insert(message: String, sql: String, vararg values: Any?) {
        try { database.connection().use { connection -> connection.prepareStatement(sql).use { it.bind(values.toList()); it.executeUpdate() } } }
        catch (failure: Exception) { throw ProducerContractException(message, failure) }
    }
    private fun <T> query(sql: String, values: List<Any?>, map: (ResultSet) -> T): List<T> = try {
        database.connection().use { connection -> connection.prepareStatement(sql).use { statement ->
            statement.bind(values)
            statement.executeQuery().use { row -> buildList { while (row.next()) add(map(row)) } }
        } }
    } catch (failure: Exception) { throw IllegalStateException("Failed to query warehouse governance", failure) }
    private fun PreparedStatement.bind(values: List<Any?>) = values.forEachIndexed { index, value ->
        if (value is Instant) setTimestamp(index + 1, Timestamp.from(value)) else setObject(index + 1, value)
    }
    private fun ResultSet.text(column: String): String = getString(column) ?: throw ProducerContractException("persisted $column is required")
    private fun ResultSet.instant(column: String): Instant = getTimestamp(column)?.toInstant() ?: throw ProducerContractException("persisted $column is required")
    private fun ResultSet.requiredInt(column: String): Int = getObject(column, Int::class.javaObjectType) ?: throw ProducerContractException("persisted $column is required")
}
