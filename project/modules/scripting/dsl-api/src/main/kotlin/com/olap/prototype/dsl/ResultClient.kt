package com.olap.prototype.dsl

import com.olap.prototype.contract.InputContractException
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
    init {
        if (offset < 0 || (section in singleValueSections && offset != 0))
            throw InputContractException.pagination("offset", offset, 0, if (section in singleValueSections) 0 else Int.MAX_VALUE.toLong())
        if (limit <= 0) throw InputContractException.pagination("limit", limit, 1, Int.MAX_VALUE.toLong())
        if ((section == ResultMetadataSection.EXPLANATION) != (explanationSection != null))
            throw InputContractException.violation(InputContractException.Rule.DECODE_INPUT, listOf("explanationSection"),
                "explanationSection is required exactly when section is EXPLANATION", "PRESENT_EXACTLY_FOR_EXPLANATION",
                if (explanationSection == null) "MISSING" else "UNEXPECTED")
        if (scopePath != null && section != ResultMetadataSection.SCOPE_INPUT_COLUMNS)
            throw InputContractException.violation(InputContractException.Rule.DECODE_INPUT, listOf("scopePath"),
                "scopePath is only allowed for SCOPE_INPUT_COLUMNS", "ABSENT_OUTSIDE_SCOPE_INPUT_COLUMNS", "PRESENT")
        explanationSection?.requirePage(offset, limit)
    }
    companion object {
        private val singleValueSections = setOf(ResultMetadataSection.RUN, ResultMetadataSection.SOURCE_EVIDENCE,
            ResultMetadataSection.PUBLICATION, ResultMetadataSection.QUERY_INTENT)
        fun contract(): Map<String, Any> = mapOf(
            "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_INDEX"),
            "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_PAGE_LENGTH"),
            "singleValueSections" to mapOf("sections" to singleValueSections.map { it.name }, "offset" to 0),
            "paginatedSections" to listOf("COLUMNS", "INPUT_COLUMNS", "SOURCE_SYMBOLS", "SCOPE_INPUT_COLUMNS"),
            "scopePath" to "ONLY_FOR_SCOPE_INPUT_COLUMNS_ABSENT_SELECTS_SCOPE_INDEX_PRESENT_SELECTS_ITS_COLUMNS",
            "explanationSection" to "REQUIRED_EXACTLY_FOR_EXPLANATION",
            "explanation" to QueryExplanationSection.contract(),
            "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
            "pastEnd" to "EMPTY_ITEMS_WITH_TOTAL_ITEMS_AND_NO_NEXT_OFFSET",
            "nextOffset" to "ACTUAL_PAGE_END_ONLY_WHEN_FURTHER_ITEMS_EXIST",
            "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_OR_LOOKAHEAD",
            "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code,
                "fieldPath" to "offset OR limit", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
        )
    }
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
