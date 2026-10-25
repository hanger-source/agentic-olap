package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.*
import com.olap.prototype.warehouse.*
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*

/** JDBC is one complete access implementation; the shared source API requires no JDBC resources. */
class JdbcSourceAccess(private val identity: ConnectorRef, compilation: CompilationSupport, override val jdbc: JdbcSupport) : JdbcAccess {
    override val configurationParameters = SourceConfigurationParameters(setOf("jdbcUrl", "username", "driverClassName"), emptySet())
    private val registry = ConnectorRegistry(listOf(ResolvedConnector(identity, compilation, this)))
    private val inspector = JdbcWarehouseInspector(registry)
    private val inspection = JdbcSourceInspection(inspector, registry)
    override fun supportsConstraintVerification(dataContract: PhysicalDataContract, adoption: PhysicalSourceAdoption): Boolean {
        adoption.requireContract(dataContract)
        return jdbc.supportsConstraintSnapshot(adoption.evidence)
    }
    override fun validateConfiguration(source: SourceConfiguration) {
        configurationParameters.requireConfiguration(source)
        require(identity == source.connectorRef) { "JDBC source differs from exact connector identity" }
        JdbcConnectionSpec.from(source)
    }
    override fun capabilities(source: SourceConfiguration): SourceCapabilities { validateConfiguration(source); return jdbc.capabilities(source) }
    override fun execute(source: SourceConfiguration, request: SourceSqlRequest, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult {
        validateConfiguration(source)
        return JdbcProvider(JdbcConnectionSpec.from(source), registry).executeFrame(request, control, budget, nativeMemoryBytes, stageNanos)
    }
    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery = inspection.discover(request, control)
    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview = inspection.preview(request, control)
    override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl) = inspection.verifySealedDataset(connection, declaration, control)
    override fun discover(request: WarehouseDiscoveryRequest, control: WarehouseExecutionControl): WarehouseDiscoveryResult { control.checkCancellation(); return inspector.discover(request, control) }
    override fun describe(request: WarehouseTableInspectionRequest, control: WarehouseExecutionControl): WarehouseTableMetadata { control.checkCancellation(); return inspector.describe(request, control) }
    override fun snapshot(request: WarehouseSchemaSnapshotRequest, control: WarehouseExecutionControl): WarehouseSchemaSnapshot { control.checkCancellation(); return inspector.snapshot(request, control) }
    override fun profile(request: WarehouseProfileRequest, control: WarehouseExecutionControl): WarehouseProfile { control.checkCancellation(); return inspector.profile(request, control) }
    override fun profileRelationship(request: WarehouseRelationshipProfileRequest, control: WarehouseExecutionControl): WarehouseRelationshipProfile { control.checkCancellation(); return inspector.profileRelationship(request, control) }
    override fun close() = jdbc.close()
}
