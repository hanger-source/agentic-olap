package com.olap.prototype.governance.contract

import com.olap.prototype.contract.InputContractException
import java.util.Collections

/** 服务读取入口的完整请求；脚本及通信边界共同采用这些正式类型。 */
object GovernanceReadRequests {
    data class AssetVersionRequest(val assetType: String?, val id: String?, val version: Long)
    data class DefinitionDependenciesRequest(val assetType: String, val id: String, val version: Long, val offset: Int, val limit: Int) {
        init { requirePage(offset, limit) }
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "representation" to "REQUIRED_INT32_LIST_INDEX"),
                "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "REQUIRED_INT32_PAGE_LENGTH"),
                "defaults" to "NO_NULL_OR_CONSTRUCTOR_DEFAULTS",
                "wireAbsence" to "PROTO3_INT32_DEFAULT_ZERO_OFFSET_VALID_LIMIT_REJECTED",
                "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
                "pastEnd" to "EMPTY_ITEMS_AND_NO_NEXT_OFFSET",
                "nextOffset" to "ACTUAL_PAGE_END_ONLY_WHEN_FURTHER_DEPENDENCIES_EXIST",
                "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_OR_LOOKAHEAD",
                "scope" to "PRELOADED_EXACT_ASSET_DEPENDENCIES_NOT_DEFINITION_DISCOVERY_SEARCH",
                "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code, "stage" to InputContractException.Rule.PAGINATION.stage,
                    "fieldPath" to "offset OR limit", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
            )
        }
    }
    data class ReleaseIdentityRequest(val releaseId: String, val releaseVersion: String)
    class Page<T>(items: List<T>, val total: Int, val nextOffset: Int?) { val items: List<T> = java.util.List.copyOf(items) }
    class EmptyInput
    data class OperationInput(val operation: String?)
    class DescribeCommandInput(val operation: String?, path: List<String>?, val variant: String?) {
        val path: List<String>? = path?.let { Collections.unmodifiableList(ArrayList(it)) }
    }
    data class PageInput(val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class SourceDiscoveriesInput(val dataSourceId: String?, val catalog: String?, val schemaName: String?, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class SnapshotInput(val snapshotId: String?)
    data class SnapshotPageInput(val snapshotId: String?, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class DiscoveredTablePageInput(val discoveredTableId: String?, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class SourcePageInput(val dataSourceId: String?, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class IdentityInput(val id: String?)
    data class VersionInput(val id: String?, val version: Long)
    data class VersionPageInput(val id: String?, val version: Long, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }
    data class SubjectPageInput(val subjectAreaId: String?, val offset: Int?, val limit: Int?) {
        init { requirePage(offset, limit) }
    }

    fun requirePage(offset: Int?, limit: Int?) {
        pageOffset(offset)
        pageLimit(limit)
    }

    fun pageOffset(value: Int?): Int = (value ?: 0).also {
        if (it < 0) throw InputContractException.pagination("offset", it, 0, Int.MAX_VALUE.toLong())
    }

    fun pageLimit(value: Int?): Int = (value ?: 50).also {
        if (it <= 0) throw InputContractException.pagination("limit", it, 1, Int.MAX_VALUE.toLong())
    }

    fun paginationContract(): Map<String, Any> = mapOf(
        "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "nullMeans" to 0, "representation" to "NULLABLE_INT32_LIST_INDEX"),
        "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "nullMeans" to 50, "representation" to "NULLABLE_INT32_PAGE_LENGTH"),
        "nullPolicy" to "EACH_NULL_FIELD_USES_ITS_DEFAULT_NOT_ALL_ITEMS",
        "wireAbsence" to "ABSENT_OPTIONAL_FIELD_IS_NULL",
        "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
        "pastEnd" to "EMPTY_ITEMS_WITH_ACTUAL_TOTAL_AND_NO_NEXT_OFFSET",
        "nextOffset" to "ACTUAL_PAGE_END_ONLY_WHEN_FURTHER_ITEMS_EXIST",
        "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_OR_LOOKAHEAD",
        "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code, "stage" to InputContractException.Rule.PAGINATION.stage,
            "fieldPath" to "offset OR limit", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
    )

}
