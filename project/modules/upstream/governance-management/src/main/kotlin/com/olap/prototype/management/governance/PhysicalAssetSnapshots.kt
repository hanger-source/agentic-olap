package com.olap.prototype.management.governance

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.governance.contract.*
import com.olap.prototype.warehouse.contract.PhysicalDataContract
import com.olap.prototype.warehouse.contract.PhysicalTableRef

internal class PhysicalAssetSnapshots(private val reads: GovernanceSnapshotReader) {
    fun physicalTable(id: String, version: Long): PhysicalTableRecord {
        val snapshot = reads.versionSnapshot("physical_table_version", "physical_table_id", id, version)
        val record = snapshot.content<GovernanceDefinition.PhysicalTable>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun physicalColumn(id: String, version: Long): PhysicalColumnRecord {
        val snapshot = reads.versionSnapshot("physical_column_version", "physical_column_id", id, version)
        val record = snapshot.content<GovernanceDefinition.PhysicalColumn>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedAt = snapshot.activatedAt ?: snapshot.createdAt)
    }
    fun physicalTableColumns(id: String, version: Long): List<PhysicalColumnRecord> = reads.physicalTableContent(id, version).columnVersionRefs
        .map { physicalColumn(it.id, it.version) }.sortedWith(compareBy(PhysicalColumnRecord::ordinalPosition, PhysicalColumnRecord::columnName))
    companion object {
        fun physicalColumnSnapshotProto(column: PhysicalColumnRecord, sensitivityLevel: String?): String =
            encode(GovernanceDefinition.PhysicalColumn(column, normalized(sensitivityLevel)))
        fun tableContent(table: PhysicalTableRecord, request: PhysicalTableCreateRequest, columns: List<PhysicalColumnRecord>, contract: PhysicalDataContract): String =
            encode(GovernanceDefinition.PhysicalTable(table, references(columns), contract, normalized(request.dataDomain), normalized(request.sensitivityLevel), normalized(request.sourceSystem)))
        fun revisedTableContent(table: PhysicalTableRecord, previous: GovernanceDefinition.PhysicalTable, columns: List<PhysicalColumnRecord>): String {
            val prior = previous.dataContract
            val contract = PhysicalDataContract(PhysicalTableRef(table.id, table.currentVersion), prior.dataSourceId, prior.table,
                columns.associate { it.columnName to PhysicalDataContract.Column("${it.id}@${it.currentVersion}", it.jdbcColumnType) },
                prior.constraints, prior.snapshotUniqueKeys, prior.sourceCollections, prior.originDiscoveredTableId)
            return encode(GovernanceDefinition.PhysicalTable(table, references(columns), contract, previous.dataDomain, previous.sensitivityLevel, previous.sourceSystem))
        }
        private fun references(columns: List<PhysicalColumnRecord>): List<GovernanceDefinition.VersionRef> = columns.map { GovernanceDefinition.VersionRef(it.id, it.currentVersion) }
        private fun normalized(value: String?): String? = value?.takeUnless(String::isBlank)?.trim()
        private fun encode(value: GovernanceDefinition): String = ProtoBytes.storage(GovernanceProto.definition(value))
    }
}
