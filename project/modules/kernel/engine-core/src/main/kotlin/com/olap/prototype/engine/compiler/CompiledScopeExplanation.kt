package com.olap.prototype.engine.compiler

import com.olap.prototype.engine.executable.*
import com.olap.prototype.query.QueryStageAnalysis

/** 只投影已经绑定的计算事实；不重新推导人口、粒度或业务条件。 */
data class CompiledScopeExplanation(
    val scope: QueryStageAnalysis.ScopeId,
    val inputMode: String?,
    val root: Map<String, Any?>,
    val qualifications: List<ExecutableObjectRestriction>,
    val children: List<Child>,
) {
    data class Child(val kind: ExecutableInputGraph.EdgeKind, val name: String, val scope: QueryStageAnalysis.ScopeId)

    companion object {
        fun collect(graph: ExecutableInputGraph): List<CompiledScopeExplanation> = graph.scopes.map { (scope, input) ->
            CompiledScopeExplanation(scope, input.inputMode, root(input.root),
                input.objectRestrictions + input.metrics.flatMap { it.objectRestrictions },
                graph.children(scope).map { Child(it.kind, it.name, it.scope) })
        }

        private fun root(root: ExecutableInputRoot): Map<String, Any?> = when (root) {
            is ExecutableInputRoot.Measures -> mapOf("kind" to "MEASURES", "rowPopulation" to root.population,
                "calls" to root.calls.map { it.invocation.callId }, "timeRange" to root.timeRange,
                "clocks" to root.clocks, "outputCondition" to root.outputCondition)
            is ExecutableInputRoot.Members -> mapOf("kind" to "MEMBERS", "source" to member(root.input))
            is ExecutableInputRoot.Input -> mapOf("kind" to "INPUT", "inputId" to root.inputId)
            is ExecutableInputRoot.Relation -> mapOf("kind" to "RELATION", "inputScope" to root.input.inputScope,
                "outputs" to root.input.projection.outputSlots, "keys" to root.input.projection.keys, "requirements" to root.input.requirements)
            is ExecutableInputRoot.ObjectMeasure -> mapOf("kind" to "OBJECT_MEASURE", "roles" to root.roles)
            is ExecutableInputRoot.AlignMeasurements -> mapOf("kind" to "ALIGN_MEASUREMENTS", "keys" to root.keys,
                "observationKeys" to root.observationKeys, "population" to root.population)
            is ExecutableInputRoot.ObservationStatistics -> mapOf("kind" to "OBSERVATION_STATISTICS",
                "unit" to root.unit, "outerKeys" to root.outerKeys)
            is ExecutableInputRoot.SetInputs -> mapOf("kind" to "SET_INPUTS", "operation" to root.operation,
                "all" to root.all, "inputs" to root.inputs)
        }

        private fun member(input: ExecutableMemberInput): Map<String, Any?> {
            val origin = when (input) {
                is ExecutableMemberInput.Projection -> mapOf("kind" to "PROJECTION", "id" to input.memberSource.id,
                    "version" to input.memberSource.version)
                is ExecutableMemberInput.Computed -> mapOf("kind" to "COMPUTED", "id" to input.source.id,
                    "version" to input.source.version, "computationScope" to input.computationScope)
                is ExecutableMemberInput.Local -> mapOf("kind" to "LOCAL", "inputScope" to input.inputScope)
            }
            return origin + mapOf("outputs" to input.outputs, "condition" to input.condition, "range" to input.range)
        }
    }
}
