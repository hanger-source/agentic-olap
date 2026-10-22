package com.olap.prototype.query


/** 结果组的人口来源，不声明源完整性，也不改变聚合值NULL含义。 */
object RowPopulationContract {
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
    fun requireSelected(selection: Selection, selectedCallIds: Set<String>) { require(selectedCallIds.containsAll(selection.callIds)) { "rowPopulation references calls outside the current scope: ${selection.callIds}" } }
    fun requireInputs(selection: Selection, available: Set<String>) {
        require(selection.mode != "SCOPED_UNION" || available.containsAll(requireNotNull(selection.inputIds))) { "SCOPED_UNION must name direct declared additional inputs" }
    }
    fun contract() = QueryExplanationView.PopulationContract("olap-row-population-contract/v4", true,
        listOf(QueryExplanationView.PopulationMode("CALLS", listOf("mode", "callIds"), "UNION_OF_EXPLICIT_COMPLETED_CALL_GROUPS", null),
            QueryExplanationView.PopulationMode("SCOPED_UNION", listOf("mode", "callIds", "inputIds"), "EXPLICIT_COMPLETED_CALL_GROUPS_UNION_DIRECT_INPUT_FINAL_GROUPS", "NONEMPTY_UNIQUE_DECLARED_INPUT_IDS_WITH_EXACT_FULL_NONSCALAR_PRIMARY_GRAIN")),
        "AFTER_LOCAL_SOURCE_FILTERS_AND_CHILD_LOCAL_POST_OPERATIONS_BEFORE_CURRENT_RESULT_POST_OPERATIONS", "ALL_SELECTED_ANALYSIS_DIMENSIONS_NULL_KEYS_MATCH", "NULL_NOT_ZERO",
        "COMPLETED_FROZEN_CALCULATION_CALL_AFTER_ITS_LOCAL_STAGES", "GROUPED_RETURNS_NO_ROWS_SCALAR_FOLLOWS_SQL_GLOBAL_AGGREGATE_RULES",
        "NOT_PROOF_OF_COMPLETE_SOURCE_RECORDS_KNOWN_EMPTY_FACTS_OR_UNIQUE_BUSINESS_IDENTITIES")
}
