package com.olap.prototype.query.contract

import com.olap.prototype.contract.InputContractException
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.QueryExplanationContent
import com.olap.prototype.query.QueryExplanationView

/** 解释投影来自已经编译的有限列表；不执行来源查询，不设额外页长上限。 */
enum class QueryExplanationSection(val paginated: Boolean) {
    SUMMARY(false), SCOPES(true), SOURCES(true), PLAN(true), COMPILATION_SCALE(false), EXECUTION_REQUIREMENTS(false);

    fun requirePage(offset: Int, limit: Int) {
        if (offset < 0 || (!paginated && offset != 0))
            throw InputContractException.pagination("offset", offset, 0, if (paginated) Int.MAX_VALUE.toLong() else 0)
        if (limit <= 0) throw InputContractException.pagination("limit", limit, 1, Int.MAX_VALUE.toLong())
    }

    /** 实时解释和历史结果中的冻结解释使用相同的 section 与切页规则。 */
    fun project(view: QueryExplanationView, offset: Int, limit: Int): QueryExplanationContent {
        requirePage(offset, limit)
        fun <T, R> page(values: List<T>, result: (List<T>, Int, Int?) -> R): R {
            val start = minOf(offset, values.size)
            val end = start + minOf(limit, values.size - start)
            return result(values.subList(start, end).toList(), values.size, end.takeIf { it < values.size })
        }
        return when (this) {
            SUMMARY -> QueryExplanationContent.Summary(view.summary)
            SCOPES -> page(view.scopes) { items, total, next -> QueryExplanationContent.Scopes(items, total, next) }
            SOURCES -> page(view.sources) { items, total, next -> QueryExplanationContent.Sources(items, total, next) }
            PLAN -> page(view.plan) { items, total, next -> QueryExplanationContent.Plan(items, total, next) }
            COMPILATION_SCALE -> QueryExplanationContent.CompilationScale(view.compilationScale)
            EXECUTION_REQUIREMENTS -> QueryExplanationContent.ExecutionRequirements(view.executionRequirements)
        }
    }

    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "offset" to mapOf("minimum" to 0, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_INDEX"),
            "limit" to mapOf("minimum" to 1, "maximum" to Int.MAX_VALUE, "representation" to "INT32_LIST_PAGE_LENGTH"),
            "paginatedSections" to entries.filter { it.paginated }.map { it.name },
            "singleValueSections" to mapOf("sections" to entries.filterNot { it.paginated }.map { it.name },
                "offset" to 0, "limit" to "POSITIVE_PAGE_LENGTH_ACCEPTED_BUT_SINGLE_VALUE_IS_RETURNED"),
            "pageLength" to "REQUESTED_UPPER_BOUND_NO_ADDITIONAL_ITEM_COUNT_CAP",
            "pastEnd" to "EMPTY_ITEMS_WITH_TOTAL_ITEMS_AND_NO_NEXT_OFFSET",
            "nextOffset" to "ACTUAL_PAGE_END_ONLY_WHEN_FURTHER_ITEMS_EXIST",
            "validation" to "NO_OFFSET_PLUS_LIMIT_PRECONDITION_OR_LOOKAHEAD",
            "violation" to mapOf("code" to InputContractException.Rule.PAGINATION.code,
                "fieldPath" to "offset OR limit", "details" to "ACTUAL_NUMERIC_VALUE_AND_EXPECTED_INCLUSIVE_RANGE"),
        )
    }
}

data class QueryExplanationRequest(val query: QuerySelection, val section: QueryExplanationSection, val offset: Int, val limit: Int,
    val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE) {
    init { section.requirePage(offset, limit) }
    companion object { fun contract(): Map<String, Any> = QueryExplanationSection.contract() }
}
