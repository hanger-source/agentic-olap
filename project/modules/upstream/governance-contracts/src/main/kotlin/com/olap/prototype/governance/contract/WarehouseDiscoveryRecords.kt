package com.olap.prototype.management.warehouse

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.StructureProvenance
import java.time.Instant

data class WarehouseDiscoverySnapshotRecord(val id: String, val dataSource: String, val dialectId: String, val sourceIdentityHash: String,
    val catalogName: String, val schemaName: String, val databaseProductName: String?, val databaseProductVersion: String?,
    val driverName: String?, val driverVersion: String?, val readOnly: Boolean?, val status: String, val tableCount: Int,
    val errorMessage: String?, val requestedBy: String, val requestedAt: Instant, val discoveredAt: Instant, val structureProvenance: StructureProvenance?) {
    init {
        ProducerContracts.requireText(id, "warehouse_discovery_snapshot.id")
        ProducerContracts.requireText(dataSource, "warehouse_discovery_snapshot.data_source")
        ProducerContracts.requireText(dialectId, "warehouse_discovery_snapshot.dialect_id")
        ProducerContracts.requireText(schemaName, "warehouse_discovery_snapshot.schema_name")
        if (!sourceIdentityHash.matches(Regex("[0-9a-f]{64}"))) throw ProducerContractException("warehouse_discovery_snapshot.source_identity_hash is required")
        ProducerContracts.requireText(status, "warehouse_discovery_snapshot.status")
        if (tableCount < 0) throw ProducerContractException("warehouse_discovery_snapshot.table_count is required")
        ProducerContracts.requireText(requestedBy, "warehouse_discovery_snapshot.requested_by")
        if (status != "FAILED") {
            val provenance = structureProvenance ?: throw ProducerContractException("structure provenance is required")
            if (provenance.kind == StructureProvenance.Kind.DATABASE_CATALOG) {
                ProducerContracts.requireText(databaseProductName, "warehouse_discovery_snapshot.database_product_name")
                ProducerContracts.requireText(databaseProductVersion, "warehouse_discovery_snapshot.database_product_version")
                ProducerContracts.requireText(driverName, "warehouse_discovery_snapshot.driver_name")
                ProducerContracts.requireText(driverVersion, "warehouse_discovery_snapshot.driver_version")
                if (readOnly == null) throw ProducerContractException("warehouse_discovery_snapshot.read_only is required")
            }
        }
    }
}

data class WarehouseDiscoveredTableRecord(val id: String, val discoverySnapshotId: String, val catalogName: String, val schemaName: String,
    val tableName: String, val tableComment: String?, val rowCount: Long?, val schemaHash: String?, val constraints: JdbcTableConstraints,
    val registeredPhysicalTableId: String?, val status: String, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "warehouse_discovered_table.id")
        ProducerContracts.requireText(discoverySnapshotId, "warehouse_discovered_table.discovery_snapshot_id")
        ProducerContracts.requireText(schemaName, "warehouse_discovered_table.schema_name")
        ProducerContracts.requireText(tableName, "warehouse_discovered_table.table_name")
        ProducerContracts.requireText(status, "warehouse_discovered_table.status")
    }
}

data class WarehouseDiscoveredColumnRecord(val id: String, val discoveredTableId: String, val columnName: String, val jdbcType: Int,
    val typeName: String, val precision: Int?, val scale: Int?, val autoIncrement: Boolean?, val generated: Boolean?, val defaultValue: String?,
    val columnComment: String?, val nullable: Boolean?, val ordinalPosition: Int?, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "warehouse_discovered_column.id")
        ProducerContracts.requireText(discoveredTableId, "warehouse_discovered_column.discovered_table_id")
        ProducerContracts.requireText(columnName, "warehouse_discovered_column.column_name")
        ProducerContracts.requireText(typeName, "warehouse_discovered_column.type_name")
    }
}

/** 正式调查的子事实，随同一个成功事务保存，没有独立激活生命周期。 */
data class WarehouseDiscoveredUniqueKeyRecord(val id: String, val discoveredTableId: String, val key: SnapshotUniqueKey) {
    init { require(id.isNotBlank() && discoveredTableId.isNotBlank()) { "discovered unique key requires exact identities" } }
}

