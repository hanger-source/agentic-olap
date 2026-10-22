package com.olap.prototype.warehouse.inspection

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceAccess
import com.olap.prototype.connector.SourceCapabilities.Capability
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration

/** 按本次技术操作检查实际所需能力，再委派到精确插件；不隐式打开 JDBC。 */
class PluginWarehouseInspector(private val connectors: ConnectorRegistry) : WarehouseSourceInspection {
    private fun access(source: SourceConfiguration, operation: String, capability: Capability): SourceAccess = connectors.require(source.connectorRef).access.also {
        it.validateConfiguration(source)
        it.capabilities(source).require(source.dataSourceId, operation, capability)
    }
    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery {
        if (request.temporalKeys.isNotEmpty() || request.snapshotUniqueKeys.isNotEmpty())
            access(request.inspection.connection, "discover-source/verify-keys", Capability.CONSTRAINT_VERIFICATION)
        return access(request.inspection.connection, "discover-source", Capability.STRUCTURE_INSPECTION).discover(request, control)
    }
    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview =
        access(request.connection, "preview-source", Capability.DATA_PREVIEW).preview(request, control)
    override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl) =
        access(connection, "register-source-dataset", Capability.SEALED_DATASET_VERIFICATION).verifySealedDataset(connection, declaration, control)
    fun discover(request: WarehouseDiscoveryRequest, control: WarehouseExecutionControl): WarehouseDiscoveryResult =
        access(request.connection, "discover", Capability.STRUCTURE_INSPECTION).discover(request, control)
    fun describe(request: WarehouseTableInspectionRequest, control: WarehouseExecutionControl): WarehouseTableMetadata =
        access(request.connection, "describe", Capability.STRUCTURE_INSPECTION).describe(request, control)
    fun snapshot(request: WarehouseSchemaSnapshotRequest, control: WarehouseExecutionControl): WarehouseSchemaSnapshot =
        access(request.connection, "snapshot", Capability.STRUCTURE_INSPECTION).snapshot(request, control)
    fun profile(request: WarehouseProfileRequest, control: WarehouseExecutionControl): WarehouseProfile =
        access(request.connection, "profile", Capability.DATA_PROFILING).profile(request, control)
    fun profileRelationship(request: WarehouseRelationshipProfileRequest, control: WarehouseExecutionControl): WarehouseRelationshipProfile =
        access(request.connection, "profileRelationship", Capability.RELATIONSHIP_PROFILING).profileRelationship(request, control)
}
