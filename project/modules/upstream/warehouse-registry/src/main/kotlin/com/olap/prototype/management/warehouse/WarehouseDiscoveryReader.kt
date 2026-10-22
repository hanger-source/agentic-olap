package com.olap.prototype.management.warehouse

import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceCollectionRecord

interface WarehouseDataSourceReader {
    fun findDataSource(id: String): WarehouseDataSourceRecord?
    fun findSourceBinding(reference: SourceBindingRef): WarehouseSourceBindingRecord?
}

/** 治理按精确身份采用已记录的调查事实，不因此再连接来源。 */
interface WarehouseDiscoveryReader : WarehouseDataSourceReader {
    fun findDiscoverySnapshot(id: String): WarehouseDiscoverySnapshotRecord?
    fun findDiscoveredTable(id: String): WarehouseDiscoveredTableRecord?
    fun listDiscoveredColumns(discoveredTableId: String): List<WarehouseDiscoveredColumnRecord>
    fun listDiscoveredUniqueKeys(discoveredTableId: String): List<WarehouseDiscoveredUniqueKeyRecord>
    fun listDiscoveredTables(snapshotId: String): List<WarehouseDiscoveredTableRecord>
    fun findSourceCollection(id: String): SourceCollectionRecord?
}
