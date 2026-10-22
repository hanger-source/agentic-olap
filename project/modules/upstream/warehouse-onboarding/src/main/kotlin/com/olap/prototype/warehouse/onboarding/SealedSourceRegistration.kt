package com.olap.prototype.warehouse.onboarding

import com.fasterxml.jackson.databind.DeserializationFeature
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.SealedSourceDataset
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

internal class SealedSourceRegistration(private val registry: WarehouseRegistryService) {
    fun register(source: SourceConfiguration, request: SourceDatasetRegistrationRequest, control: WarehouseExecutionControl,
        inspector: WarehouseSourceInspection): List<SourceCollectionRecord> {
        control.checkCancellation()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.declarationJson.toByteArray(Charsets.UTF_8)))
        if (hash != request.sha256) throw ProducerContractException("source declaration SHA256 differs from exact UTF8 document")
        val declaration: SealedSourceDataset = try {
            OlapJsonUtils.mapper().readerFor(SealedSourceDataset::class.java).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(request.declarationJson)
        } catch (failure: IOException) { throw ProducerContractException("invalid sealed source declaration", failure) }
        inspector.verifySealedDataset(source, declaration, control)
        control.checkCancellation()
        val records = declaration.collections.map { collection -> SourceCollectionRecord(
            discoveryChildId(source.dataSourceId, "sealed", "${source.sourceIdentityHash()}:${request.sha256}:${collection.code}"), source.dataSourceId,
            source.sourceIdentityHash(), request.sha256, declaration.sourceAuthority, declaration.ruleDocumentSha256, declaration.anchor, declaration.scope, collection) }
        return registry.inTransaction {
            control.checkCancellation()
            records.forEach(registry::insertSourceCollection)
            java.util.List.copyOf(records)
        }
    }
}
