package com.olap.prototype.query.contract.catalog

import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.contract.QueryCapabilityRequest

/** 对同一精确发布投影的读取；引用不授予新的来源权限。 */
object SemanticCatalogReads {
    enum class Kind { SCENARIO, METRIC, MEMBER, RELATION_FILTER, DIMENSION, TIME_DIMENSION, FILTER }
    data class CatalogReference(val coordinate: PublishedSemanticCatalog.Coordinate, val sha256: String) {
        init { require(sha256.matches(Regex("[a-f0-9]{64}"))) { "exact semantic catalog hash is required" } }
    }
    data class CatalogRequest(val reference: CatalogReference)
    data class AssetReference(val catalog: CatalogReference, val kind: Kind, val id: String, val version: Long) {
        init { require(id.isNotBlank() && version > 0) { "exact semantic asset identity is required" } }
    }
    data class SearchRequest(val publication: QueryCapabilityRequest, val query: String, val kind: Kind?, val offset: Int, val limit: Int) {
        init {
            if (offset < 0) throw InputContractException.pagination("offset", offset, 0, Int.MAX_VALUE.toLong())
            if (limit <= 0) throw InputContractException.pagination("limit", limit, 1, Int.MAX_VALUE.toLong())
        }
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_INDEX"),
                "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_PAGE_LENGTH"),
                "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
                "pastEnd" to "EMPTY_ITEMS_WITH_TOTAL_AND_NO_NEXT_OFFSET",
                "nextOffset" to "ACTUAL_PAGE_END_ONLY_WHEN_FURTHER_MATCHES_EXIST",
                "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_OR_LOOKAHEAD",
                "scope" to "MATCHES_WITHIN_THE_AUTHORIZED_EXACT_PUBLICATION_CATALOG",
                "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code,
                    "fieldPath" to "offset OR limit", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
            )
        }
    }
    data class SearchItem(val reference: AssetReference, val displayName: String, val businessSummary: String)
    class SearchPage(items: List<SearchItem>, val total: Int, val offset: Int, val nextOffset: Int?) {
        val items: List<SearchItem> = java.util.List.copyOf(items)
        override fun equals(other: Any?): Boolean = other is SearchPage && items == other.items && total == other.total && offset == other.offset && nextOffset == other.nextOffset
        override fun hashCode(): Int = listOf(items, total, offset, nextOffset).hashCode()
    }
    data class AssetRequest(val reference: AssetReference)
    data class AssetResult(val reference: AssetReference, val asset: Asset)
    data class Scenario(val id: String, val version: Long, val displayName: String, val sourceJoinContract: PublishedSemanticCatalog.SourceJoinMeaning,
        val metrics: Int, val members: Int, val relationFilters: Int, val dimensions: Int, val timeDimensions: Int, val filters: Int)
    sealed interface Asset {
        data class ScenarioAsset(val value: Scenario) : Asset
        data class MetricAsset(val value: PublishedSemanticCatalog.Metric) : Asset
        data class MemberAsset(val value: PublishedSemanticCatalog.Member) : Asset
        data class RelationFilterAsset(val value: PublishedSemanticCatalog.RelationFilter) : Asset
        data class DimensionAsset(val value: PublishedSemanticCatalog.Dimension) : Asset
        data class TimeDimensionAsset(val value: PublishedSemanticCatalog.TimeDimension) : Asset
        data class FilterAsset(val value: PublishedSemanticCatalog.Filter) : Asset
    }
    sealed interface DefinitionReference {
        data class Calculation(val reference: MetricRef) : DefinitionReference
        data class Fact(val reference: MetricRef) : DefinitionReference
        data class Instance(val reference: MetricInstanceRef) : DefinitionReference
        data class Member(val reference: MemberRef) : DefinitionReference
    }
    data class DefinitionRequest(val catalog: CatalogReference, val reference: DefinitionReference)
    data class DefinitionResult(val catalog: CatalogReference, val definition: PublishedSemanticCatalog.SemanticDependency)
}
