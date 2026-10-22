package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.QueryStageAnalysis

data class ExecutableObjectRestriction(val objectsScope: QueryStageAnalysis.ScopeId, val objectKeys: List<String>,
    val target: Calculation.ObjectTarget, val recordSlots: List<String>, val recordMeanings: Map<String, QueryStageAnalysis.MemberMeaning>) {
    init { require(target !is Calculation.RecordOutputs || recordMeanings.keys.containsAll(target.keys)) { "record qualification requires its exact bound meanings" } }
}
