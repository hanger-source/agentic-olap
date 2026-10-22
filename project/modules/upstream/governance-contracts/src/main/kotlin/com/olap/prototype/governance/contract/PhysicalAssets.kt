package com.olap.prototype.governance.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import java.time.Instant

data class PhysicalTableRecord(val id: String, val code: String, val displayName: String, val dataSource: String, val dialectId: String, val catalogName: String?, val schemaName: String?, val tableName: String, val description: String?, val owner: String, val loadStatus: String, val rowCount: Long?, val schemaHash: String?, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class PhysicalTableVersionRecord(val id: String, val physicalTableId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class PhysicalColumnRecord(val id: String, val physicalTableId: String, val columnName: String, val displayName: String, val dataType: String, val description: String?, val nullable: Boolean, val ordinalPosition: Int, val enabled: Boolean, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedAt: Instant?, val jdbcColumnType: JdbcColumnType) {
    init { if (nullable != jdbcColumnType.nullable) throw ProducerContractException("physical_column nullable differs from JDBC column type") }
}
data class PhysicalColumnVersionRecord(val id: String, val physicalColumnId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class PhysicalLogicalTableRefRecord(val id: String, val physicalTableId: String, val physicalTableVersion: Long, val logicalTableId: String, val logicalTableVersion: Long, val refRole: String, val createdAt: Instant)

class PhysicalTableCreateRequest(
    val code: String, val tableName: String, val displayName: String, val description: String?,
    val dataDomain: String?, val owner: String, val sensitivityLevel: String?, val sourceSystem: String?,
    val dataSource: String, val dialectId: String, val catalog: String?, val schemaName: String?,
    @param:JsonProperty(required = true) val schemaHash: String,
    @param:JsonProperty(required = true) val sourceConstraints: SourceConstraintsInput,
    val createdBy: String, columns: List<ColumnInput>
) {
    val columns: List<ColumnInput> = java.util.List.copyOf(columns)
    class SourceConstraintsInput(
        @param:JsonProperty(required = true) val discoveredTableId: String,
        @param:JsonProperty(required = true) sourceCollectionIds: List<String>
    ) {
        val sourceCollectionIds: List<String> = java.util.List.copyOf(sourceCollectionIds)
        init {
            require(discoveredTableId.isNotBlank()) { "source constraints require an exact discovered table identity" }
            require(this.sourceCollectionIds.size <= 64 && this.sourceCollectionIds.distinct().size == this.sourceCollectionIds.size && this.sourceCollectionIds.none { it.isBlank() }) { "source collections must be explicit distinct exact identities" }
        }
        override fun equals(other: Any?): Boolean = other is SourceConstraintsInput && discoveredTableId == other.discoveredTableId && sourceCollectionIds == other.sourceCollectionIds
        override fun hashCode(): Int = 31 * discoveredTableId.hashCode() + sourceCollectionIds.hashCode()
    }
    data class ColumnInput(val columnName: String, val displayName: String, val columnType: String, val sensitivityLevel: String?, val nullable: Boolean, val description: String?, val jdbcColumnType: JdbcColumnType)
    override fun equals(other: Any?): Boolean = other is PhysicalTableCreateRequest && code == other.code && tableName == other.tableName && displayName == other.displayName && description == other.description && dataDomain == other.dataDomain && owner == other.owner && sensitivityLevel == other.sensitivityLevel && sourceSystem == other.sourceSystem && dataSource == other.dataSource && dialectId == other.dialectId && catalog == other.catalog && schemaName == other.schemaName && schemaHash == other.schemaHash && sourceConstraints == other.sourceConstraints && createdBy == other.createdBy && columns == other.columns
    override fun hashCode(): Int = listOf(code, tableName, displayName, description, dataDomain, owner, sensitivityLevel, sourceSystem, dataSource, dialectId, catalog, schemaName, schemaHash, sourceConstraints, createdBy, columns).hashCode()
}
class PhysicalTableCreateResult(val physicalTable: PhysicalTableRecord, val physicalTableVersion: PhysicalTableVersionRecord, physicalColumns: List<PhysicalColumnRecord>, physicalColumnVersions: List<PhysicalColumnVersionRecord>) {
    val physicalColumns: List<PhysicalColumnRecord> = java.util.List.copyOf(physicalColumns)
    val physicalColumnVersions: List<PhysicalColumnVersionRecord> = java.util.List.copyOf(physicalColumnVersions)
    override fun equals(other: Any?): Boolean = other is PhysicalTableCreateResult && physicalTable == other.physicalTable && physicalTableVersion == other.physicalTableVersion && physicalColumns == other.physicalColumns && physicalColumnVersions == other.physicalColumnVersions
    override fun hashCode(): Int = listOf(physicalTable, physicalTableVersion, physicalColumns, physicalColumnVersions).hashCode()
}
class PhysicalTableColumnsUpdateRequest(val physicalTableId: String, val expectedVersion: Long, val updatedBy: String, columns: List<ColumnInput>) {
    val columns: List<ColumnInput> = java.util.List.copyOf(columns)
    data class ColumnInput(val columnName: String, val displayName: String, val columnType: String, val description: String?, val nullable: Boolean, val jdbcColumnType: JdbcColumnType)
    override fun equals(other: Any?): Boolean = other is PhysicalTableColumnsUpdateRequest && physicalTableId == other.physicalTableId && expectedVersion == other.expectedVersion && updatedBy == other.updatedBy && columns == other.columns
    override fun hashCode(): Int = listOf(physicalTableId, expectedVersion, updatedBy, columns).hashCode()
}
class PhysicalTableColumnsUpdateResult(val physicalTable: PhysicalTableRecord, val physicalTableVersion: PhysicalTableVersionRecord, physicalColumns: List<PhysicalColumnRecord>, physicalColumnVersions: List<PhysicalColumnVersionRecord>) {
    val physicalColumns: List<PhysicalColumnRecord> = java.util.List.copyOf(physicalColumns)
    val physicalColumnVersions: List<PhysicalColumnVersionRecord> = java.util.List.copyOf(physicalColumnVersions)
    override fun equals(other: Any?): Boolean = other is PhysicalTableColumnsUpdateResult && physicalTable == other.physicalTable && physicalTableVersion == other.physicalTableVersion && physicalColumns == other.physicalColumns && physicalColumnVersions == other.physicalColumnVersions
    override fun hashCode(): Int = listOf(physicalTable, physicalTableVersion, physicalColumns, physicalColumnVersions).hashCode()
}
data class PhysicalTableMetadataUpdateRequest(val physicalTableId: String, val expectedVersion: Long, val displayName: String, val description: String?, val owner: String, val updatedBy: String)
data class PhysicalTableMetadataUpdateResult(val physicalTable: PhysicalTableRecord, val physicalTableVersion: PhysicalTableVersionRecord)
