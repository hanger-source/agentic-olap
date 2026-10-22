package com.olap.prototype.management.warehouse

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.StructureProvenance
import java.time.Instant

class WarehouseDataSourceCreateRequest(val id: String, val displayName: String, val connectorRef: ConnectorRef, val dialectId: String,
    identityParameters: Map<String, Any?>, runtimeParameters: Map<String, Any?>, credentialEnvironmentVariables: Map<String, String>, val createdBy: String) {
    val identityParameters: Map<String, Any?>
    val runtimeParameters: Map<String, Any?>
    val credentialEnvironmentVariables: Map<String, String>
    init {
        val source = SourceConfiguration(id, connectorRef, dialectId, identityParameters, runtimeParameters, credentialEnvironmentVariables)
        this.identityParameters = source.identityParameters
        this.runtimeParameters = source.runtimeParameters
        this.credentialEnvironmentVariables = source.credentialEnvironmentVariables
    }
    override fun equals(other: Any?): Boolean = other is WarehouseDataSourceCreateRequest && id == other.id && displayName == other.displayName && connectorRef == other.connectorRef && dialectId == other.dialectId && identityParameters == other.identityParameters && runtimeParameters == other.runtimeParameters && credentialEnvironmentVariables == other.credentialEnvironmentVariables && createdBy == other.createdBy
    override fun hashCode(): Int = listOf(id, displayName, connectorRef, dialectId, identityParameters, runtimeParameters, credentialEnvironmentVariables, createdBy).hashCode()
}

data class WarehouseDataSourceRecord(val id: String, val displayName: String, val status: String, val createdBy: String, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "warehouse_data_source.id")
        ProducerContracts.requireText(displayName, "warehouse_data_source.display_name")
        ProducerContracts.requireText(status, "warehouse_data_source.status")
        ProducerContracts.requireText(createdBy, "warehouse_data_source.created_by")
    }
}

/** 来源的不可变精确访问绑定；凭据内容仍只来自获授权的运行配置。 */
data class WarehouseSourceBindingRecord(val source: SourceConfiguration, val createdBy: String, val createdAt: Instant) {
    val reference: SourceBindingRef get() = source.bindingRef()
}

data class PhysicalColumnProfileRecord(val id: String, val physicalColumnId: String, val physicalColumnVersion: Long, val profileJson: String,
    val sampleValuesJson: String?, val nullRate: Double?, val distinctCount: Long?, val minValue: String?, val maxValue: String?, val profiledAt: Instant,
    val status: String, val errorMessage: String?, val createdBy: String, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "physical_column_profile.id")
        ProducerContracts.requireText(physicalColumnId, "physical_column_profile.physical_column_id")
        ProducerContracts.requireVersion(physicalColumnVersion, "physical_column_profile.physical_column_version")
        ProducerContracts.requireText(profileJson, "physical_column_profile.profile_json")
        ProducerContracts.requireText(status, "physical_column_profile.status")
        ProducerContracts.requireText(createdBy, "physical_column_profile.created_by")
    }
}
