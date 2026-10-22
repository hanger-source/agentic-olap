package com.olap.prototype.management.governance

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredBoolean
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredCoordinate
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredInteger
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints

internal class PhysicalAssetSnapshots(private val reads: GovernanceSnapshotReader) {
    fun physicalTable(id: String, version: Long): PhysicalTableRecord = decode {
        val snapshot = reads.versionSnapshot("physical_table_version", "physical_table_id", id, version)
        val content = snapshot.content
        val identity = requiredText(content, "id"); requireMatchingId(id, identity, "physical_table")
        PhysicalTableRecord(identity, requiredText(content, "code"), requiredText(content, "displayName"), requiredText(content, "dataSource"), requiredText(content, "dialectId"), requiredCoordinate(content, "catalogName", "physical_table_version.content_json"), requiredText(content, "schemaName"), requiredText(content, "tableName"), optionalText(content, "description"), requiredText(content, "owner"), requiredText(content, "loadStatus"), optionalLong(content, "rowCount"), optionalText(content, "schemaHash"), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    fun physicalColumn(id: String, version: Long): PhysicalColumnRecord = decode {
        val snapshot = reads.versionSnapshot("physical_column_version", "physical_column_id", id, version)
        val content = snapshot.content; val field = "physical_column_version.content_json"
        val identity = requiredText(content, "id", field); requireMatchingId(id, identity, "physical_column")
        PhysicalColumnRecord(identity, requiredText(content, "physicalTableId", field), requiredText(content, "columnName", field), requiredText(content, "displayName", field), requiredText(content, "dataType", field), optionalText(content, "description"), requiredBoolean(content, "nullable", field), requiredInteger(content, "ordinalPosition", field), requiredBoolean(content, "enabled", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedAt ?: snapshot.createdAt, OlapJsonUtils.mapper().treeToValue(content.required("jdbcColumnType"), JdbcColumnType::class.java))
    }
    fun physicalTableColumns(id: String, version: Long): List<PhysicalColumnRecord> {
        val refs = reads.physicalTableContent(id, version).get("columnVersionRefs")
        if (refs == null || !refs.isArray) throw ProducerContractException("physical_table_version.content_json.columnVersionRefs must be an array: $id@$version")
        return refs.map { ref -> physicalColumn(requiredText(ref, "id", "physical_table_version.content_json.columnVersionRefs"), requireVersion(ref.path("version").asLong(), "physical_table_version.content_json.columnVersionRefs.version")) }
            .sortedWith(compareBy(PhysicalColumnRecord::ordinalPosition, PhysicalColumnRecord::columnName))
    }
    private fun <T> decode(action: () -> T): T = try { action() }
        catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("exact aggregate snapshot decoding failed", failure) }

    companion object {
        fun physicalColumnSnapshotJson(column: PhysicalColumnRecord, sensitivityLevel: String?): String = encode(linkedMapOf(
            "id" to column.id, "physicalTableId" to column.physicalTableId, "columnName" to column.columnName, "displayName" to column.displayName, "dataType" to column.dataType, "jdbcColumnType" to column.jdbcColumnType, "description" to column.description, "nullable" to column.nullable, "ordinalPosition" to column.ordinalPosition, "enabled" to column.enabled, "status" to column.status, "sensitivityLevel" to normalized(sensitivityLevel)))
        fun putPhysicalTableFields(snapshot: MutableMap<String, Any?>, table: PhysicalTableRecord) {
            snapshot.putAll(linkedMapOf("id" to table.id, "code" to table.code, "displayName" to table.displayName, "dataSource" to table.dataSource, "dialectId" to table.dialectId, "catalogName" to table.catalogName, "schemaName" to table.schemaName, "tableName" to table.tableName, "description" to table.description, "owner" to table.owner, "loadStatus" to table.loadStatus, "rowCount" to table.rowCount, "schemaHash" to table.schemaHash, "status" to table.status))
        }
        fun putColumnVersionRefs(snapshot: MutableMap<String, Any?>, columns: List<PhysicalColumnRecord>) { snapshot["columnVersionRefs"] = columns.map { mapOf("id" to it.id, "version" to it.currentVersion) } }
        fun sourceConstraints(content: JsonNode): PhysicalSourceConstraints {
            if (!content.hasNonNull("sourceConstraints")) throw ProducerContractException("physical table source evidence is missing; revise the complete definition from an exact registered discovery")
            return OlapJsonUtils.mapper().convertValue(content.get("sourceConstraints"), PhysicalSourceConstraints::class.java)
        }
        fun tableContent(table: PhysicalTableRecord, request: PhysicalTableCreateRequest, columns: List<PhysicalColumnRecord>, evidence: PhysicalSourceConstraints): String {
            val content = linkedMapOf<String, Any?>(); putPhysicalTableFields(content, table)
            content["dataDomain"] = normalized(request.dataDomain); content["sensitivityLevel"] = normalized(request.sensitivityLevel); content["sourceSystem"] = normalized(request.sourceSystem)
            putColumnVersionRefs(content, columns); content["sourceConstraints"] = evidence
            return encode(content)
        }
        fun revisedTableContent(table: PhysicalTableRecord, previous: JsonNode, columns: List<PhysicalColumnRecord>, evidence: PhysicalSourceConstraints): String {
            val content: LinkedHashMap<String, Any?> = OlapJsonUtils.mapper().convertValue(previous, object : TypeReference<LinkedHashMap<String, Any?>>() {})
            putPhysicalTableFields(content, table); putColumnVersionRefs(content, columns); content["sourceConstraints"] = evidence
            return encode(content)
        }
        private fun normalized(value: String?): String? = value?.takeUnless { it.isBlank() }?.trim()
        private fun encode(value: Any): String = try { OlapJsonUtils.mapper().writeValueAsString(value) }
            catch (failure: java.io.IOException) { throw ProducerContractException("physical version content is invalid", failure) }
    }
}
