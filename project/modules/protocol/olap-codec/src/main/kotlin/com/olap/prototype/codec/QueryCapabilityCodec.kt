package com.olap.prototype.codec

import com.google.protobuf.Any as ProtoAny
import com.olap.prototype.dsl.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.wire.query.*
import com.olap.prototype.wire.query.QueryExecutionRequest as WExecution
import com.olap.prototype.wire.query.QueryValidationRequest as WValidation
import com.olap.prototype.wire.query.QueryExplanationRequest as WExplanation
import com.olap.prototype.wire.query.QueryDefinitionCommand as WSave
import com.olap.prototype.wire.query.QueryVersionRequest as WVersion
import com.olap.prototype.wire.query.QueryListRequest as WList
import com.olap.prototype.wire.query.QueryExecutionReceipt as WReceipt
import com.olap.prototype.wire.query.QueryValidationResult as WValidated
import com.olap.prototype.wire.query.QueryExplanationResult as WExplained
import com.olap.prototype.wire.query.QueryDefinitionResult as WDefinition
import com.olap.prototype.wire.query.QueryDefinitionPage as WDefinitions

/** 每项正式查询操作固定一对具体消息，不根据payload类型名选择实现。 */
object QueryCapabilityCodec {
    private val readOperations = setOf("read-query-result", "read-query-result-metadata", "read-query-result-value", "profile-query-result",
        "read-query-usage", "search-query-usage", "append-query-usage-note")
    fun supports(operation: String): Boolean = operation in readOperations || operation in setOf("execute-query", "validate-query", "explain-query",
        "save-query", "update-query", "read-query", "list-queries", "candidate-values")

