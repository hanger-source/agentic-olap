package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.server.ServerInputs.command
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.WarehouseInspectionException
import com.olap.prototype.warehouse.inspection.WarehousePreview
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest

internal class SourceInvestigationClient(private val workers: RemoteWorkers,
    private val request: RemoteWorkers.Request) : WarehouseSourceInspection {
    private val mapper = OlapJsonUtils.mapper()

    private fun call(operation: String, payload: Any, source: SourceConfiguration,
        control: WarehouseExecutionControl): JsonNode {
        control.checkCancellation()
        try {
            return workers.borrow(RemoteWorkers.Kind.SOURCE_INSPECTION, listOf(source), request).use {
                val response = it.exchange(command(operation, mapper.valueToTree(payload)))
                control.checkCancellation(); response
            }
        } catch (failure: RemoteWorkers.WorkerFailure) {
            SourceInspectionFailure.rethrow(failure.error)
            throw failure
        }
    }

    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery =
        mapper.treeToValue(call("discover-source", request, request.inspection.connection, control), WarehouseSourceInspection.Discovery::class.java)

    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview =
        mapper.treeToValue(call("preview-source", request, request.connection, control), WarehousePreview::class.java)

    override fun verifySealedDataset(source: SourceConfiguration, declaration: SealedSourceDataset,
        control: WarehouseExecutionControl) {
        val response = call("verify-sealed-source", mapOf("connection" to source, "declaration" to declaration), source, control)
        check(response.isObject && response.size() == 1 && response.path("verified").isBoolean && response.path("verified").booleanValue()) {
            "source verification worker returned an invalid receipt"
        }
    }
}

internal object SourceInspectionFailure {
    fun project(failure: Exception): Map<String, Any?> = when (failure) {
        is SourceCapabilityException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "dataSourceId" to failure.dataSourceId, "operation" to failure.operation, "capability" to failure.capability.name)
        is WarehouseInspectionException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "dataSourceId" to failure.dataSourceId, "sqlState" to failure.sqlState, "failureType" to failure.failureType)
        is SourceGuaranteeException -> mapOf("type" to failure.javaClass.name, "code" to failure.code,
            "sqlState" to failure.sqlState, "failureType" to failure.failureType)
        is RuntimeResourceLimitException -> mapOf("type" to failure.javaClass.name, "code" to failure.code)
        is java.util.concurrent.CancellationException -> mapOf("type" to failure.javaClass.name, "code" to "CANCELLED")
        else -> mapOf("type" to failure.javaClass.name, "code" to "SOURCE_INSPECTION_FAILED")
    }

    fun rethrow(error: JsonNode) {
        fun text(field: String) = ServerInputs.text(error, field)
        fun optional(field: String) = ServerInputs.optionalText(error, field)
        when (text("type")) {
            SourceCapabilityException::class.java.name -> throw SourceCapabilityException(text("dataSourceId"), text("operation"), SourceCapabilities.Capability.valueOf(text("capability")))
            WarehouseInspectionException::class.java.name -> throw WarehouseInspectionException(text("code"), text("dataSourceId"), optional("sqlState"), optional("failureType"), null)
            SourceGuaranteeException::class.java.name -> throw SourceGuaranteeException(text("code"), optional("sqlState"), optional("failureType"))
            RuntimeResourceLimitException::class.java.name -> throw RuntimeResourceLimitException(text("code"))
            java.util.concurrent.CancellationException::class.java.name -> throw java.util.concurrent.CancellationException("source inspection was cancelled")
        }
    }
}
