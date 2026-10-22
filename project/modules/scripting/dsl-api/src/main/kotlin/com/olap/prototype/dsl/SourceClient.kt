package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.Capability
import com.olap.prototype.dsl.transport.CapabilityCalls
import com.olap.prototype.source.contract.*
import com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest
import com.olap.prototype.warehouse.contract.SourceCollectionRecord
import com.olap.prototype.governance.contract.DeploymentAvailability
import com.olap.prototype.governance.contract.DeploymentAvailabilityRequest

/** 插件和接入由部署方决定；脚本只调查、选择和使用获准来源。 */
class SourceClient internal constructor(private val calls: CapabilityCalls) {
    @Capability("list-data-sources", "list-data-sources", "READ")
    fun list(request: EmptyRequest): AvailableSources = calls.call(SourceClient::class.java, "list", request)
    @Capability("read-connector-capabilities", "read-connector-capabilities", "READ")
    fun connectorCapabilities(request: ConnectorCapabilitiesRequest): ConnectorCapabilities = calls.call(SourceClient::class.java, "connectorCapabilities", request)
    @Capability("inspect-warehouse", "inspect-warehouse", "READ_SOURCE")
    fun inspect(request: SourceInspection): SourceInspectionResult = calls.call(SourceClient::class.java, "inspect", request)
    @Capability("register-source-dataset", "register-source-dataset", "WRITE")
    fun registerDataset(request: SourceDatasetRegistrationRequest): SourceCollections = calls.call(SourceClient::class.java, "registerDataset", request)
    @Capability("read-deployment-availability", "read-deployment-availability", "READ")
    fun availability(request: DeploymentAvailabilityRequest): DeploymentAvailability = calls.call(SourceClient::class.java, "availability", request)
}
data class SourceCollections(val items: List<SourceCollectionRecord>)
