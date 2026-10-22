package com.olap.prototype.connector

import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.inspection.*

/** JDBC、HTTP等实现共同遵守的来源执行与调查边界。 */
interface SourceAccess : WarehouseSourceInspection, AutoCloseable {
    val configurationParameters: com.olap.prototype.warehouse.contract.SourceConfigurationParameters
    fun validateConfiguration(source: SourceConfiguration)
    fun capabilities(source: SourceConfiguration): SourceCapabilities
    fun supportsConstraintVerification(evidence: PhysicalSourceConstraints): Boolean = false
    fun execute(source: SourceConfiguration, request: SourceSqlRequest, control: WarehouseExecutionControl,
        budget: ResultMaterializationBudget, nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult

    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery =
        unsupported(request.inspection.connection, "discover-source", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview =
        unsupported(request.connection, "preview-source", SourceCapabilities.Capability.DATA_PREVIEW)
    override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl): Unit =
        unsupported(connection, "register-source-dataset", SourceCapabilities.Capability.SEALED_DATASET_VERIFICATION)
    fun discover(request: WarehouseDiscoveryRequest, control: WarehouseExecutionControl): WarehouseDiscoveryResult =
        unsupported(request.connection, "discover", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun describe(request: WarehouseTableInspectionRequest, control: WarehouseExecutionControl): WarehouseTableMetadata =
        unsupported(request.connection, "describe", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun snapshot(request: WarehouseSchemaSnapshotRequest, control: WarehouseExecutionControl): WarehouseSchemaSnapshot =
        unsupported(request.connection, "snapshot", SourceCapabilities.Capability.STRUCTURE_INSPECTION)
    fun profile(request: WarehouseProfileRequest, control: WarehouseExecutionControl): WarehouseProfile =
        unsupported(request.connection, "profile", SourceCapabilities.Capability.DATA_PROFILING)
    fun profileRelationship(request: WarehouseRelationshipProfileRequest, control: WarehouseExecutionControl): WarehouseRelationshipProfile =
        unsupported(request.connection, "profileRelationship", SourceCapabilities.Capability.RELATIONSHIP_PROFILING)

    override fun close()

    private fun unsupported(source: SourceConfiguration, operation: String, capability: SourceCapabilities.Capability): Nothing =
        throw SourceCapabilityException(source.dataSourceId, operation, capability)
}

/** 可选 JDBC 专属技术能力；通用访问合同不要求 HTTP 来源实现它。 */
interface JdbcAccess : SourceAccess { val jdbc: JdbcSupport }
