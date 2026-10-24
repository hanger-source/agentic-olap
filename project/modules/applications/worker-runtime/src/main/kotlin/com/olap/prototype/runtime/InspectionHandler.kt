package com.olap.prototype.runtime

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.source.contract.SourceInspectionResult
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.inspection.*

/** 绑定只由监督者提供；调查请求从入口到插件保持具体类型。 */
internal class InspectionHandler(connectors: ConnectorRegistry, configured: List<SourceConfiguration>) {
    private val sources = DeployedSources(configured, connectors)
    private val inspector = PluginWarehouseInspector(connectors)
    fun execute(request: SourceInspection, control: WarehouseExecutionControl): SourceInspectionResult {
        control.checkCancellation()
        val result = when (request) {
            SourceInspection.Capabilities -> SourceInspectionResult.Capabilities(setOf("discover", "describe", "snapshot", "preview", "profile", "profile-relationship"),
                "agentic.olap.semantic.v1.SemanticExpression:PHYSICAL_COLUMN")
            is SourceInspection.Discover -> SourceInspectionResult.Discovery(inspector.discover(WarehouseDiscoveryRequest(source(request.binding), request.catalog, request.schemaPattern, request.tablePattern, request.tableTypes, request.limit), control))
            is SourceInspection.Describe -> SourceInspectionResult.Description(inspector.describe(WarehouseTableInspectionRequest(source(request.binding), request.table), control))
            is SourceInspection.Snapshot -> SourceInspectionResult.Snapshot(inspector.snapshot(WarehouseSchemaSnapshotRequest(source(request.binding), request.catalog, request.schemaName, request.tablePattern, request.tableTypes, request.limit), control))
            is SourceInspection.Preview -> SourceInspectionResult.Preview(inspector.preview(WarehousePreviewRequest(source(request.binding), request.table, request.limit), control))
            is SourceInspection.Profile -> SourceInspectionResult.Profile(inspector.profile(WarehouseProfileRequest(source(request.binding), request.table, request.columns, request.timeoutSeconds), control))
            is SourceInspection.Relationship -> SourceInspectionResult.Relationship(inspector.profileRelationship(WarehouseRelationshipProfileRequest(source(request.binding), request.sourceInput, request.targetInput, request.timeoutSeconds), control))
        }
        control.checkCancellation()
        return result
    }
    private fun source(binding: SourceBindingRef): SourceConfiguration = sources.requireConfiguration(binding)
}
