package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.*
import com.olap.prototype.codec.ProtoStringRepresentationException
import com.olap.prototype.contract.ContractSelectionException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.governance.contract.AnalysisDimensionEventMappingConflictException
import com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord
import com.olap.prototype.governance.contract.MemberAdoptionConflictException
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.inspection.WarehouseInspectionException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** 正式错误与使用记录共用脱敏规则；插件/连接异常不按包名猜测可公开性。 */
internal object ServerFailures {
    data class Projection(val stage: String, val code: String, val message: String, val details: Map<String, Any?>)

    fun project(failure: Throwable, stage: String, unknownCode: String): Projection {
        if (failure is com.olap.prototype.server.scripting.ScriptException) return Projection("SCRIPT", failure.code, failure.message,
            mapOf("sessionId" to failure.sessionId, "executionId" to failure.executionId, "state" to failure.state).filterValues { it != null })
        if (failure is ResultPersistenceFailure) return Projection("RESULT_DELIVERY", "RESULT_DELIVERY_FAILED",
            "result persistence failed after execution; inspect the exact run without replaying",
            mapOf("runId" to failure.receipt.runId, "executionReceipt" to failure.receipt.copy(result = null)))
        if (failure is JsonMappingException) return contract(ProducerContractException("declared input does not match its contract", failure), stage)
        if (failure is WorkerPool.WorkerFailure) return worker(failure, stage, unknownCode)
        if (failure is ProtoStringRepresentationException) return Projection("ENCODE_MODEL", "CONTRACT_VIOLATION", requireNotNull(failure.message),
            mapOf("fieldPath" to failure.fieldPath, "expectedKind" to "WELL_FORMED_UNICODE_TEXT", "actualKind" to "UNPAIRED_UTF16_SURROGATE"))
        if (failure is InputContractException) return Projection(failure.rule.stage, failure.rule.code, failure.message, failure.details)
        val actualStage = if (failure is EngineExecutionException) failure.stage else stage
        val code = if (failure is EngineExecutionException) failure.code else unknownCode
        if (failure is EngineExecutionException && failure.sourceFailure != null) {
            val evidence = requireNotNull(failure.sourceFailure)
            return Projection(evidence.stage, evidence.code, evidence.safeMessage, mapOf("sourceFailure" to evidence,
                "sourceReadEvidence" to failure.sourceReadEvidence))
        }
        if (failure is EngineExecutionException && code == "SOURCE_BINDING_CONFLICT")
            return Projection(actualStage, code, "one query requires one exact source binding", select(failure.evidenceContext, "inputs"))
        if (failure is EngineExecutionException && code == "SOURCE_BINDING_NOT_DEPLOYED")
            return Projection(actualStage, code, "exact source binding is not deployed", select(failure.evidenceContext, "dataSourceId", "sourceIdentityHash"))
        if (failure is EngineExecutionException && code == "RELATION_INPUT_CONTRACT_MISMATCH")
            return Projection(actualStage, code, failure.message, select(failure.evidenceContext, "scope", "inputScope", "requirements", "actualKey", "actualOutputs"))
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && visited.add(cause)) {
            when (cause) {
                is MemberAdoptionConflictException -> return Projection(actualStage, "MEMBER_ADOPTION_CONFLICT", requireNotNull(cause.message),
                    mapOf("source" to cause.source, "expectedDefinition" to cause.expectedDefinition, "actualDefinition" to cause.actualDefinition))
                is NativeAggregateMappingException -> return Projection(actualStage, cause.code, requireNotNull(cause.message),
                    mapOf("aggregate" to cause.aggregate.name, "inputType" to cause.inputType))
                is NativeCompilationCapabilityException -> return Projection(actualStage, cause.code, requireNotNull(cause.message),
                    mapOf("capability" to cause.capability.name, "operation" to cause.operation))
                is com.olap.prototype.governance.contract.ReleaseClosureConflictException -> return Projection(actualStage, cause.code,
                    "frozen release contains conflicting exact dependencies", mapOf("coordinate" to cause.coordinate, "paths" to cause.paths))
                is SourceExecutionException -> return Projection(cause.failure.stage, cause.failure.code, cause.failure.safeMessage,
                    mapOf("sourceFailure" to cause.failure, "sourceReadEvidence" to cause.readEvidence))
                is ConnectorUnavailableException -> return Projection(actualStage, "CONNECTOR_NOT_DEPLOYED", "exact connector is not deployed", mapOf("connectorRef" to cause.connectorRef))
                is SourceBindingUnavailableException -> return Projection(actualStage, "SOURCE_BINDING_NOT_DEPLOYED", "exact source binding is not deployed",
                    mapOf("dataSourceId" to cause.binding.dataSourceId, "sourceIdentityHash" to cause.binding.sourceIdentityHash))
                is SourceCapabilityException -> return Projection(actualStage, cause.code, "source operation requires an unsupported capability",
                    mapOf("dataSourceId" to cause.dataSourceId, "operation" to cause.operation, "capability" to cause.capability.name))
                is SourceGuaranteeException -> return Projection(actualStage, cause.code, "compiled source guarantee failed",
                    mapOf("sqlState" to cause.sqlState, "failureType" to cause.failureType).filterValues { it != null })
                is WarehouseInspectionException -> return Projection(actualStage, cause.code, "source inspection failed",
                    mapOf("dataSourceId" to cause.dataSourceId, "sqlState" to cause.sqlState, "failureType" to cause.failureType).filterValues { it != null })
                is SourceResultMaterializationException -> return Projection(actualStage, "EXECUTABLE_SOURCE_RESULT_FAILED", "source result cannot be materialized", emptyMap())
                is AnalysisDimensionEventMappingConflictException -> {
                    val existing = cause.existing
                    return Projection(actualStage, "RESOURCE_CONFLICT", cause.message, mapOf(
                        "resourceType" to "ANALYSIS_DIMENSION_EVENT_MAPPING", "resourceId" to existing.id,
                        "analysisDimensionId" to existing.analysisDimensionId, "analysisDimensionVersion" to existing.analysisDimensionVersion,
                        "levelCode" to existing.levelCode, "eventReference" to mapOf("id" to existing.eventId, "version" to existing.eventVersion),
                        "existingMapping" to mappingReference(existing), "requestedMapping" to mappingReference(cause.requested),
                        "readOperation" to "read-analysis-dimension-mappings"))
                }
                is ContractSelectionException -> return Projection(actualStage, "INVALID_REQUEST", cause.message, emptyMap())
                is ProducerContractException -> return contract(cause, actualStage)
            }
            cause = cause.cause
        }
        val projected = when {
            code == "CANCELLED" -> Projection(actualStage, code, "operation was cancelled", emptyMap())
            failure is SecurityException -> Projection(actualStage, "AUTHORIZATION_DENIED", "operation or resource is not authorized", emptyMap())
            failure is CancellationException -> Projection(actualStage, "CANCELLED", "operation was cancelled", emptyMap())
            failure is RuntimeResourceLimitException -> Projection(actualStage, failure.code, "operation exceeded its resource budget", emptyMap())
            failure is EngineExecutionException && publicSemanticStage(actualStage) -> Projection(actualStage, code, failure.message, emptyMap())
            else -> Projection(actualStage, code, "operation failed; consult the authorized service diagnostics", emptyMap())
        }
        return if (failure is EngineExecutionException && failure.sourceReadEvidence != null)
            projected.copy(details = projected.details + ("sourceReadEvidence" to failure.sourceReadEvidence)) else projected
    }

    private fun contract(failure: ProducerContractException, stage: String): Projection {
        val code = "CONTRACT_VIOLATION"
        val mapping = failure.cause as? JsonMappingException ?: return Projection(stage, code, failure.message, emptyMap())
        val path = mapping.path.mapNotNull { it.fieldName ?: if (it.index >= 0) it.index else null }
        var message = "declared input does not match the described contract"
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause = mapping.cause
        while (cause != null && visited.add(cause)) {
            if (cause is ProducerContractException) { message = cause.message; break }
            cause = cause.cause
        }
        return Projection(stage, code, message, mapOf("fieldPath" to path))
    }

    private fun worker(failure: WorkerPool.WorkerFailure, stage: String, unknownCode: String): Projection {
        val error = failure.error
        val actualStage = if (error.path("stage").isTextual) error.path("stage").textValue() else stage
        val code = if (error.path("code").isTextual) error.path("code").textValue() else unknownCode
        val inputRule = InputContractException.Rule.entries.firstOrNull { it.code == code && it.stage == actualStage }
        if (inputRule != null) return Projection(actualStage, code, error.required("message").textValue(),
            select(error.path("evidenceContext"), "fieldPath", "expectedType", "actualType", "expectedKind", "actualKind"))
        val facts = if (failure.kind == WorkerPool.Kind.ENGINE) error.path("evidenceContext") else error
        failure.sourceFailure?.let { source ->
            return Projection(source.stage, source.code, source.safeMessage,
                mapOf("sourceFailure" to source, "sourceReadEvidence" to failure.sourceReadEvidence))
        }
        val projected = when {
            code == "CANCELLED" -> Projection(actualStage, code, "operation was cancelled", emptyMap())
            code == "CONTRACT_VIOLATION" && actualStage == "ENCODE_MODEL" -> Projection(actualStage, code, error.required("message").textValue(),
                select(error.path("evidenceContext"), "fieldPath", "expectedKind", "actualKind"))
            code == "WORKER_RESULT_ENCODING_FAILED" -> Projection(actualStage, code, "source execution succeeded but worker result encoding failed",
                select(error.path("evidenceContext"), "fieldPath", "expectedKind", "actualKind"))
            code == "CONTRACT_VIOLATION" -> Projection(actualStage, code, error.required("message").textValue(), emptyMap())
            code == "RELATION_INPUT_CONTRACT_MISMATCH" -> Projection(actualStage, code, error.required("message").textValue(),
                select(facts, "scope", "inputScope", "requirements", "actualKey", "actualOutputs"))
            code == "NATIVE_AGGREGATE_MAPPING_UNAVAILABLE" -> Projection(actualStage, code,
                "native aggregate mapping is unavailable for the declared input type", select(facts, "aggregate", "inputType"))
            code == "NATIVE_COMPILATION_CAPABILITY_MISSING" -> Projection(actualStage, code,
                "native SQL operation requires an unsupported compilation capability", select(facts, "capability", "operation"))
            code == "SOURCE_BINDING_CONFLICT" -> Projection(actualStage, code, "one query requires one exact source binding", select(facts, "inputs"))
            code == "CONNECTOR_NOT_DEPLOYED" -> Projection(actualStage, code, "exact connector is not deployed", select(error.path("evidenceContext"), "connectorRef"))
            code == "SOURCE_BINDING_NOT_DEPLOYED" -> Projection(actualStage, code, "exact source binding is not deployed", select(facts, "dataSourceId", "sourceIdentityHash"))
            code == "SOURCE_CAPABILITY_MISSING" -> Projection(actualStage, code, "source operation requires an unsupported capability", select(facts, "dataSourceId", "operation", "capability"))
            code == "EXECUTABLE_SOURCE_RESULT_FAILED" -> Projection(actualStage, code, "source result cannot be materialized", emptyMap())
            actualStage == "VERIFY_SOURCE" -> Projection(actualStage, code, "compiled source guarantee failed", select(facts, "sourceSqlState", "sourceFailureType"))
            failure.kind in setOf(WorkerPool.Kind.INSPECTION, WorkerPool.Kind.SOURCE_INSPECTION) -> Projection(actualStage, code, "source inspection failed", select(facts, "dataSourceId", "sqlState", "failureType"))
            failure.kind == WorkerPool.Kind.QUERY || publicSemanticStage(actualStage) -> {
                check(error.path("message").isTextual) { "semantic worker failure requires its public contract message" }
                Projection(actualStage, code, error.path("message").textValue(), emptyMap())
            }
            else -> Projection(actualStage, code, "operation failed; consult the authorized service diagnostics", emptyMap())
        }
        return failure.sourceReadEvidence?.let {
            projected.copy(details = projected.details + ("sourceReadEvidence" to it))
        } ?: projected
    }

    private fun publicSemanticStage(stage: String) = stage in setOf("AUTHORIZE_INPUT", "BIND_INPUT", "ANALYZE_STAGES")
    private fun select(facts: JsonNode, vararg keys: String): Map<String, Any?> = keys.mapNotNull { key ->
        facts.get(key)?.takeUnless { it.isNull }?.let { key to it }
    }.toMap()
    private fun select(facts: Map<String, Any?>, vararg keys: String): Map<String, Any?> = keys.mapNotNull { key -> facts[key]?.let { key to it } }.toMap()
    private fun mappingReference(mapping: AnalysisDimensionEventMappingRecord): Map<String, Any?> = mapOf(
        "id" to mapping.id, "bindingId" to mapping.bindingId, "bindingVersion" to mapping.bindingVersion,
        "alignmentStatus" to mapping.alignmentStatus, "alignmentId" to mapping.alignmentId).filterValues { it != null }
}
