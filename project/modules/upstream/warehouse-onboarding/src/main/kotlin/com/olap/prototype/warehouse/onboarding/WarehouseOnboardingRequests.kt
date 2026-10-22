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

/** 源生产者的精确声明文本，仅部署授权入口可调用。 */
data class SourceDatasetRegistrationRequest(val dataSourceId: String, val sourceIdentityHash: String, val declarationJson: String, val sha256: String) {
    init {
        SourceBindingRef(dataSourceId, sourceIdentityHash)
        require(dataSourceId.isNotBlank() && declarationJson.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}"))) {
            "exact source declaration text, hash and deployment are required"
        }
    }
}

/** 使用方提供的结构和出处；结构声明不等于约束已经执行核验。 */
class SourceStructureRegistrationRequest(val dataSourceId: String, val sourceIdentityHash: String, val catalog: String?, val schemaName: String,
    val authority: String, val revision: String, tables: List<Table>, val requestedBy: String) {
    val tables: List<Table> = java.util.List.copyOf(tables)
    init {
        SourceBindingRef(dataSourceId, sourceIdentityHash)
        require(listOf(dataSourceId, schemaName, authority, revision, requestedBy).none(String::isBlank)) { "source structure identity and provenance are required" }
        require(this.tables.size in 1..1000 && this.tables.map { it.table.ref }.distinct().size == this.tables.size) { "source structure requires bounded distinct tables" }
    }
    class Table(val table: WarehouseTableSummary, columns: List<WarehouseColumnMetadata>, val constraints: JdbcTableConstraints) {
        val columns: List<WarehouseColumnMetadata> = java.util.List.copyOf(columns)
        init {
            require(this.columns.size in 1..10000 && this.columns.map { it.name }.distinct().size == this.columns.size &&
                this.columns.map { it.ordinalPosition }.distinct().size == this.columns.size) { "source table requires bounded distinct columns" }
            this.columns.forEach {
                val ordinal = it.ordinalPosition
                require(it.name.isNotBlank() && ordinal != null && ordinal >= 1 && it.nullable != null) {
                    "source column name, ordinal position and nullability are required"
                }
                JdbcColumnType(requireNotNull(it.jdbcType) { "source column JDBC type is required" },
                    requireNotNull(it.typeName) { "source column native type name is required" }, it.precision, it.scale, it.nullable)
            }
            val byName = this.columns.associateBy { it.name }
            val references = buildSet {
                addAll(constraints.primaryKeyColumns)
                constraints.uniqueKeys.forEach { addAll(it.columns) }
                constraints.importedKeys.forEach { add(it.column) }
                constraints.temporalKeys.forEach { addAll(it.rule.columns()) }
            }
            require(byName.keys.containsAll(references)) { "declared constraints must reference declared columns" }
            require(constraints.primaryKeyColumns.none { byName.getValue(it).nullable == true }) { "declared primary key columns must be explicitly non-nullable" }
        }
        override fun equals(other: Any?): Boolean = other is Table && table == other.table && columns == other.columns && constraints == other.constraints
        override fun hashCode(): Int = listOf(table, columns, constraints).hashCode()
    }
    override fun equals(other: Any?): Boolean = other is SourceStructureRegistrationRequest && dataSourceId == other.dataSourceId && sourceIdentityHash == other.sourceIdentityHash && catalog == other.catalog && schemaName == other.schemaName && authority == other.authority && revision == other.revision && tables == other.tables && requestedBy == other.requestedBy
    override fun hashCode(): Int = listOf(dataSourceId, sourceIdentityHash, catalog, schemaName, authority, revision, tables, requestedBy).hashCode()
}

class WarehouseDiscoveryCommand(dataSourceId: String, val sourceIdentityHash: String, catalog: String?, schemaName: String, tablePattern: String?,
    tableTypes: List<String>, val limit: Int?, temporalKeys: Map<String, List<JdbcTableConstraints.TemporalRule>>,
    snapshotUniqueKeys: Map<String, List<SnapshotUniqueKey.Rule>>, requestedBy: String) {
    val dataSourceId = required(dataSourceId, "dataSourceId")
    val catalog = catalog?.trim()?.takeUnless(String::isBlank)
    val schemaName = required(schemaName, "schemaName")
    val tablePattern = tablePattern?.trim()?.takeUnless(String::isBlank)
    val tableTypes: List<String> = java.util.List.copyOf(tableTypes)
    val temporalKeys: Map<String, List<JdbcTableConstraints.TemporalRule>> = java.util.Map.copyOf(temporalKeys.mapValues { (table, rules) ->
        require(table.isNotBlank() && rules.isNotEmpty()) { "temporal key requests require exact tables and nonempty rules" }; java.util.List.copyOf(rules)
    })
    val snapshotUniqueKeys: Map<String, List<SnapshotUniqueKey.Rule>> = java.util.Map.copyOf(snapshotUniqueKeys.mapValues { (table, rules) ->
        require(table.isNotBlank() && rules.size in 1..16 && rules.map { it.name }.distinct().size == rules.size) { "snapshot unique requests require exact tables and distinct bounded rules" }
        java.util.List.copyOf(rules)
    })
    val requestedBy = required(requestedBy, "requestedBy")
    init { SourceBindingRef(dataSourceId, sourceIdentityHash) }
    override fun equals(other: Any?): Boolean = other is WarehouseDiscoveryCommand && dataSourceId == other.dataSourceId && sourceIdentityHash == other.sourceIdentityHash && catalog == other.catalog && schemaName == other.schemaName && tablePattern == other.tablePattern && tableTypes == other.tableTypes && limit == other.limit && temporalKeys == other.temporalKeys && snapshotUniqueKeys == other.snapshotUniqueKeys && requestedBy == other.requestedBy
    override fun hashCode(): Int = listOf(dataSourceId, sourceIdentityHash, catalog, schemaName, tablePattern, tableTypes, limit, temporalKeys, snapshotUniqueKeys, requestedBy).hashCode()
}

internal fun required(value: String?, field: String): String = value?.trim()?.takeUnless(String::isBlank)
    ?: throw ProducerContractException("$field is required")
