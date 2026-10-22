package com.olap.prototype.warehouse.onboarding

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.PhysicalAssetRegistrar
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.StructureProvenance
import com.olap.prototype.warehouse.inspection.*
import java.time.Instant
import java.util.UUID

/** 接入应用只组织获授权绑定、调查与采用；具体来源访问通过正式插件端口完成。 */
class WarehouseOnboardingService(physicalAssets: PhysicalAssetRegistrar, private val registry: WarehouseRegistryService,
    private val authorizedSources: (SourceBindingRef) -> SourceConfiguration?) {
    private val recording = WarehouseDiscoveryRecording(registry)
    private val adoption = DiscoveredPhysicalTableAdoption(physicalAssets, registry)
    private val sealed = SealedSourceRegistration(registry)

    fun discover(command: WarehouseDiscoveryCommand, control: WarehouseExecutionControl, inspector: WarehouseSourceInspection): RecordedWarehouseDiscovery {
        control.checkCancellation()
        val source = requireAuthorizedSource(SourceBindingRef(command.dataSourceId, command.sourceIdentityHash))
        val inspection = WarehouseSchemaSnapshotRequest(source, command.catalog, command.schemaName, command.tablePattern, command.tableTypes, command.limit)
        val requestedAt = Instant.now()
        val snapshotId = "warehouse-discovery:${UUID.randomUUID()}"
        val observation = try { inspector.discover(WarehouseSourceInspection.DiscoveryRequest(inspection, command.temporalKeys, command.snapshotUniqueKeys), control) }
        catch (failure: RuntimeException) {
            if (failure !is WarehouseInspectionException && failure !is SourceGuaranteeException) throw failure
            control.checkCancellation()
            return recording.failed(snapshotId, requestedAt, command, source, failure)
        }
        control.checkCancellation()
        return recording.success(snapshotId, requestedAt, command, observation.snapshot, observation.uniqueKeys)
    }
    fun registerStructure(request: SourceStructureRegistrationRequest): RecordedWarehouseDiscovery {
        val source = requireAuthorizedSource(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash))
        val provenance = StructureProvenance(StructureProvenance.Kind.DECLARED, request.authority, request.revision)
        val metadata = WarehouseSourceMetadata(source.dataSourceId, source.dialectId, source.sourceIdentityHash(), null, null, null, null, null, null, provenance)
        val tables = request.tables.map { WarehouseTableMetadata(metadata, it.table, it.columns, it.constraints) }
        val command = WarehouseDiscoveryCommand(request.dataSourceId, request.sourceIdentityHash, request.catalog, request.schemaName, null, emptyList(), tables.size,
            emptyMap(), emptyMap(), request.requestedBy)
        return recording.success("warehouse-discovery:${UUID.randomUUID()}", Instant.now(), command, WarehouseSchemaSnapshot(metadata, false, tables), emptyMap())
    }
    fun registerSourceDataset(request: SourceDatasetRegistrationRequest, control: WarehouseExecutionControl, inspector: WarehouseSourceInspection): List<SourceCollectionRecord> =
        sealed.register(requireAuthorizedSource(SourceBindingRef(request.dataSourceId, request.sourceIdentityHash)), request, control, inspector)
    fun register(request: DiscoveredPhysicalTableRegistrationRequest): RegisteredPhysicalTable = adoption.adopt(request)
    fun preview(request: DiscoveredTablePreviewRequest, control: WarehouseExecutionControl, inspector: WarehouseSourceInspection): WarehousePreview {
        val table = registry.findDiscoveredTable(request.discoveredTableId)
            ?: throw ProducerContractException("warehouse_discovered_table.id not found: ${request.discoveredTableId}")
        val snapshot = registry.findDiscoverySnapshot(table.discoverySnapshotId)
            ?: throw ProducerContractException("warehouse_discovery_snapshot.id not found: ${table.discoverySnapshotId}")
        val source = requireAuthorizedSource(SourceBindingRef(snapshot.dataSource, snapshot.sourceIdentityHash))
        return inspector.preview(WarehousePreviewRequest(source, WarehouseTableRef(table.catalogName.takeUnless(String::isBlank), table.schemaName, table.tableName), request.effectiveLimit), control)
    }
    private fun requireAuthorizedSource(reference: SourceBindingRef): SourceConfiguration {
        val identity = registry.findDataSource(reference.dataSourceId) ?: throw ProducerContractException("warehouse_data_source.id not found: ${reference.dataSourceId}")
        if (identity.status != "ACTIVE") throw ProducerContractException("warehouse_data_source is not ACTIVE: ${identity.id} status=${identity.status}")
        val registered = registry.findSourceBinding(reference) ?: throw ProducerContractException("exact registered source binding not found: $reference")
        val deployed = authorizedSources(reference)
        if (deployed == null || deployed.bindingRef() != reference || deployed.bindingRef() != registered.reference)
            throw ProducerContractException("exact source binding is not authorized by deployment: $reference")
        return deployed
    }
}
