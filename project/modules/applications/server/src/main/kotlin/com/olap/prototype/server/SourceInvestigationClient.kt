package com.olap.prototype.server

import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.runtime.SourceWorkerCommand
import com.olap.prototype.runtime.SourceWorkerResult
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.WarehouseInspectionException
import com.olap.prototype.warehouse.inspection.WarehousePreview
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest
import com.olap.prototype.wire.query.SourceCapabilityUnavailable
import com.olap.prototype.wire.query.SourceGuaranteeFailed
import com.olap.prototype.wire.query.SourceInspectionFailed
import com.olap.prototype.wire.query.SourceInspectionFailure as WireInspectionFailure

internal class SourceInvestigationClient(private val workers: WorkerPool,
    private val request: WorkerPool.Request) : WarehouseSourceInspection {
    private fun call(command: SourceWorkerCommand, source: SourceConfiguration,
        control: WarehouseExecutionControl): SourceWorkerResult {
        control.checkCancellation()
        try {
            return workers.borrow(WorkerPool.Kind.SOURCE_INSPECTION, listOf(source), request).use {
                val response = it.source(command)
                control.checkCancellation(); response
            }
        } catch (failure: WorkerPool.WorkerFailure) {
            failure.inspectionFailure?.let(SourceInspectionFailure::rethrow)
            throw failure
        }
    }

    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery {
        val input = request.inspection
        val result = call(SourceWorkerCommand.Discover(SourceInspection.Snapshot(input.connection.bindingRef(), input.catalog,
            input.schemaName, input.tablePattern, input.tableTypes.orEmpty(), input.limit), request.temporalKeys, request.snapshotUniqueKeys), input.connection, control)
        return (result as? SourceWorkerResult.Discovery)?.value ?: error("source worker returned a different discovery receipt")
    }

    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview {
        val result = call(SourceWorkerCommand.Preview(SourceInspection.Preview(request.connection.bindingRef(), request.table, request.limit)), request.connection, control)
        return (result as? SourceWorkerResult.Preview)?.value ?: error("source worker returned a different preview receipt")
    }

    override fun verifySealedDataset(source: SourceConfiguration, declaration: SealedSourceDataset,
        control: WarehouseExecutionControl) {
        val response = call(SourceWorkerCommand.Verify(source.bindingRef(), declaration), source, control)
        check(response == SourceWorkerResult.Verified) {
            "source verification worker returned an invalid receipt"
        }
    }
}
