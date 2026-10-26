package com.olap.prototype.connector

import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceRequestAcceptance
import com.olap.prototype.warehouse.contract.SourceRequestEvidence
import com.olap.prototype.warehouse.contract.SourceResponseState
import com.olap.prototype.warehouse.contract.SourceExecutionState
import com.olap.prototype.warehouse.contract.SourceReadEvidence

/** 一项已授权来源的运行绑定；访问前核对精确部署与本次实际依赖。 */
class SourceProvider(private val source: SourceConfiguration, registry: ConnectorRegistry) {
    private val access: SourceAccess = registry.require(source.connectorRef).access.also {
        it.configurationParameters.requireConfiguration(source)
        it.validateConfiguration(source)
    }
    val dataSourceId: String get() = source.dataSourceId
    val bindingRef: SourceBindingRef get() = source.bindingRef()
    val connectorRef: ConnectorRef get() = source.connectorRef
    val dialectId: String get() = source.dialectId
    val capabilities: SourceCapabilities get() = access.capabilities(source)

    fun execute(request: SourceSqlRequest, control: WarehouseExecutionControl,
        nativeMemoryBytes: Long, stageNanos: (String, Long) -> Unit): SourceExecutionResult {
        var enteredAccess = false
        var completed: SourceExecutionResult? = null
        var stage = "PREPARE_SOURCE"
        return try {
            control.checkCancellation()
            stage = "VERIFY_SOURCE"
            request.sourceRequirements.forEach { it.requireDeployment(source) }
            stage = "PREPARE_SOURCE"
            request.requireCapabilities(dataSourceId, capabilities)
            enteredAccess = true
            stage = "EXECUTE_SQL"
            val result = access.execute(source, request, control, nativeMemoryBytes, stageNanos)
            completed = result
            stage = "MATERIALIZE_RESULT"
            control.checkCancellation()
            stage = "VERIFY_SOURCE"
            request.expectedReadBasis?.let { expected ->
                if (result.readBasis != expected) throw com.olap.prototype.warehouse.SourceGuaranteeException("SOURCE_READ_BASIS_MISMATCH")
            }
            result
        } catch (failure: Exception) {
            val result = completed
            val receipt = result?.request ?: SourceRequestEvidence(null,
                if (enteredAccess) SourceRequestAcceptance.UNKNOWN else SourceRequestAcceptance.NOT_SUBMITTED,
                if (enteredAccess) SourceResponseState.UNKNOWN else SourceResponseState.NOT_RECEIVED,
                if (enteredAccess) SourceExecutionState.UNKNOWN else SourceExecutionState.NOT_SUBMITTED, null, null)
            throw SourceExecutionFailures.exception(bindingRef, request.operation, stage, failure, control.cancellationEvidence(), receipt,
                result?.let { SourceReadEvidence(bindingRef, it.readBasis, it.request) })
        }
    }
}
