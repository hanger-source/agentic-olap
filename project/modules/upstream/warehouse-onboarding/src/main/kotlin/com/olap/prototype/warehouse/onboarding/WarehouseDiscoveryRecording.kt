package com.olap.prototype.warehouse.onboarding

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText as required
import com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord
import com.olap.prototype.management.warehouse.WarehouseDiscoveredTableRecord
import com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord
import com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshot
import com.olap.prototype.warehouse.inspection.WarehouseTableMetadata
import java.time.Instant
import java.util.UUID

/** 一次调查先形成完整观察，再在同一事务记录身份、范围和所有子事实。 */
internal class WarehouseDiscoveryRecording(private val registry: WarehouseRegistryService) {
    fun failed(snapshotId: String, requestedAt: Instant, command: WarehouseDiscoveryCommand, source: SourceConfiguration, failure: RuntimeException): RecordedWarehouseDiscovery {
        val snapshot = WarehouseDiscoverySnapshotRecord(snapshotId, source.dataSourceId, source.dialectId, source.sourceIdentityHash(), command.catalog ?: "", command.schemaName,
            null, null, null, null, null, "FAILED", 0, failure.message, command.requestedBy, requestedAt, Instant.now(), null)
        return registry.inTransaction {
            registry.saveDiscoverySnapshot(snapshot)
            RecordedWarehouseDiscovery(snapshot, null, false, emptyList(), emptyList())
        }
    }
    fun success(snapshotId: String, requestedAt: Instant, command: WarehouseDiscoveryCommand, inspected: WarehouseSchemaSnapshot,
        uniqueKeys: Map<String, List<SnapshotUniqueKey>>): RecordedWarehouseDiscovery {
        val source = inspected.source
        if (SourceBindingRef(command.dataSourceId, command.sourceIdentityHash) != SourceBindingRef(source.dataSourceId, source.sourceIdentityHash))
            throw ProducerContractException("source observation differs from the exact authorized inspection")
        if (uniqueKeys.keys != command.snapshotUniqueKeys.keys)
            throw ProducerContractException("source unique-key observation differs from the explicitly requested tables")
        val observedAt = Instant.now()
        val snapshot = WarehouseDiscoverySnapshotRecord(snapshotId, source.dataSourceId, source.dialectId, source.sourceIdentityHash, command.catalog ?: "", command.schemaName,
            source.databaseProductName, source.databaseProductVersion, source.driverName, source.driverVersion, source.readOnly,
            if (inspected.truncated) "TRUNCATED" else "SUCCEEDED", inspected.tables.size, if (inspected.truncated) "WAREHOUSE_DISCOVERY_TRUNCATED" else null,
            command.requestedBy, requestedAt, observedAt, source.structureProvenance)
        val tables = inspected.tables.map { table -> tableRecord(snapshotId, command, table, observedAt) }
        val columns = inspected.tables.zip(tables).flatMap { (table, record) -> table.columns.map { column ->
            WarehouseDiscoveredColumnRecord(discoveryChildId(record.id, "column", column.name), record.id, column.name,
                column.jdbcType ?: throw ProducerContractException("warehouse column jdbcType is required"), required(column.typeName, "warehouse column typeName"),
                column.precision, column.scale, column.autoIncrement, column.generated, column.defaultValue, column.remarks, column.nullable, column.ordinalPosition, observedAt)
        } }
        return registry.inTransaction {
            registry.saveDiscoverySnapshot(snapshot)
            tables.forEach(registry::saveDiscoveredTable)
            columns.forEach(registry::saveDiscoveredColumn)
            tables.forEach { table -> uniqueKeys[table.tableName].orEmpty().forEach { key ->
                registry.insertDiscoveredUniqueKey(WarehouseDiscoveredUniqueKeyRecord(discoveryChildId(table.id, "unique", key.rule.name), table.id, key))
            } }
            RecordedWarehouseDiscovery(snapshot, source, inspected.truncated, tables, columns)
        }
    }
    private fun tableRecord(snapshotId: String, command: WarehouseDiscoveryCommand, table: WarehouseTableMetadata, observedAt: Instant): WarehouseDiscoveredTableRecord {
        val ref = table.table.ref
        if (command.catalog != null && command.catalog != ref.catalog || command.schemaName != ref.schema)
            throw ProducerContractException("warehouse discovery returned a table outside the requested scope: $ref")
        val id = discoveryChildId(snapshotId, "table", "${ref.catalog}\u0000${ref.schema}\u0000${ref.table}")
        val schema = SourceStructureRegistrationRequest.Table(table.table, table.columns, table.constraints)
        val hash = ProtoBytes.digest(GovernanceProto.toProto(schema))
        return WarehouseDiscoveredTableRecord(id, snapshotId, ref.catalog ?: "", requireNotNull(ref.schema) { "discovered table schema is required" }, ref.table, table.table.remarks, null, hash, table.constraints, null, "DISCOVERED", observedAt)
    }
}

internal fun discoveryChildId(parent: String, kind: String, key: String): String = "$parent:$kind:${UUID.nameUUIDFromBytes("$parent\u0000$kind\u0000$key".toByteArray(Charsets.UTF_8))}"
