package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 实际根调用链：最内来源出口、逐层阶段及精确调用输出投影。不会打开已完成的来源输入。 */
class CoreFinalizationPath private constructor(val sourceScope: ScopeId, scopes: List<ScopeId>, calls: Map<ScopeId, CallProjection>) {
    val scopes: List<ScopeId> = java.util.List.copyOf(scopes)
    val calls: Map<ScopeId, CallProjection> = java.util.Collections.unmodifiableMap(LinkedHashMap(calls))

    class CallProjection(val callId: String, outputSlots: Map<String, String>, val measureAlias: String,
        val displayName: String, val valueContract: MetricValueContract) {
        val outputSlots: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(outputSlots))
    }

    companion object {
        fun select(request: ExecutableQueryRequest, graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>): CoreFinalizationPath {
            var scope = ScopeId.root()
            val outer = mutableListOf(scope)
            val calls = linkedMapOf<ScopeId, CallProjection>()
            if (request.computation.root is QueryComputation.Compute) {
                val input = graph.root
                requireTransparentInputs(input, scope)
                val invocation = input.root as? ExecutableInputRoot.Input
                    ?: error("bound query computation must retain its actual invocation scope")
                val body = scope.child(invocation.inputId)
                check(graph.scope(body) === input.additionalInputs[invocation.inputId]) {
                    "bound query computation body is outside its actual input graph"
                }
                scope = body
                outer += scope
            }
            while (true) {
                val input = requireNotNull(graph.scope(scope))
                val root = input.root as? ExecutableInputRoot.Measures ?: break
                if (root.calls.none { it is ExecutableMetricCall.Calculation || it is ExecutableMetricCall.LevelCalculation }) break
                val call = root.calls.singleOrNull() as? ExecutableMetricCall.Calculation
                    ?: CoreFinalizationExpressions.unsupported(path(scope), "core root calculation calls require a single completed call; multiple or grouping-level calls need relational alignment")
                requireTransparentInputs(input, scope)
                if (root.groupingLevels != null || root.outputCondition !is DimensionCondition.Unrestricted ||
                    root.population.mode != "CALLS" || root.population.callIds != listOf(call.invocation.callId))
                    CoreFinalizationExpressions.unsupported(path(scope), "core root calculation calls cannot include grouping levels, output filters or an additional population")
                val child = scope.metric(call.invocation.callId)
                check(graph.scope(child) === call.input) { "bound metric calculation body differs from its exact call scope" }
                val grain = analyses.getValue(child).alignmentGrain()
                check(grain.values.toSet() == root.grouping.values.toSet()) { "bound metric calculation output grain differs from its call grouping" }
                val projection = linkedMapOf<String, String>()
                root.grouping.forEach { (alias, dimension) -> projection[alias] = grain.entries.first { it.value == dimension }.key }
                projection[call.invocation.outputAlias] = call.measureOutput
                calls[scope] = CallProjection(call.invocation.callId, projection, call.invocation.outputAlias, call.invocation.displayName,
                    call.invocation.metric.definition.valueContract.adopted(call.invocation.bindings))
                scope = child
                outer += scope
            }
            return CoreFinalizationPath(scope, outer.asReversed(), calls)
        }

        private fun requireTransparentInputs(input: ExecutableInputScope, scope: ScopeId) {
            if (input.objectRestrictions.isNotEmpty() || input.additionalInputs.values.any { it.inputMode != "ROOT" })
                CoreFinalizationExpressions.unsupported(path(scope), "a core root call cannot cross object restrictions or aligned/imported relations; complete them in an explicit source ROOT input")
        }
        private fun path(scope: ScopeId): List<Any> = listOf<Any>("input") + scope.path + "root"
    }
}
