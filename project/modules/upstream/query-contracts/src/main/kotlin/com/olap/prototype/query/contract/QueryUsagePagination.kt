package com.olap.prototype.query.contract

import com.olap.prototype.contract.InputContractException

/** 用法与注记采用同一分页合同；页长是本页上限，下一页只按实际返回事实计算。 */
object QueryUsagePagination {
    const val DEFAULT_OFFSET: Long = 0
    const val DEFAULT_LIMIT: Int = 50

    fun requirePage(offset: Long, limit: Int) {
        if (offset < 0) throw InputContractException.pagination("offset", offset, 0, Long.MAX_VALUE)
        if (limit <= 0) throw InputContractException.pagination("limit", limit, 1, Int.MAX_VALUE.toLong())
    }

    fun nextOffset(offset: Long, returned: Int, hasMore: Boolean): Long? {
        if (!hasMore) return null
        return try { Math.addExact(offset, returned.toLong()) }
        catch (_: ArithmeticException) {
            throw InputContractException.pagination("offset", java.math.BigInteger.valueOf(offset).add(java.math.BigInteger.valueOf(returned.toLong())),
                0, Long.MAX_VALUE)
        }
    }

    fun contract(): Map<String, Any> = mapOf(
        "offset" to mapOf("minimum" to 0L, "maximum" to Long.MAX_VALUE, "representation" to "INT64",
            "json" to "DECIMAL_STRING_OR_INTEGER_TOKEN_NEXT_OFFSET_USES_PROTOBUF_JSON_DECIMAL_STRING"),
        "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "INT32_JVM_PAGE_LIST"),
        "pageLength" to "LIMIT_IS_AN_UPPER_BOUND_NOT_A_REQUIRED_ROW_COUNT_NO_ADDITIONAL_PAGE_SIZE_CAP",
        "lookahead" to "INTERNAL_INT64_LIMIT_PLUS_ONE_NOT_A_SECOND_CLIENT_PAGE_LIMIT",
        "nextOffset" to "ONLY_WHEN_MORE_ITEMS_EXIST_OFFSET_PLUS_ACTUAL_RETURNED_ITEMS_AS_INT64",
        "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION",
        "jsonDefaults" to mapOf("offset" to DEFAULT_OFFSET, "limit" to DEFAULT_LIMIT),
        "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code, "fieldPath" to "offset OR limit",
            "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
    )

    fun jsonFields(): Map<String, String> = mapOf(
        "offset" to "optional INT64 decimal string or integer token in 0..${Long.MAX_VALUE}; default $DEFAULT_OFFSET; nextOffset is reusable decimal text",
        "limit" to "optional integer in 1..${Int.MAX_VALUE}; requested maximum items; default $DEFAULT_LIMIT",
    )
}
