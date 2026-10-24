package com.olap.prototype.runtime

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.PluginWarehouseInspector
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest
import com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshotRequest

internal class SourceInspectionHandler(connectors: ConnectorRegistry, private val source: SourceConfiguration) {
    private val inspector = PluginWarehouseInspector(connectors)
    fun execute(command: SourceWorkerCommand, control: WarehouseExecutionControl): SourceWorkerResult = when (command) {
        is SourceWorkerCommand.Discover -> {
            val input = command.inspection
            require(input.binding == source.bindingRef()) { "inspection source differs from supervisor capability" }
            val request = WarehouseSourceInspection.DiscoveryRequest(WarehouseSchemaSnapshotRequest(source, input.catalog,
                input.schemaName, input.tablePattern, input.tableTypes, input.limit), command.temporalKeys, command.snapshotUniqueKeys)
            SourceWorkerResult.Discovery(inspector.discover(request, control))
        }
        is SourceWorkerCommand.Preview -> {
            val input = command.inspection
            require(input.binding == source.bindingRef()) { "inspection source differs from supervisor capability" }
            SourceWorkerResult.Preview(inspector.preview(WarehousePreviewRequest(source, input.table, input.limit), control))
        }
        is SourceWorkerCommand.Verify -> {
            require(command.binding == source.bindingRef()) { "inspection source differs from supervisor capability" }
            inspector.verifySealedDataset(source, command.declaration, control)
            SourceWorkerResult.Verified
        }
    }
}
