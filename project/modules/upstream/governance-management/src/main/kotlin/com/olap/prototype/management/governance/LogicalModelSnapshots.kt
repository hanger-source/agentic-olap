package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*

internal class LogicalModelSnapshots(private val reads: GovernanceSnapshotReader) {
    fun logicalTable(id: String, version: Long): LogicalTableRecord {
        val snapshot = reads.versionSnapshot("logical_table_version", "logical_table_id", id, version)
        val record = snapshot.content<GovernanceDefinition.LogicalTable>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun logicUnit(id: String, version: Long): LogicUnitRecord {
        val snapshot = reads.versionSnapshot("logic_unit_version", "logic_unit_id", id, version)
        val record = snapshot.content<GovernanceDefinition.LogicUnit>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    companion object {
        fun logicUnitVersionContent(unit: LogicUnitRecord, sourceRefs: List<LogicalTableCreateRequest.SourceRefInput>): GovernanceDefinition.LogicUnit =
            GovernanceDefinition.LogicUnit(unit, sourceRefs)
        fun logicalTableVersionContent(table: LogicalTableRecord, physicalTableId: String, physicalTableVersion: Long, logicUnitCount: Int): GovernanceDefinition.LogicalTable =
            GovernanceDefinition.LogicalTable(table, physicalTableId, physicalTableVersion, logicUnitCount)
    }
}
