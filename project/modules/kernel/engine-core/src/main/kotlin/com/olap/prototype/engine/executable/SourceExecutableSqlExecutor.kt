package com.olap.prototype.engine.executable

import com.olap.prototype.connector.SourceBindingUnavailableException
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceExecutionResult
import com.olap.prototype.connector.SourceExecutionFailures
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.*

/** 确定性编译与来源访问衔接，不选择JDBC或HTTP传输。 */
class SourceExecutableSqlExecutor(private val sources: (SourceBindingRef) -> SourceProvider?,
    private val resultLimits: ResultMaterializationLimits, private val nativeMemoryBytes: Long,
    private val expectedReadBasis: SourceReadBasis.Versioned?) : ExecutableSqlExecutor {
    init { require(nativeMemoryBytes > 0) { "positive native memory budget is required" } }
    override fun execute(loweringResult: ExecutableLoweringResult, context: OlapQueryContext, executionControl: WarehouseExecutionControl): SourceExecutionResult {
        val binding = JdbcSourceRequirement.requireSingleBinding(loweringResult.sourceRequirements)
        var stage = "PREPARE_SOURCE"
        try {
            val source = sources(binding) ?: throw SourceBindingUnavailableException(binding)
            stage = "VERIFY_SOURCE"
            if (source.bindingRef != binding || source.dataSourceId != loweringResult.dataSourceId || source.connectorRef != loweringResult.connectorRef || source.dialectId != loweringResult.dialect)
                throw SourceGuaranteeException("SOURCE_BINDING_DRIFT")
            stage = "PREPARE_SOURCE"
            context.addAuditLog("SourceExecutableSqlExecutor: dataSource=${source.dataSourceId}, dialect=${source.dialectId}")
            return source.execute(loweringResult.toSourceRequest("execute-query", expectedReadBasis), executionControl,
                resultLimits, nativeMemoryBytes, context::recordStageDuration)
        } catch (failure: Exception) {
            throw SourceExecutionFailures.exception(binding, "execute-query", stage, failure, executionControl.cancellationEvidence(),
                SourceRequestEvidence(null, SourceRequestAcceptance.NOT_SUBMITTED, SourceResponseState.NOT_RECEIVED,
                    SourceExecutionState.NOT_SUBMITTED, null, null), null)
        }
    }
}
