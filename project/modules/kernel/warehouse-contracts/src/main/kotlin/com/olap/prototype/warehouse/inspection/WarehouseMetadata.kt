package com.olap.prototype.warehouse.inspection

import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.StructureProvenance

data class WarehouseColumnMetadata(
    val name: String,
    val jdbcType: Int?,
    val typeName: String?,
    val ordinalPosition: Int?,
    val nullable: Boolean?,
    val precision: Int?,
    val scale: Int?,
    val autoIncrement: Boolean?,
    val generated: Boolean?,
    val defaultValue: String?,
    val remarks: String?,
)

data class WarehouseSourceMetadata(
    val dataSourceId: String,
    val dialectId: String,
    val sourceIdentityHash: String,
    val databaseProductName: String?,
    val databaseProductVersion: String?,
    val driverName: String?,
    val driverVersion: String?,
    val userName: String?,
    val readOnly: Boolean?,
    val structureProvenance: StructureProvenance,
)

data class WarehouseTableSummary(val ref: WarehouseTableRef, val tableType: String?, val remarks: String?)

class WarehouseTableMetadata(val source: WarehouseSourceMetadata, val table: WarehouseTableSummary, columns: List<WarehouseColumnMetadata>, val constraints: JdbcTableConstraints) {
    val columns: List<WarehouseColumnMetadata> = java.util.List.copyOf(columns)
    override fun equals(other: Any?): Boolean = other is WarehouseTableMetadata && source == other.source && table == other.table && columns == other.columns && constraints == other.constraints
    override fun hashCode(): Int = listOf(source, table, columns, constraints).hashCode()
}

class WarehouseSchemaSnapshot(val source: WarehouseSourceMetadata, val truncated: Boolean, tables: List<WarehouseTableMetadata>) {
    val tables: List<WarehouseTableMetadata> = java.util.List.copyOf(tables)
    override fun equals(other: Any?): Boolean = other is WarehouseSchemaSnapshot && source == other.source && truncated == other.truncated && tables == other.tables
    override fun hashCode(): Int = listOf(source, truncated, tables).hashCode()
}

class WarehouseDiscoveryResult(val source: WarehouseSourceMetadata, val returnedTableCount: Int, val truncated: Boolean, tables: List<WarehouseTableSummary>) {
    val tables: List<WarehouseTableSummary> = java.util.List.copyOf(tables)
    override fun equals(other: Any?): Boolean = other is WarehouseDiscoveryResult && source == other.source && returnedTableCount == other.returnedTableCount && truncated == other.truncated && tables == other.tables
    override fun hashCode(): Int = listOf(source, returnedTableCount, truncated, tables).hashCode()
}
