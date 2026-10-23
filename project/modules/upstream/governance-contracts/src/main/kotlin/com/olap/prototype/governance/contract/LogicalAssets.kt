package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.query.SetOutputContract
import java.time.Instant
import com.olap.prototype.expression.SemanticExpression

data class LogicUnitRecord(val id: String, val logicalTableId: String, val code: String, val displayName: String, val sourceKind: String, val expression: SemanticExpression?, val logicType: String?, val valueShape: SetOutputContract.Container?, val isTime: Boolean, val isPrimaryKey: Boolean, val isForeignKey: Boolean, val filterable: Boolean, val aggregatable: Boolean, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?) {
    init {
        requireValueType(logicType, valueShape)
        requireContainerFlags(valueShape, isTime, isPrimaryKey, isForeignKey, filterable)
    }
    companion object {
        @JvmStatic fun requireValueType(logicType: String?, valueShape: SetOutputContract.Container?) {
            if ((logicType == null) == (valueShape == null)) throw InputContractException.violation(
                InputContractException.Rule.LOGIC_UNIT_VALUE_KIND, emptyList(),
                "LogicUnit requires exactly one scalar logicType or container valueShape",
                "SCALAR_LOGIC_TYPE_XOR_CONTAINER_VALUE_SHAPE", if (logicType == null) "NEITHER_PRESENT" else "BOTH_PRESENT")
            if (valueShape != null) SetOutputContract.requireSourceContainer(valueShape, listOf("valueShape"))
        }
        internal fun requireContainerFlags(valueShape: SetOutputContract.Container?, isTime: Boolean?, isPrimaryKey: Boolean?, isForeignKey: Boolean?, filterable: Boolean?) {
            if (valueShape == null) return
            mapOf("isTime" to isTime, "isPrimaryKey" to isPrimaryKey, "isForeignKey" to isForeignKey, "filterable" to filterable).forEach { (field, value) ->
                if (value == true) throw InputContractException.violation(InputContractException.Rule.LOGIC_UNIT_CONTAINER_FLAG,
                    listOf(field), "container LogicUnit cannot be a scalar time, key or filter field", "FALSE", "TRUE")
            }
        }
        fun contract(): Map<String, Any> = mapOf("valueTypeRules" to mapOf(
            "selection" to "EXACTLY_ONE",
            "fields" to listOf("logicType", "valueShape"),
            "scalar" to mapOf("logicType" to "NON_NULL_SCALAR_LOGIC_TYPE", "valueShape" to "NULL",
                "numericColumn" to "NUMBER_WITHOUT_COLUMN_QUANTITY_CONTRACT"),
            "container" to mapOf("logicType" to "NULL", "valueShape" to listOf("ARRAY", "ROW"),
                "falseFlags" to listOf("isTime", "isPrimaryKey", "isForeignKey", "filterable"),
                "shapeRules" to SetOutputContract.Container.contract()),
            "violationCodes" to mapOf("selection" to InputContractException.Rule.LOGIC_UNIT_VALUE_KIND.code,
                "containerFlag" to InputContractException.Rule.LOGIC_UNIT_CONTAINER_FLAG.code)))
    }
}
data class LogicUnitSourceRefRecord(val id: String, val logicUnitId: String, val logicUnitVersion: Long, val physicalColumnId: String, val physicalColumnVersion: Long, val refRole: String, val createdAt: Instant)
data class LogicUnitVersionRecord(val id: String, val logicUnitId: String, val version: Long, val versionStatus: String, val contentProto: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class LogicalTableRecord(val id: String, val code: String, val displayName: String, val tableRole: String, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
data class LogicalTableVersionRecord(val id: String, val logicalTableId: String, val version: Long, val versionStatus: String, val contentProto: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class LogicalTableLogicUnitRefRecord(val id: String, val logicalTableId: String, val logicalTableVersion: Long, val logicUnitId: String, val logicUnitVersion: Long, val createdAt: Instant)
class LogicalTableCreateRequest(val physicalTableId: String, val physicalTableVersion: Long, val code: String, val displayName: String, val tableRole: LogicalTableRole, val description: String?, val owner: String, val createdBy: String, logicUnits: List<LogicUnitInput>) {
    val logicUnits: List<LogicUnitInput> = java.util.List.copyOf(logicUnits)
    class LogicUnitInput(val code: String, val displayName: String, val sourceKind: LogicUnitSourceKind, val expression: SemanticExpression?, val logicType: LogicType?, val valueShape: SetOutputContract.Container?, val isTime: Boolean?, val isPrimaryKey: Boolean?, val isForeignKey: Boolean?, val filterable: Boolean?, val aggregatable: Boolean?, sourceRefs: List<SourceRefInput>?) {
        val sourceRefs: List<SourceRefInput> = java.util.List.copyOf(sourceRefs ?: emptyList())
        init {
            LogicUnitRecord.requireValueType(logicType?.name, valueShape)
            LogicUnitRecord.requireContainerFlags(valueShape, isTime, isPrimaryKey, isForeignKey, filterable)
            if (sourceKind == LogicUnitSourceKind.EXPRESSION && this.sourceRefs.isNotEmpty()) throw ProducerContractException("EXPRESSION sourceRefs are derived from the exact expression graph and must not be supplied")
        }
        override fun equals(other: Any?): Boolean = other is LogicUnitInput && code == other.code && displayName == other.displayName && sourceKind == other.sourceKind && expression == other.expression && logicType == other.logicType && valueShape == other.valueShape && isTime == other.isTime && isPrimaryKey == other.isPrimaryKey && isForeignKey == other.isForeignKey && filterable == other.filterable && aggregatable == other.aggregatable && sourceRefs == other.sourceRefs
        override fun hashCode(): Int = listOf(code, displayName, sourceKind, expression, logicType, valueShape, isTime, isPrimaryKey, isForeignKey, filterable, aggregatable, sourceRefs).hashCode()
        companion object { fun contract(): Map<String, Any> = LogicUnitRecord.contract() }
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
data class ReachableLogicUnitCandidateRecord(val id: String, val logicalTableId: String, val logicalTableDisplayName: String, val code: String, val displayName: String, val sourceKind: String, val expression: SemanticExpression?, val logicType: String?, val valueShape: SetOutputContract.Container?, val logicTypeDisplayName: String?, val isTime: Boolean, val isPrimaryKey: Boolean, val isForeignKey: Boolean, val filterable: Boolean, val aggregatable: Boolean, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?)
