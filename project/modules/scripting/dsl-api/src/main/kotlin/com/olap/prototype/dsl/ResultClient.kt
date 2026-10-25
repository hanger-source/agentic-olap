package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.Capability
import com.olap.prototype.dsl.transport.CapabilityCalls
import com.olap.prototype.query.contract.QueryResultReference
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.*

/** 保存结果读取继续由服务按当前主体授权，不会重新查询来源。 */
class ResultClient internal constructor(private val calls: CapabilityCalls) {
    @Capability("read-query-result", "read-query-result", "READ")
    fun read(request: ResultReadRequest): ResultPage = calls.call(ResultClient::class.java, "read", request)
    @Capability("read-query-result-metadata", "read-query-result", "READ")
    fun metadata(request: ResultMetadataRequest): ResultMetadataPage = calls.call(ResultClient::class.java, "metadata", request)
    @Capability("read-query-result-value", "read-query-result", "READ")
    fun value(request: ResultValueRequest): ResultValuePage = calls.call(ResultClient::class.java, "value", request)
    @Capability("profile-query-result", "profile-query-result", "READ")
    fun profile(request: ResultProfileRequest): ResultProfile = calls.call(ResultClient::class.java, "profile", request)
    @Capability("read-query-usage", "read-query-usage", "READ")
    fun run(request: QueryRunRequest): QueryUsageView = calls.call(ResultClient::class.java, "run", request)
    @Capability("search-query-usage", "search-query-usage", "READ")
    fun history(request: QueryRunListRequest): QueryUsagePage = calls.call(ResultClient::class.java, "history", request)
    @Capability("append-query-usage-note", "append-query-usage-note", "WRITE")
    fun appendNote(request: QueryUsageNoteRequest): QueryUsageNote = calls.call(ResultClient::class.java, "appendNote", request)
}

data class ResultReadRequest(val resultReference: QueryResultReference, val offset: Int, val limit: Int, val columnIds: List<String>?)
data class ResultMetadataRequest(val resultReference: QueryResultReference, val section: ResultMetadataSection, val scopePath: String?,
    val explanationSection: com.olap.prototype.query.contract.QueryExplanationSection?, val offset: Int, val limit: Int) {
    init { require(offset >= 0 && limit in 1..2000); require((section == ResultMetadataSection.EXPLANATION) == (explanationSection != null)); require(scopePath == null || section == ResultMetadataSection.SCOPE_INPUT_COLUMNS) }
}
data class ResultValueRequest(val resultReference: QueryResultReference, val valueReference: ResultValueReference, val offset: Int, val limit: Int)
data class ResultProfileRequest(val resultReference: QueryResultReference, val columnIds: List<String>)
data class QueryRunRequest(val runId: String, val offset: Long, val limit: Int) {
    init { QueryUsagePagination.requirePage(offset, limit) }
    companion object { fun contract(): Map<String, Any> = QueryUsagePagination.contract() }
}
data class QueryRunListRequest(val coordinate: QueryCapabilityRequest, val text: String?, val offset: Long, val limit: Int) {
    init { QueryUsagePagination.requirePage(offset, limit) }
    companion object { fun contract(): Map<String, Any> = QueryUsagePagination.contract() }
}
data class QueryUsageNoteRequest(val runId: String, val text: String)
