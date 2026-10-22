package com.olap.prototype.warehouse.onboarding

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.warehouse.contract.SourceConfiguration

internal class SealedSourceRegistration(private val registry: WarehouseRegistryService) {
    fun register(source: SourceConfiguration, request: SourceDatasetRegistrationRequest, control: WarehouseExecutionControl,
        inspector: WarehouseSourceInspection): List<SourceCollectionRecord> {
        control.checkCancellation()
        val declaration = request.declaration
        val hash = ProtoBytes.digest(GovernanceProto.toProto(declaration))
        inspector.verifySealedDataset(source, declaration, control)
        control.checkCancellation()
        val records = declaration.collections.map { collection -> SourceCollectionRecord(
            discoveryChildId(source.dataSourceId, "sealed", "${source.sourceIdentityHash()}:$hash:${collection.code}"), source.dataSourceId,
            source.sourceIdentityHash(), hash, declaration.sourceAuthority, declaration.ruleDocumentSha256, declaration.anchor, declaration.scope, collection) }
        return registry.inTransaction {
            control.checkCancellation()
            records.forEach(registry::insertSourceCollection)
            java.util.List.copyOf(records)
        }
    }
}
