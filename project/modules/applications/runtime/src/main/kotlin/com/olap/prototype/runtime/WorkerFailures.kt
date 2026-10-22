package com.olap.prototype.runtime

import com.olap.prototype.connector.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.protocol.WorkerKind
import com.olap.prototype.server.SourceInspectionFailure
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.codec.QuerySourceProto
import com.olap.prototype.wire.query.WorkerFailureFacts
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** 工作进程诊断是私有事实；metadata按操作责任选择可公开的消息与证据。 */
internal object WorkerFailures {
    fun recordLocal(logger: org.slf4j.Logger, kind: WorkerKind, requestId: String, failure: Exception) {
        if (!logger.isDebugEnabled) return
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            if (cause is EngineExecutionException) logger.debug("worker execution context: kind={}, requestId={}, compiledQuery={}, evidenceContext={}",
                kind, requestId, cause.compiledQuery, cause.evidenceContext)
            cause = cause.cause
        }
    }
    /** 从真实失败类型携带的事实投影，不从诊断JSON恢复来源模型。 */
    fun facts(failure: Exception, executionStarted: Boolean?): WorkerFailureFacts {
        val engine = failure as? EngineExecutionException
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        var source: SourceExecutionException? = null
        while (cause != null && seen.add(cause)) {
            if (cause is SourceExecutionException) { source = cause; break }
            cause = cause.cause
        }
        return WorkerFailureFacts.newBuilder().apply {
            (engine?.sourceReadEvidence ?: source?.readEvidence)?.let { setSourceReadEvidence(QuerySourceProto.toProto(it)) }
            (engine?.sourceFailure ?: source?.failure)?.let { setSourceFailure(QuerySourceProto.toProto(it)) }
            (engine?.sourceExecutionStarted ?: executionStarted)?.let(::setSourceExecutionStarted)
            SourceInspectionFailure.facts(failure)?.let(::setInspectionFailure)
            if (engine != null) putAllStageNanos(engine.stageNanos)
        }.build()
    }

    fun project(kind: WorkerKind, failure: Exception): Map<String, Any?> {
        if (kind == WorkerKind.SOURCE_INSPECTION || kind == WorkerKind.INSPECTION)
            return SourceInspectionFailure.project(failure)
        if (failure is InputContractException) return mapOf("stage" to failure.rule.stage, "code" to failure.rule.code,
            "message" to failure.message, "evidenceContext" to failure.details)
        val mappingSeen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var mappingCause: Throwable? = failure
        while (mappingCause != null && mappingSeen.add(mappingCause)) {
            if (mappingCause is NativeCompilationCapabilityException) return mapOf(
                "stage" to if (failure is EngineExecutionException) failure.stage else "RENDER_SQL",
                "code" to mappingCause.code, "message" to mappingCause.message,
                "evidenceContext" to mapOf("capability" to mappingCause.capability.name, "operation" to mappingCause.operation,
                    "sourceExecutionStarted" to false))
            if (mappingCause is NativeAggregateMappingException) return mapOf(
                "stage" to if (failure is EngineExecutionException) failure.stage else "COMPILE_REL",
                "code" to mappingCause.code, "message" to mappingCause.message,
                "evidenceContext" to mapOf("aggregate" to mappingCause.aggregate.name, "inputType" to mappingCause.inputType))
            mappingCause = mappingCause.cause
        }
        if (failure is ProducerContractException) return mapOf("stage" to "CONTRACT_INPUT", "code" to "CONTRACT_VIOLATION", "message" to failure.message)
        if (kind == WorkerKind.ENGINE && failure is EngineExecutionException) return mapOf(
            "type" to failure.javaClass.name, "message" to failure.message, "stage" to failure.stage,
            "code" to failure.code, "evidenceContext" to controlledContext(failure.evidenceContext))
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            when (cause) {
                is SourceExecutionException -> return mapOf("type" to cause.javaClass.name,
                    "stage" to cause.failure.stage, "code" to cause.failure.code)
                is ConnectorUnavailableException -> return mapOf("type" to cause.javaClass.name,
                    "message" to cause.message, "code" to "CONNECTOR_NOT_DEPLOYED",
                    "evidenceContext" to mapOf("connectorRef" to cause.connectorRef))
                is SourceBindingUnavailableException -> return mapOf("type" to cause.javaClass.name,
                    "code" to "SOURCE_BINDING_NOT_DEPLOYED", "dataSourceId" to cause.binding.dataSourceId,
                    "sourceIdentityHash" to cause.binding.sourceIdentityHash,
                    "evidenceContext" to mapOf("dataSourceId" to cause.binding.dataSourceId, "sourceIdentityHash" to cause.binding.sourceIdentityHash))
                is SourceCapabilityException -> return mapOf("type" to cause.javaClass.name, "code" to cause.code,
                    "dataSourceId" to cause.dataSourceId, "operation" to cause.operation, "capability" to cause.capability.name,
                    "evidenceContext" to mapOf("dataSourceId" to cause.dataSourceId, "operation" to cause.operation, "capability" to cause.capability.name))
                is SourceGuaranteeException -> return mapOf("type" to cause.javaClass.name, "code" to cause.code,
                    "stage" to "VERIFY_SOURCE", "evidenceContext" to mapOf("sourceSqlState" to cause.sqlState, "sourceFailureType" to cause.failureType))
                is SourceResultMaterializationException -> return mapOf("type" to cause.javaClass.name, "code" to "EXECUTABLE_SOURCE_RESULT_FAILED")
                is RuntimeResourceLimitException -> return mapOf("type" to cause.javaClass.name, "code" to cause.code)
                is CancellationException -> return mapOf("type" to cause.javaClass.name, "code" to "CANCELLED")
            }
            cause = cause.cause
        }
        if (failure is EngineExecutionException) return mapOf("type" to failure.javaClass.name,
            "message" to failure.message, "stage" to failure.stage, "code" to failure.code, "evidenceContext" to controlledContext(failure.evidenceContext))
        return mapOf("type" to failure.javaClass.name, "message" to failure.message,
            "code" to if (kind == WorkerKind.CANDIDATE_VALUES) "CANDIDATE_VALUE_REQUEST_FAILED" else "WORKER_OPERATION_FAILED")
    }
    private fun controlledContext(context: Map<String, Any?>): Map<String, Any?> = context.filterKeys { it in setOf(
        "fieldPath", "expectedType", "actualType", "expectedKind", "actualKind", "scope", "inputScope", "requirements", "actualKey", "actualOutputs",
        "aggregate", "inputType", "capability", "operation", "inputs", "connectorRef", "dataSourceId", "sourceIdentityHash", "sourceSqlState", "sourceFailureType") }
}
