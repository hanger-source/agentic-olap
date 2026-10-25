package com.olap.prototype.warehouse.onboarding

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText as required
import com.olap.prototype.governance.contract.PhysicalAssetRegistrar
import com.olap.prototype.governance.contract.PhysicalTableCreateRequest
import com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import java.sql.Types

/** 逐列治理一份精确调查，并原子建立物理资产及其采用记录。 */
internal class DiscoveredPhysicalTableAdoption(private val physicalAssets: PhysicalAssetRegistrar, private val registry: WarehouseRegistryService) {
    fun adopt(request: DiscoveredPhysicalTableRegistrationRequest): RegisteredPhysicalTable {
        val id = required(request.discoveredTableId, "discoveredTableId")
        val table = registry.findDiscoveredTable(id) ?: throw ProducerContractException("warehouse_discovered_table.id not found: $id")
        val snapshot = registry.findDiscoverySnapshot(table.discoverySnapshotId)
            ?: throw ProducerContractException("warehouse_discovery_snapshot.id not found: ${table.discoverySnapshotId}")
        if (snapshot.status != "SUCCEEDED") throw ProducerContractException("warehouse discovery snapshot is not complete: ${snapshot.id} status=${snapshot.status}")
        if (table.registeredPhysicalTableId != null) throw ProducerContractException("warehouse discovered table is already registered: ${table.id}")
        val discovered = registry.listDiscoveredColumns(id).associateBy { it.columnName }
        if (request.columns.isEmpty()) throw ProducerContractException("physical_table.columns must not be empty")
        val selected = mutableSetOf<String>()
        val columns = request.columns.map { governed ->
            val name = required(governed.columnName, "physical_column.column_name")
            if (!selected.add(name)) throw ProducerContractException("physical_table.columns contains duplicate column: $name")
            val column = discovered[name] ?: throw ProducerContractException("physical column is not present in discovery snapshot: $name")
            val nullable = column.nullable ?: throw ProducerContractException("physical column requires explicit source nullability: $name")
            PhysicalTableCreateRequest.ColumnInput(name, required(governed.displayName, "physical_column.display_name"), physicalType(column),
                required(governed.sensitivityLevel, "physical_column.sensitivity_level"), nullable, governed.description,
                JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, nullable))
        }
        if (selected.size != discovered.size) throw ProducerContractException("physical_table registration must govern every discovered column; missing=${(discovered.keys - selected).toSortedSet()}")
        return registry.inTransaction {
            val created = physicalAssets.createPhysicalTable(PhysicalTableCreateRequest(required(request.code, "physical_table.code"), table.tableName,
                required(request.displayName, "physical_table.display_name"), request.description, required(request.dataDomain, "physical_table.data_domain"),
                required(request.owner, "physical_table.owner"), required(request.sensitivityLevel, "physical_table.sensitivity_level"), required(request.sourceSystem, "physical_table.source_system"),
                snapshot.dataSource, table.catalogName, table.schemaName,
                PhysicalTableCreateRequest.SourceAdoptionInput(table.id, emptyList()), required(request.createdBy, "physical_table.created_by"), columns))
            val registered = table.copy(registeredPhysicalTableId = created.physicalTable.id, status = "REGISTERED")
            registry.saveDiscoveredTable(registered)
            RegisteredPhysicalTable(created, registered)
        }
    }
    private fun physicalType(column: WarehouseDiscoveredColumnRecord): String = when (column.jdbcType) {
        Types.DECIMAL, Types.NUMERIC -> sized(column.typeName, column.precision, column.scale)
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> sized(column.typeName, column.precision, null)
        else -> column.typeName
    }
    private fun sized(type: String, precision: Int?, scale: Int?): String = when {
        precision == null || precision <= 0 -> type
        scale != null && scale >= 0 -> "$type($precision,$scale)"
        else -> "$type($precision)"
    }
}
