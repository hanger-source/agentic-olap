package com.olap.prototype.connector

import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.*
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import java.io.IOException
import java.util.concurrent.CancellationException

/** 在已知实际来源边界建立安全失败事实，不推断数据库产品或泄露驱动原文。 */
object SourceExecutionFailures {
    fun exception(binding: SourceBindingRef, operation: String, stage: String, failure: Exception, cancellation: SourceCancellationEvidence,
        request: SourceRequestEvidence, readEvidence: SourceReadEvidence?): SourceExecutionException {
        if (failure is SourceExecutionException) {
            require(failure.failure.binding == binding && failure.failure.operation == operation) { "source failure evidence differs from its actual execution" }
            return failure
        }
        val (category, code, message) = when (failure) {
            is SourceBindingUnavailableException -> Triple(SourceFailureCategory.CONTRACT, "SOURCE_BINDING_NOT_DEPLOYED", "exact source binding is not deployed")
            is SourceCapabilityException -> Triple(SourceFailureCategory.CONTRACT, failure.code, "source operation requires an unsupported capability")
            is SourceGuaranteeException -> Triple(SourceFailureCategory.CONTRACT, failure.code, "compiled source guarantee failed")
            is SourceResultMaterializationException -> Triple(SourceFailureCategory.MATERIALIZATION, failure.code, "source result cannot be materialized")
            is RuntimeResourceLimitException -> Triple(SourceFailureCategory.RESOURCE_LIMIT, failure.code,
                if (request.execution == SourceExecutionState.SUCCEEDED) "source result processing exceeded its resource budget"
                else "source execution exceeded its resource budget")
            is CancellationException, is InterruptedException -> when {
                request.execution == SourceExecutionState.SUCCEEDED -> Triple(SourceFailureCategory.CANCELLATION, "SOURCE_RESULT_WAIT_INTERRUPTED", "request interrupted after the source completed successfully")
                cancellation is SourceCancellationEvidence.Confirmed -> Triple(SourceFailureCategory.CANCELLATION, "SOURCE_CANCELLED", "source cancellation was confirmed by its terminal receipt")
                else -> Triple(SourceFailureCategory.CANCELLATION, "SOURCE_OUTCOME_UNKNOWN", "source cancellation has no confirmed terminal receipt")
            }
            is SQLInvalidAuthorizationSpecException -> Triple(SourceFailureCategory.AUTHENTICATION, "SOURCE_AUTHENTICATION_FAILED", "source authentication failed")
            is SQLNonTransientConnectionException, is SQLTransientConnectionException -> Triple(SourceFailureCategory.CONNECTION, "SOURCE_CONNECTION_FAILED", "source connection failed")
            is SQLException -> Triple(SourceFailureCategory.SQL, "SOURCE_SQL_FAILED",
                if (request.execution == SourceExecutionState.SUCCEEDED) "JDBC processing failed after source result completion" else "source SQL execution failed")
            is IOException -> Triple(SourceFailureCategory.TRANSPORT, "SOURCE_TRANSPORT_FAILED",
                if (request.execution == SourceExecutionState.SUCCEEDED) "source transport failed after result completion" else "source transport failed")
            else -> Triple(SourceFailureCategory.UNKNOWN, "SOURCE_EXECUTION_FAILED",
                if (request.execution == SourceExecutionState.SUCCEEDED) "source completed but subsequent result handling failed; consult authorized diagnostics"
                else "source execution failed; consult authorized diagnostics")
        }
        val sql = when (failure) {
            is SQLException -> failure
            is SourceResultMaterializationException -> failure.cause as? SQLException
            else -> null
        }
        return SourceExecutionException(SourceFailureEvidence(category, stage, code, message, binding, operation,
            (failure as? SourceCapabilityException)?.capability?.name,
            sql?.sqlState ?: (failure as? SourceGuaranteeException)?.sqlState, sql?.errorCode, request,
            if ((failure is CancellationException || failure is InterruptedException) && cancellation is SourceCancellationEvidence.NotRequested)
                SourceCancellationEvidence.Unconfirmed("execution was interrupted; source cancellation is unconfirmed") else cancellation), failure, readEvidence)
    }
}
