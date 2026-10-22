package com.olap.prototype.warehouse

import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.WarehousePreview
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest
import com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshot
import com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshotRequest

/** 技术调查访问业务源；元数据应用只接收观察，不打开来源连接。 */
interface WarehouseSourceInspection {
    fun discover(request: DiscoveryRequest, control: WarehouseExecutionControl): Discovery
    fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview
    fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl)

    class DiscoveryRequest(
        val inspection: WarehouseSchemaSnapshotRequest,
        temporalKeys: Map<String, List<JdbcTableConstraints.TemporalRule>>,
        snapshotUniqueKeys: Map<String, List<SnapshotUniqueKey.Rule>>,
    ) {
        val temporalKeys: Map<String, List<JdbcTableConstraints.TemporalRule>> = immutable(temporalKeys)
        val snapshotUniqueKeys: Map<String, List<SnapshotUniqueKey.Rule>> = immutable(snapshotUniqueKeys)
        override fun equals(other: Any?): Boolean = other is DiscoveryRequest && inspection == other.inspection && temporalKeys == other.temporalKeys && snapshotUniqueKeys == other.snapshotUniqueKeys
        override fun hashCode(): Int = listOf(inspection, temporalKeys, snapshotUniqueKeys).hashCode()
    }

    class Discovery(val snapshot: WarehouseSchemaSnapshot, uniqueKeys: Map<String, List<SnapshotUniqueKey>>) {
        val uniqueKeys: Map<String, List<SnapshotUniqueKey>> = immutable(uniqueKeys)
        override fun equals(other: Any?): Boolean = other is Discovery && snapshot == other.snapshot && uniqueKeys == other.uniqueKeys
        override fun hashCode(): Int = listOf(snapshot, uniqueKeys).hashCode()
    }

    companion object {
        private fun <T> immutable(values: Map<String, List<T>>): Map<String, List<T>> =
            java.util.Map.copyOf(values.mapValues { (_, value) -> java.util.List.copyOf(value) })
    }
}
