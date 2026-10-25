package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.QueryStageAnalysis

data class ExecutableObjectRestriction(val objectsScope: QueryStageAnalysis.ScopeId, val objectKeys: List<String>,
    val target: Calculation.ObjectTarget, val recordSlots: List<String>, val recordMeanings: Map<String, QueryStageAnalysis.MemberMeaning>) {
    init {
        val keys = when (target) {
            is Calculation.RecordOutputs -> target.keys
            is Calculation.RecordCallOutputs -> target.keys
            is Calculation.EventRole -> emptyList()
        }
        require(recordMeanings.keys.containsAll(keys)) { "record qualification requires its exact bound meanings" }
    }
}
