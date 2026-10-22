package com.olap.prototype.management.warehouse

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.time.Instant

/** 部署方拥有来源登记；不依赖语义治理、物理资产写入或计算引擎。 */
class SourceRegistrationService(private val database: PlatformDatabase, private val connectors: ConnectorRegistry) {
    private val repository = WarehouseRegistryRepository(database)

    fun register(request: WarehouseDataSourceCreateRequest): WarehouseDataSourceRecord {
        val source = SourceConfiguration(code(request.id), request.connectorRef, code(request.dialectId), request.identityParameters, request.runtimeParameters, request.credentialEnvironmentVariables)
        connectors.require(source.connectorRef).access.let { it.configurationParameters.requireConfiguration(source); it.validateConfiguration(source) }
        return database.inTransaction {
            val existing = repository.findDataSource(source.dataSourceId)
            val identity = existing ?: WarehouseDataSourceRecord(source.dataSourceId, text(request.displayName), "ACTIVE", text(request.createdBy), Instant.now())
            if (identity.status != "ACTIVE" || identity.displayName != text(request.displayName))
                throw ProducerContractException("registered source identity conflicts with registration")
            if (existing == null) repository.insertDataSource(identity)
            repository.insertSourceBinding(WarehouseSourceBindingRecord(source, text(request.createdBy), Instant.now()))
            identity
        }
    }
    /** 只登记部署明确授权的绑定，不覆盖任何既有身份或绑定字节。 */
    fun initializeAuthorizedSources(sources: List<SourceConfiguration>) {
        sources.forEach { source -> connectors.require(source.connectorRef).access.let { it.configurationParameters.requireConfiguration(source); it.validateConfiguration(source) } }
        database.inTransaction {
            sources.forEach { source ->
                val existing = repository.findDataSource(source.dataSourceId)
                if (existing == null) repository.insertDataSource(WarehouseDataSourceRecord(source.dataSourceId, source.dataSourceId, "ACTIVE", "source-discovery-deployment", Instant.now()))
                else if (existing.status != "ACTIVE") throw ProducerContractException("registered source conflicts with authorized deployment")
                repository.insertSourceBinding(WarehouseSourceBindingRecord(source, "source-discovery-deployment", Instant.now()))
            }
        }
    }
    fun read(id: String): WarehouseDataSourceRecord = repository.findDataSource(code(id)) ?: throw ProducerContractException("registered source not found")
    fun readBinding(reference: SourceBindingRef): WarehouseSourceBindingRecord = repository.findSourceBinding(reference)
        ?: throw ProducerContractException("registered source binding not found: $reference")
    fun listBindings(dataSourceId: String): List<WarehouseSourceBindingRecord> {
        read(dataSourceId)
        return repository.listSourceBindings(dataSourceId)
    }
    private fun text(value: String): String = value.trim().also { if (it.isBlank()) throw ProducerContractException("registration field is required") }
    private fun code(value: String): String = text(value).also {
        if (!it.matches(Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"))) throw ProducerContractException("registration identity or dialect is invalid")
    }
}
