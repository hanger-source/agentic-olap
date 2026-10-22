package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.server.ServerInputs
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.PluginWarehouseInspector
import com.olap.prototype.warehouse.inspection.WarehousePreviewRequest

internal class SourceInspectionHandler(connectors: ConnectorRegistry, private val source: SourceConfiguration) {
    private val inspector = PluginWarehouseInspector(connectors)
    private val mapper = OlapJsonUtils.mapper()

    fun execute(operation: String, payload: JsonNode, control: WarehouseExecutionControl): Any = when (operation) {
        "discover-source" -> {
            val request = mapper.treeToValue(payload, WarehouseSourceInspection.DiscoveryRequest::class.java)
            require(request.inspection.connection == source) { "inspection source differs from supervisor capability" }
            inspector.discover(request, control)
        }
        "preview-source" -> {
            val request = mapper.treeToValue(payload, WarehousePreviewRequest::class.java)
            require(request.connection == source) { "inspection source differs from supervisor capability" }
            inspector.preview(request, control)
        }
        "verify-sealed-source" -> {
            ServerInputs.fields(payload, setOf("connection", "declaration"))
            require(mapper.treeToValue(payload.required("connection"), SourceConfiguration::class.java) == source) { "inspection source differs from supervisor capability" }
            inspector.verifySealedDataset(source,
                mapper.treeToValue(payload.required("declaration"), SealedSourceDataset::class.java), control)
            mapOf("verified" to true)
        }
        else -> throw IllegalArgumentException("unsupported technical source inspection")
    }
}
