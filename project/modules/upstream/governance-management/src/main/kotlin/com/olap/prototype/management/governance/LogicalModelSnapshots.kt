package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredBoolean
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.util.OlapJsonUtils

internal class LogicalModelSnapshots(private val reads: GovernanceSnapshotReader) {
    fun logicalTable(id: String, version: Long): LogicalTableRecord = decode {
        val snapshot = reads.versionSnapshot("logical_table_version", "logical_table_id", id, version)
        val content = snapshot.content; val field = "logical_table_version.content_json"
        val identity = requiredText(content, "logicalTableId", field); requireMatchingId(id, identity, "logical_table")
        LogicalTableRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), requiredText(content, "tableRole", field), optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    fun logicUnit(id: String, version: Long): LogicUnitRecord = decode {
        val snapshot = reads.versionSnapshot("logic_unit_version", "logic_unit_id", id, version)
        val content = snapshot.content; val field = "logic_unit_version.content_json"
        val identity = requiredText(content, "logicUnitId", field); requireMatchingId(id, identity, "logic_unit")
        val shape = if (content.hasNonNull("valueShape")) OlapJsonUtils.mapper().treeToValue(content.get("valueShape"), SetOutputContract.Shape::class.java) else null
        LogicUnitRecord(identity, requiredText(content, "logicalTableId", field), requiredText(content, "code", field), requiredText(content, "displayName", field), requiredText(content, "sourceKind", field), optionalText(content, "expressionJson"), optionalText(content, "logicType"), shape, requiredBoolean(content, "isTime", field), requiredBoolean(content, "isPrimaryKey", field), requiredBoolean(content, "isForeignKey", field), requiredBoolean(content, "filterable", field), requiredBoolean(content, "aggregatable", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    private fun <T> decode(action: () -> T): T = try { action() }
        catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("exact aggregate snapshot decoding failed", failure) }
    companion object {
        fun logicUnitVersionContent(unit: LogicUnitRecord, sourceRefs: List<LogicalTableCreateRequest.SourceRefInput>): Map<String, Any?> {
            val shape = unit.valueShape?.let {
                try { OlapJsonUtils.mapper().readTree(OlapJsonUtils.mapper().writerFor(SetOutputContract.Shape::class.java).writeValueAsString(it)) }
                catch (failure: java.io.IOException) { throw ProducerContractException("LogicUnit valueShape cannot be encoded", failure) }
            }
            return linkedMapOf("logicUnitId" to unit.id, "logicalTableId" to unit.logicalTableId, "code" to unit.code, "displayName" to unit.displayName, "sourceKind" to unit.sourceKind, "expressionJson" to unit.expressionJson, "logicType" to unit.logicType, "valueShape" to shape, "isTime" to unit.isTime, "isPrimaryKey" to unit.isPrimaryKey, "isForeignKey" to unit.isForeignKey, "filterable" to unit.filterable, "aggregatable" to unit.aggregatable, "status" to unit.status, "sourceRefs" to sourceRefs)
        }
        fun logicalTableVersionContent(table: LogicalTableRecord, physicalTableId: String, physicalTableVersion: Long, logicUnitCount: Int): Map<String, Any?> = linkedMapOf("logicalTableId" to table.id, "code" to table.code, "displayName" to table.displayName, "tableRole" to table.tableRole, "description" to table.description, "owner" to table.owner, "status" to table.status, "physicalTableId" to physicalTableId, "physicalTableVersion" to physicalTableVersion, "logicUnitCount" to logicUnitCount)
    }
}
