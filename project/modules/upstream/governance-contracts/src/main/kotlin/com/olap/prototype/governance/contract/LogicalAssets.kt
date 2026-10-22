package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.SetOutputContract
import java.time.Instant

data class LogicUnitRecord(val id: String, val logicalTableId: String, val code: String, val displayName: String, val sourceKind: String, val expressionJson: String?, val logicType: String?, val valueShape: SetOutputContract.Shape?, val isTime: Boolean, val isPrimaryKey: Boolean, val isForeignKey: Boolean, val filterable: Boolean, val aggregatable: Boolean, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?) {
    init {
        requireValueType(logicType, valueShape)
        if (valueShape != null && (isTime || isPrimaryKey || isForeignKey || filterable))
            throw ProducerContractException("container LogicUnit cannot be a scalar time, key or filter field")
    }
    companion object {
        @JvmStatic fun requireValueType(logicType: String?, valueShape: SetOutputContract.Shape?) {
            if ((logicType == null) == (valueShape == null)) throw ProducerContractException("LogicUnit requires either a scalar logicType or a container valueShape")
            if (valueShape != null) SetOutputContract.requireSourceContainer(valueShape)
        }
    }
}
data class LogicUnitSourceRefRecord(val id: String, val logicUnitId: String, val logicUnitVersion: Long, val physicalColumnId: String, val physicalColumnVersion: Long, val refRole: String, val createdAt: Instant)
data class LogicUnitVersionRecord(val id: String, val logicUnitId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class LogicalTableRecord(val id: String, val code: String, val displayName: String, val tableRole: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class LogicalTableVersionRecord(val id: String, val logicalTableId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class LogicalTableLogicUnitRefRecord(val id: String, val logicalTableId: String, val logicalTableVersion: Long, val logicUnitId: String, val logicUnitVersion: Long, val createdAt: Instant)
class LogicalTableCreateRequest(val physicalTableId: String, val physicalTableVersion: Long, val code: String, val displayName: String, val tableRole: LogicalTableRole, val description: String?, val owner: String, val createdBy: String, logicUnits: List<LogicUnitInput>) {
    val logicUnits: List<LogicUnitInput> = java.util.List.copyOf(logicUnits)
    class LogicUnitInput(val code: String, val displayName: String, val sourceKind: LogicUnitSourceKind, val expressionJson: String?, val logicType: LogicType?, val valueShape: SetOutputContract.Shape?, val isTime: Boolean?, val isPrimaryKey: Boolean?, val isForeignKey: Boolean?, val filterable: Boolean?, val aggregatable: Boolean?, sourceRefs: List<SourceRefInput>?) {
        val sourceRefs: List<SourceRefInput> = java.util.List.copyOf(sourceRefs ?: emptyList())
        init {
            LogicUnitRecord.requireValueType(logicType?.name, valueShape)
            if (sourceKind == LogicUnitSourceKind.EXPRESSION && this.sourceRefs.isNotEmpty()) throw ProducerContractException("EXPRESSION sourceRefs are derived from the exact expression graph and must not be supplied")
        }
        override fun equals(other: Any?): Boolean = other is LogicUnitInput && code == other.code && displayName == other.displayName && sourceKind == other.sourceKind && expressionJson == other.expressionJson && logicType == other.logicType && valueShape == other.valueShape && isTime == other.isTime && isPrimaryKey == other.isPrimaryKey && isForeignKey == other.isForeignKey && filterable == other.filterable && aggregatable == other.aggregatable && sourceRefs == other.sourceRefs
        override fun hashCode(): Int = listOf(code, displayName, sourceKind, expressionJson, logicType, valueShape, isTime, isPrimaryKey, isForeignKey, filterable, aggregatable, sourceRefs).hashCode()
    }
    data class SourceRefInput(val physicalColumnId: String, val physicalColumnVersion: Long, val refRole: LogicUnitSourceRefRole)
    override fun equals(other: Any?): Boolean = other is LogicalTableCreateRequest && physicalTableId == other.physicalTableId && physicalTableVersion == other.physicalTableVersion && code == other.code && displayName == other.displayName && tableRole == other.tableRole && description == other.description && owner == other.owner && createdBy == other.createdBy && logicUnits == other.logicUnits
    override fun hashCode(): Int = listOf(physicalTableId, physicalTableVersion, code, displayName, tableRole, description, owner, createdBy, logicUnits).hashCode()
}
class LogicalTableCreateResult(val logicalTable: LogicalTableRecord, val logicalTableVersion: LogicalTableVersionRecord, val physicalTableRef: PhysicalLogicalTableRefRecord, logicUnits: List<LogicUnitRecord>, logicUnitVersions: List<LogicUnitVersionRecord>, logicUnitRefs: List<LogicalTableLogicUnitRefRecord>, sourceRefs: List<LogicUnitSourceRefRecord>) {
    val logicUnits: List<LogicUnitRecord> = java.util.List.copyOf(logicUnits)
    val logicUnitVersions: List<LogicUnitVersionRecord> = java.util.List.copyOf(logicUnitVersions)
    val logicUnitRefs: List<LogicalTableLogicUnitRefRecord> = java.util.List.copyOf(logicUnitRefs)
    val sourceRefs: List<LogicUnitSourceRefRecord> = java.util.List.copyOf(sourceRefs)
    override fun equals(other: Any?): Boolean = other is LogicalTableCreateResult && logicalTable == other.logicalTable && logicalTableVersion == other.logicalTableVersion && physicalTableRef == other.physicalTableRef && logicUnits == other.logicUnits && logicUnitVersions == other.logicUnitVersions && logicUnitRefs == other.logicUnitRefs && sourceRefs == other.sourceRefs
    override fun hashCode(): Int = listOf(logicalTable, logicalTableVersion, physicalTableRef, logicUnits, logicUnitVersions, logicUnitRefs, sourceRefs).hashCode()
}
data class ReachableLogicUnitCandidateRecord(val id: String, val logicalTableId: String, val logicalTableDisplayName: String, val code: String, val displayName: String, val sourceKind: String, val expressionJson: String?, val logicType: String?, val valueShape: SetOutputContract.Shape?, val logicTypeDisplayName: String?, val isTime: Boolean, val isPrimaryKey: Boolean, val isForeignKey: Boolean, val filterable: Boolean, val aggregatable: Boolean, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
