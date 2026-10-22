package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract

/** 精确采用、实参与阶段的完整输入，不是关系计划。保持scope对象身份。 */
class ExecutableInputScope(val inputMode: String?, val root: ExecutableInputRoot,
    val objectRestrictions: List<ExecutableObjectRestriction>, val additionalInputs: Map<String, ExecutableInputScope>,
    val postOps: List<PostOperationContract.ValidatedOperation>) {
    init { inputMode?.let(ScopedInputContract::requireMode) }
    @get:JsonIgnore val metrics: List<ExecutableMetricInput> get() = metricCalls.filterIsInstance<ExecutableMetricCall.Fact>().map { it.input }
    @get:JsonIgnore val metricCalls: List<ExecutableMetricCall> get() = (root as? ExecutableInputRoot.Measures)?.calls ?: emptyList()
    @get:JsonIgnore val eventInputs: List<ExecutableEventInput> get() = ((root as? ExecutableInputRoot.Members)?.input as? ExecutableMemberInput.Projection)?.let(::listOf) ?: metrics
    @JsonIgnore fun memberComputations(): Map<ScopeId, ExecutableInputScope> = ((root as? ExecutableInputRoot.Members)?.input as? ExecutableMemberInput.Computed)?.let {
        mapOf(it.computationScope to it.computation)
    } ?: emptyMap()
    @JsonIgnore fun measures(): ExecutableInputRoot.Measures = root as? ExecutableInputRoot.Measures ?: throw IllegalArgumentException("input root is not a measure selection")
}
