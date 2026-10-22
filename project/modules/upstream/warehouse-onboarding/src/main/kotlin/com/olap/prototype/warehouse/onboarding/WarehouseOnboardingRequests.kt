package com.olap.prototype.warehouse.onboarding

import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.PhysicalTableCreateResult
import com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord
import com.olap.prototype.management.warehouse.WarehouseDiscoveredTableRecord
import com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.inspection.WarehouseColumnMetadata
import com.olap.prototype.warehouse.inspection.WarehouseSourceMetadata
import com.olap.prototype.warehouse.inspection.WarehouseTableSummary

class DiscoveredPhysicalTableRegistrationRequest(val discoveredTableId: String, val code: String, val displayName: String, val description: String?,
    val dataDomain: String, val owner: String, val sensitivityLevel: String, val sourceSystem: String, val createdBy: String, columns: List<ColumnGovernance>) {
    val columns: List<ColumnGovernance> = java.util.List.copyOf(columns)
    data class ColumnGovernance(val columnName: String, val displayName: String, val sensitivityLevel: String, val description: String?)
    override fun equals(other: Any?): Boolean = other is DiscoveredPhysicalTableRegistrationRequest && discoveredTableId == other.discoveredTableId && code == other.code && displayName == other.displayName && description == other.description && dataDomain == other.dataDomain && owner == other.owner && sensitivityLevel == other.sensitivityLevel && sourceSystem == other.sourceSystem && createdBy == other.createdBy && columns == other.columns
    override fun hashCode(): Int = listOf(discoveredTableId, code, displayName, description, dataDomain, owner, sensitivityLevel, sourceSystem, createdBy, columns).hashCode()
}
class DiscoveredTablePreviewRequest(discoveredTableId: String, val limit: Int?) {
    val discoveredTableId = required(discoveredTableId, "discoveredTableId")
    init { require(limit == null || limit in 1..200) { "limit 必须在 1 到 200 之间" } }
    @get:JsonIgnore val effectiveLimit: Int get() = limit ?: 20
    override fun equals(other: Any?): Boolean = other is DiscoveredTablePreviewRequest && discoveredTableId == other.discoveredTableId && limit == other.limit
    override fun hashCode(): Int = listOf(discoveredTableId, limit).hashCode()
}
class RecordedWarehouseDiscovery(val snapshot: WarehouseDiscoverySnapshotRecord, val source: WarehouseSourceMetadata?, val truncated: Boolean,
    tables: List<WarehouseDiscoveredTableRecord>, columns: List<WarehouseDiscoveredColumnRecord>) {
    val tables: List<WarehouseDiscoveredTableRecord> = java.util.List.copyOf(tables)
    val columns: List<WarehouseDiscoveredColumnRecord> = java.util.List.copyOf(columns)
    override fun equals(other: Any?): Boolean = other is RecordedWarehouseDiscovery && snapshot == other.snapshot && source == other.source && truncated == other.truncated && tables == other.tables && columns == other.columns
    override fun hashCode(): Int = listOf(snapshot, source, truncated, tables, columns).hashCode()
}
data class RegisteredPhysicalTable(val physicalTable: PhysicalTableCreateResult, val discoverySource: WarehouseDiscoveredTableRecord)

private fun required(value: String?, field: String): String = value?.trim()?.takeUnless(String::isBlank)
    ?: throw ProducerContractException("$field is required")
