package com.olap.prototype.query.contract

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.warehouse.contract.SourceBindingRef
import java.time.Instant

/** 候选来自发布定义；分页和目标均显式，不接受来源SQL或连接。 */
data class CandidateRequest(val publication: QueryCapabilityRequest, val capabilityHash: String,
    val target: CandidateTarget, val offset: Int, val limit: Int) {
    init {
        require(capabilityHash.matches(Regex("[0-9a-f]{64}"))) { "exact candidate capability is required" }
        if (offset < 0) throw InputContractException.pagination("offset", offset, 0, Int.MAX_VALUE.toLong())
        if (limit <= 0) throw InputContractException.pagination("limit", limit, 1, Int.MAX_VALUE.toLong())
    }
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "representation" to "INT32_DISTINCT_VALUE_INDEX"),
            "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "INT32_PAGE_LENGTH"),
            "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
            "values" to "ONE_SCALAR_COLUMN_DISTINCT_NON_NULL_VALUES_IN_NATIVE_ORDER",
            "lookahead" to "ONE_ADDITIONAL_VALUE_REQUESTED_USING_INT64_SQL_FETCH_TO_ESTABLISH_WHETHER_A_NEXT_PAGE_EXISTS",
            "materialization" to "DEPLOYED_RESULT_ROWS_VALUES_BYTES_NATIVE_MEMORY_AND_REQUEST_DEADLINE_APPLY_INCLUDING_LOOKAHEAD_ROWS_MUST_FIT_A_JVM_LIST",
            "depth" to "ONE_SCALAR_VALUE_PER_ROW",
            "pastEnd" to "EMPTY_VALUES_WITH_NO_NEXT_OFFSET",
            "nextOffset" to "ACTUAL_RETURNED_VALUE_COUNT_PLUS_OFFSET_ONLY_WHEN_MORE_VALUES_EXIST_MUST_FIT_INT32_OR_INVALID_PAGINATION",
            "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_NO_TRUNCATED_LOOKAHEAD_OR_AUTOMATIC_REEXECUTION",
            "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code,
                "fieldPath" to "offset OR limit OR nextOffset", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
        )
    }
}
sealed interface CandidateTarget {
    data class DimensionProvider(val filterRefId: String, val levelCode: String) : CandidateTarget
    data class MemberOutput(val call: Calculation.Members) : CandidateTarget
}
data class CandidateSource(val binding: SourceBindingRef, val connectorRef: ConnectorRef)
data class CandidateResolution(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String,
    val queryHash: String, val sources: List<CandidateSource>, val valueType: ScalarValueType)
data class CandidateValues(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String,
    val queryHash: String, val target: CandidateTarget, val valueType: ScalarValueType, val values: List<Calculation.Scalar>,
    val offset: Int, val nextOffset: Int?, val observedAt: Instant, val scope: String)