    fun encodeRequest(operationId: String, request: Any, maximumBytes: Int): ByteArray = ProtoBytes.encode(ProtoBytes.pack(when (operationId) {
        "execute-query" -> QueryProto.toProto(request as com.olap.prototype.query.contract.QueryExecutionRequest)
        "validate-query" -> QueryProto.toProto(request as com.olap.prototype.query.contract.QueryValidationRequest)
        "explain-query" -> QueryProto.toProto(request as com.olap.prototype.query.contract.QueryExplanationRequest)
        "save-query", "update-query" -> QueryProto.toProto(request as QueryDefinitionCommand)
        "read-query" -> QueryProto.toProto(request as com.olap.prototype.dsl.QueryVersionRequest)
        "list-queries" -> QueryProto.toProto(request as com.olap.prototype.dsl.QueryListRequest)
        "candidate-values" -> CandidateProto.toProto(request as com.olap.prototype.query.contract.CandidateRequest)
        "read-query-result" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.ResultReadRequest)
        "read-query-result-metadata" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.ResultMetadataRequest)
        "read-query-result-value" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.ResultValueRequest)
        "profile-query-result" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.ResultProfileRequest)
        "read-query-usage" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.QueryRunRequest)
        "search-query-usage" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.QueryRunListRequest)
        "append-query-usage-note" -> QueryReadProto.toProto(request as com.olap.prototype.dsl.QueryUsageNoteRequest)
        else -> error("unregistered query operation: $operationId")
    }, maximumBytes), maximumBytes)

    fun decodeRequest(operationId: String, envelope: ProtoAny, maximumBytes: Int, maximumDepth: Int): Any = when (operationId) {
        "execute-query" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WExecution.getDefaultInstance(), maximumBytes, maximumDepth))
        "validate-query" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WValidation.getDefaultInstance(), maximumBytes, maximumDepth))
        "explain-query" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WExplanation.getDefaultInstance(), maximumBytes, maximumDepth))
        "save-query", "update-query" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WSave.getDefaultInstance(), maximumBytes, maximumDepth))
        "read-query" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WVersion.getDefaultInstance(), maximumBytes, maximumDepth))
        "list-queries" -> QueryProto.fromProto(ProtoBytes.unpack(envelope, WList.getDefaultInstance(), maximumBytes, maximumDepth))
        "candidate-values" -> CandidateProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.CandidateRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "read-query-result" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.ResultReadRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "read-query-result-metadata" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.ResultMetadataRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "read-query-result-value" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.ResultValueRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "profile-query-result" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.ResultProfileRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "read-query-usage" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.QueryRunRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "search-query-usage" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.QueryRunListRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        "append-query-usage-note" -> QueryReadProto.fromProto(ProtoBytes.unpack(envelope, com.olap.prototype.wire.query.QueryUsageNoteRequest.getDefaultInstance(), maximumBytes, maximumDepth))
        else -> error("unregistered query operation: $operationId")
    }

    fun encodeResult(operationId: String, result: Any): ProtoAny = ProtoBytes.pack(when (operationId) {
        "execute-query" -> QueryProto.toProto(result as com.olap.prototype.query.contract.QueryExecutionReceipt)
        "validate-query" -> QueryProto.toProto(result as com.olap.prototype.query.contract.QueryValidationResult)
        "explain-query" -> QueryProto.toProto(result as com.olap.prototype.dsl.QueryExplanationResult)
        "save-query", "update-query", "read-query" -> QueryProto.toProto(result as com.olap.prototype.query.contract.QueryDefinitionResult)
        "list-queries" -> QueryProto.toProto(result as com.olap.prototype.dsl.QueryDefinitionPage)
        "candidate-values" -> CandidateProto.toProto(result as com.olap.prototype.query.contract.CandidateValues)
        "read-query-result" -> QueryPagingProto.toProto(result as com.olap.prototype.query.contract.ResultPage)
        "read-query-result-metadata" -> QueryPagingProto.toProto(result as com.olap.prototype.query.contract.ResultMetadataPage)
        "read-query-result-value" -> QueryPagingProto.toProto(result as com.olap.prototype.query.contract.ResultValuePage)
        "profile-query-result" -> QueryPagingProto.toProto(result as com.olap.prototype.query.contract.ResultProfile)
        "read-query-usage" -> QueryUsageProto.toProto(result as com.olap.prototype.query.contract.QueryUsageView)
        "search-query-usage" -> QueryUsageProto.toProto(result as com.olap.prototype.query.contract.QueryUsagePage)
        "append-query-usage-note" -> QueryUsageProto.toProto(result as com.olap.prototype.query.contract.QueryUsageNote)
        else -> error("unregistered query operation: $operationId")
    })

    fun decodeResult(operationId: String, bytes: ByteArray): Any {
        val envelope = ProtoBytes.decodeResponse(bytes, ProtoAny.getDefaultInstance())
        return when (operationId) {
            "execute-query" -> QueryProto.fromProto(ProtoBytes.unpackResponse(envelope, WReceipt.getDefaultInstance()))
            "validate-query" -> QueryProto.fromProto(ProtoBytes.unpackResponse(envelope, WValidated.getDefaultInstance()))
            "explain-query" -> QueryProto.fromProto(ProtoBytes.unpackResponse(envelope, WExplained.getDefaultInstance()))
            "save-query", "update-query", "read-query" -> QueryProto.fromProto(ProtoBytes.unpackResponse(envelope, WDefinition.getDefaultInstance()))
            "list-queries" -> QueryProto.fromProto(ProtoBytes.unpackResponse(envelope, WDefinitions.getDefaultInstance()))
            "candidate-values" -> CandidateProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.CandidateValues.getDefaultInstance()))
            "read-query-result" -> QueryPagingProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.ResultPage.getDefaultInstance()))
            "read-query-result-metadata" -> QueryPagingProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.ResultMetadataPage.getDefaultInstance()))
            "read-query-result-value" -> QueryPagingProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.ResultValuePage.getDefaultInstance()))
            "profile-query-result" -> QueryPagingProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.ResultProfile.getDefaultInstance()))
            "read-query-usage" -> QueryUsageProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.QueryUsageView.getDefaultInstance()))
            "search-query-usage" -> QueryUsageProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.QueryUsagePage.getDefaultInstance()))
            "append-query-usage-note" -> QueryUsageProto.fromProto(ProtoBytes.unpackResponse(envelope, com.olap.prototype.wire.query.QueryUsageNote.getDefaultInstance()))
            else -> error("unregistered query operation: $operationId")
        }
    }
}
