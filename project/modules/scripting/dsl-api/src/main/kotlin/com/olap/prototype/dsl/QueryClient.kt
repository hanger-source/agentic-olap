package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.Capability
import com.olap.prototype.dsl.transport.CapabilityCalls
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand

/** 构造模型不会执行；只有这些正式调用才读取或改变产品状态。 */
class QueryClient internal constructor(private val calls: CapabilityCalls) {
    @Capability("execute-query", "execute-query", "EXECUTE")
    fun execute(request: QueryExecutionRequest): QueryExecutionReceipt = calls.call(QueryClient::class.java, "execute", request)
    @Capability("validate-query", "validate-query", "READ")
    fun validate(request: QueryValidationRequest): QueryValidationResult = calls.call(QueryClient::class.java, "validate", request)
    @Capability("explain-query", "explain-query", "READ")
    fun explain(request: QueryExplanationRequest): QueryExplanationResult = calls.call(QueryClient::class.java, "explain", request)
    @Capability("save-query", "save-query", "WRITE")
    fun save(request: QueryDefinitionCommand): QueryDefinitionResult = calls.call(QueryClient::class.java, "save", request)
    @Capability("update-query", "update-query", "WRITE")
    fun revise(request: QueryDefinitionCommand): QueryDefinitionResult = calls.call(QueryClient::class.java, "revise", request)
    @Capability("read-query", "read-query", "READ")
    fun read(request: QueryVersionRequest): QueryDefinitionResult = calls.call(QueryClient::class.java, "read", request)
    @Capability("list-queries", "list-queries", "READ")
    fun list(request: QueryListRequest): QueryDefinitionPage = calls.call(QueryClient::class.java, "list", request)
    @Capability("candidate-values", "candidate-values", "READ")
    fun candidates(request: CandidateRequest): CandidateValues = calls.call(QueryClient::class.java, "candidates", request)
}

data class QueryVersionRequest(val queryReference: QueryReference)
data class QueryListRequest(val coordinate: QueryCapabilityRequest, val offset: Int, val limit: Int)
data class QueryDefinitionPage(val items: List<QueryDefinitionResult>, val nextOffset: Int?)
data class QueryExplanationResult(val section: QueryExplanationSection, val content: com.olap.prototype.query.QueryExplanationContent)
