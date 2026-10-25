package com.olap.prototype.management.warehouse

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.PhysicalAssetReferenceResolver
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.warehouse.contract.PhysicalSourceAdoption
import com.olap.prototype.warehouse.contract.SourceAdoptionRef

class WarehouseRegistryService(private val database: PlatformDatabase, private val repository: WarehouseRegistryRepository,
    private val physicalAssets: PhysicalAssetReferenceResolver) : SourceAdoptionRegistry {
    override fun <T> inTransaction(work: () -> T): T = database.inTransaction(work)
    override fun findDataSource(id: String): WarehouseDataSourceRecord? = repository.findDataSource(id)
    fun listDataSources(): List<WarehouseDataSourceRecord> = repository.listDataSources()
    override fun findSourceBinding(reference: SourceBindingRef): WarehouseSourceBindingRecord? = repository.findSourceBinding(reference)
    fun listSourceBindings(dataSourceId: String): List<WarehouseSourceBindingRecord> = repository.listSourceBindings(dataSourceId)
    fun saveDiscoverySnapshot(record: WarehouseDiscoverySnapshotRecord) {
        repository.findSourceBinding(SourceBindingRef(record.dataSource, record.sourceIdentityHash))
            ?: throw ProducerContractException("discovery requires an exact registered source binding")
        repository.saveDiscoverySnapshot(record)
    }
    override fun findDiscoverySnapshot(id: String): WarehouseDiscoverySnapshotRecord? = repository.findDiscoverySnapshot(id)
    fun listDiscoverySnapshots(dataSource: String, catalogName: String?, schemaName: String): List<WarehouseDiscoverySnapshotRecord> =
        repository.listDiscoverySnapshots(dataSource, catalogName?.trim() ?: "", schemaName)
    fun saveDiscoveredTable(record: WarehouseDiscoveredTableRecord) {
        repository.findDiscoverySnapshot(record.discoverySnapshotId)
            ?: throw ProducerContractException("warehouse_discovered_table.discovery_snapshot_id not found: ${record.discoverySnapshotId}")
        record.registeredPhysicalTableId?.takeUnless(String::isBlank)?.let { physicalAssets.requirePhysicalTable(it, "warehouse_discovered_table.registered_physical_table_id") }
        repository.saveDiscoveredTable(record)
    }
    override fun findDiscoveredTable(id: String): WarehouseDiscoveredTableRecord? = repository.findDiscoveredTable(id)
    override fun listDiscoveredTables(snapshotId: String): List<WarehouseDiscoveredTableRecord> = repository.listDiscoveredTables(snapshotId)
    fun saveDiscoveredColumn(record: WarehouseDiscoveredColumnRecord) {
        repository.findDiscoveredTable(record.discoveredTableId)
            ?: throw ProducerContractException("warehouse_discovered_column.discovered_table_id not found: ${record.discoveredTableId}")
        repository.saveDiscoveredColumn(record)
    }
    override fun listDiscoveredColumns(discoveredTableId: String): List<WarehouseDiscoveredColumnRecord> = repository.listDiscoveredColumns(discoveredTableId)
    fun insertDiscoveredUniqueKey(record: WarehouseDiscoveredUniqueKeyRecord) = repository.insertDiscoveredUniqueKey(record)
    override fun listDiscoveredUniqueKeys(tableId: String): List<WarehouseDiscoveredUniqueKeyRecord> = repository.listDiscoveredUniqueKeys(tableId)
    fun insertSourceCollection(record: SourceCollectionRecord) = repository.insertSourceCollection(record)
    override fun findSourceCollection(id: String): SourceCollectionRecord? = repository.findSourceCollection(id)
    override fun listSourceCollections(dataSourceId: String): List<SourceCollectionRecord> = repository.listSourceCollections(dataSourceId)
    override fun insertSourceAdoption(record: PhysicalSourceAdoption): PhysicalSourceAdoption = repository.insertSourceAdoption(record)
    override fun findSourceAdoption(reference: SourceAdoptionRef): PhysicalSourceAdoption? = repository.findSourceAdoption(reference)
    override fun listSourceAdoptions(discoverySnapshotId: String): List<PhysicalSourceAdoption> = repository.listSourceAdoptions(discoverySnapshotId)
    fun savePhysicalColumnProfile(record: PhysicalColumnProfileRecord) {
        physicalAssets.requirePhysicalColumnVersion(record.physicalColumnId, record.physicalColumnVersion, "physical_column_profile.physical_column_version")
        repository.savePhysicalColumnProfile(record)
    }
    fun listPhysicalColumnProfiles(physicalColumnId: String): List<PhysicalColumnProfileRecord> {
        physicalAssets.requirePhysicalColumn(physicalColumnId, "physical_column_profile.physical_column_id")
        return repository.listPhysicalColumnProfiles(physicalColumnId)
    }
}
