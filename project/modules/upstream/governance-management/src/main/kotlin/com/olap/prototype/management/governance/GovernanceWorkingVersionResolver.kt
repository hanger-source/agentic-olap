package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.storage.PlatformDatabase

/** 工作目录只选择唯一草稿；没有草稿时选择唯一 ACTIVE，不改变精确快照读取。 */
class GovernanceWorkingVersionResolver(private val database: PlatformDatabase) {
    data class VersionRef(val objectId: String, val version: Long, val status: String)
    fun list(versionTable: String, idColumn: String): List<VersionRef> = select(versionTable, idColumn, null)
    fun find(versionTable: String, idColumn: String, objectId: String): VersionRef? = select(versionTable, idColumn, requireText(objectId, "$versionTable.$idColumn")).singleOrNull()
    private fun select(table: String, column: String, id: String?): List<VersionRef> = try {
        val rows = GovernanceJdbcSupport.query(database, "SELECT $column, version, version_status FROM $table WHERE version_status IN ('DRAFT', 'ACTIVE')${if (id == null) "" else " AND $column=?"} ORDER BY $column, version_status, version DESC", if (id == null) emptyList() else listOf(id)) {
            VersionRef(requireText(it.getString(column), "$table.$column"), requireVersion(GovernanceJdbcSupport.nullableLong(it, "version"), "$table.version"), requireText(it.getString("version_status"), "$table.version_status"))
        }
        rows.groupBy { it.objectId }.map { (objectId, candidates) ->
            val draft = candidates.filter { it.status == "DRAFT" }; val active = candidates.filter { it.status == "ACTIVE" }
            if (draft.size > 1) throw ProducerContractException("$table has multiple DRAFT versions: $objectId")
            if (active.size > 1) throw ProducerContractException("$table has multiple ACTIVE versions: $objectId")
            draft.singleOrNull() ?: active.singleOrNull() ?: throw ProducerContractException("working version candidate is empty: $objectId")
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to select working versions from $table", failure) }
}
