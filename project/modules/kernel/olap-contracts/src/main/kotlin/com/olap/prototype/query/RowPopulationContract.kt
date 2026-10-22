package com.olap.prototype.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/** 结果组的人口来源，不声明源完整性，也不改变聚合值NULL含义。 */
object RowPopulationContract {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class Selection(val mode: String, callIds: List<String>, inputIds: List<String>?) {
        val callIds: List<String> = java.util.List.copyOf(callIds)
        val inputIds: List<String>? = inputIds?.let { java.util.List.copyOf(it) }
        init {
            require(mode in setOf("CALLS", "SCOPED_UNION")) { "unsupported rowPopulation.mode: $mode" }
            this.callIds.forEach { ScopedInputContract.requireId(it) }
            require(this.callIds.isNotEmpty() && this.callIds.distinct().size == this.callIds.size) { "row population requires explicit distinct completed call ids" }
            if (mode == "SCOPED_UNION") {
                require(!this.inputIds.isNullOrEmpty()) { "SCOPED_UNION requires explicit nonempty inputIds" }
                this.inputIds.forEach { ScopedInputContract.requireId(it) }
                require(this.inputIds.distinct().size == this.inputIds.size) { "SCOPED_UNION inputIds must not repeat" }
            } else require(this.inputIds == null) { "inputIds only belongs to SCOPED_UNION" }
        }
        override fun equals(other: Any?): Boolean = other is Selection && mode == other.mode && callIds == other.callIds && inputIds == other.inputIds
        override fun hashCode(): Int = listOf(mode, callIds, inputIds).hashCode()
    }
    fun calls(ids: List<String>): Selection = Selection("CALLS", ids, null)
    fun parse(node: JsonNode?): Selection {
        require(node != null && node.isObject) { "rowPopulation must be an explicit object" }
        val mode = node["mode"]
        require(mode != null && mode.isTextual) { "rowPopulation.mode must be text" }
        val fields = node.fieldNames().asSequence().toSet()
        val calls = ids(node["callIds"], "rowPopulation.callIds")
        return if (mode.textValue() == "SCOPED_UNION") {
            require(fields == setOf("mode", "callIds", "inputIds")) { "SCOPED_UNION requires mode, callIds and inputIds" }
            Selection("SCOPED_UNION", calls, ids(node["inputIds"], "rowPopulation.inputIds"))
        } else {
            require(fields == setOf("mode", "callIds")) { "CALLS rowPopulation requires only mode and callIds" }
            Selection(mode.textValue(), calls, null)
        }
    }
    private fun ids(node: JsonNode?, field: String): List<String> {
        require(node != null && node.isArray) { "$field must be an explicit array" }
        return node.map { require(it.isTextual) { "$field requires text ids" }; it.textValue() }
    }
    fun requireSelected(selection: Selection, selectedCallIds: Set<String>) { require(selectedCallIds.containsAll(selection.callIds)) { "rowPopulation references calls outside the current scope: ${selection.callIds}" } }
    fun requireInputs(selection: Selection, available: Set<String>) {
        require(selection.mode != "SCOPED_UNION" || available.containsAll(requireNotNull(selection.inputIds))) { "SCOPED_UNION must name direct declared additional inputs" }
    }
    fun contract(): Map<String, Any> = mapOf("formatVersion" to "olap-row-population-contract/v4", "required" to true,
        "modes" to listOf(mapOf("mode" to "CALLS", "fields" to listOf("mode", "callIds"), "groups" to "UNION_OF_EXPLICIT_COMPLETED_CALL_GROUPS"),
            mapOf("mode" to "SCOPED_UNION", "fields" to listOf("mode", "callIds", "inputIds"), "groups" to "EXPLICIT_COMPLETED_CALL_GROUPS_UNION_DIRECT_INPUT_FINAL_GROUPS", "inputs" to "NONEMPTY_UNIQUE_DECLARED_INPUT_IDS_WITH_EXACT_FULL_NONSCALAR_PRIMARY_GRAIN")),
        "scope" to "AFTER_LOCAL_SOURCE_FILTERS_AND_CHILD_LOCAL_POST_OPERATIONS_BEFORE_CURRENT_RESULT_POST_OPERATIONS", "grain" to "ALL_SELECTED_ANALYSIS_DIMENSIONS_NULL_KEYS_MATCH", "unmatchedMetrics" to "NULL_NOT_ZERO",
        "calculationMetric" to "COMPLETED_FROZEN_CALCULATION_CALL_AFTER_ITS_LOCAL_STAGES", "emptyPopulation" to "GROUPED_RETURNS_NO_ROWS_SCALAR_FOLLOWS_SQL_GLOBAL_AGGREGATE_RULES",
        "coverage" to "NOT_PROOF_OF_COMPLETE_SOURCE_RECORDS_KNOWN_EMPTY_FACTS_OR_UNIQUE_BUSINESS_IDENTITIES")
}
