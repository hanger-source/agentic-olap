package com.olap.prototype.engine.executable

import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.Child
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.IdentityHashMap

/** 以绑定图的精确实例身份分析阶段；活动祖先和完成缓存仅属本次分析。 */
object ExecutableStageAnalysis {
    fun analyze(graph: ExecutableInputGraph): Map<ScopeId, Analysis> = Session(graph).analyze()
    private class Session(private val graph: ExecutableInputGraph) {
        private val result = linkedMapOf<ScopeId, Analysis>()
        private val ancestors = Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>())
        fun analyze(): Map<ScopeId, Analysis> { analyze(graph.root, ScopeId.root()); return result.toMap() }
        private fun analyze(input: ExecutableInputScope, scope: ScopeId): Analysis {
            check(graph.scope(scope) === input) { "analysis scope differs from the bound input graph" }
            result[scope]?.let { return it }
            require(scope.depth() <= ScopedInputContract.MAX_DEPTH && result.size <= ScopedInputContract.MAX_INPUTS && ancestors.add(input)) {
                "input tree exceeds explicit depth/input budget or contains a cycle"
            }
            val children = linkedMapOf<String, Child>()
            graph.references(scope).forEach { target -> analyze(requireNotNull(graph.scope(target)), target) }
            val calculations = input.metricCalls.flatMap { it.calculationScopes(scope).entries }.associate { it.key to it.value }
            graph.children(scope).forEach { edge -> when (edge.kind) {
                ExecutableInputGraph.EdgeKind.DECLARED_INPUT -> children[edge.name] = Child(requireNotNull(edge.input.inputMode), analyze(edge.input, edge.scope))
                ExecutableInputGraph.EdgeKind.METRIC_CALL -> analyzeCalculation(calculations.getValue(edge.scope), edge.scope)
                ExecutableInputGraph.EdgeKind.MEMBER_BODY -> analyze(edge.input, edge.scope)
            } }
            val facts = ExecutableInputSymbols(graph, result, input, scope, children).facts()
            val analysis = QueryStageAnalysis.analyze(scope, facts.columns, facts.key, children, facts.operations, facts.population,
                "input[${scope.path}].postOps", facts.observations)
            result[scope] = analysis
            ancestors.remove(input)
            return analysis
        }
        private fun analyzeCalculation(calculation: ExecutableMetricCall.Calculation, scope: ScopeId) {
            val metric = calculation.invocation.metric
            val diagnostic = linkedMapOf<String, Any>("metric" to metric.reference, "owner" to calculation.invocation.owner,
                "callId" to calculation.invocation.callId, "scope" to scope, "declaredMeasureOutput" to calculation.measureOutput,
                "declaredKeyOutputs" to calculation.keyOutputs)
            try {
                val internal = analyze(calculation.input, scope)
                diagnostic["actualOutputs"] = internal.outputNames(); diagnostic["actualKey"] = internal.key
                require(calculation.measureOutput in internal.outputNames() && internal.alignmentGrain().keys == calculation.keyOutputs.toSet() && internal.outputNames().size == calculation.keyOutputs.size + 1) {
                    "metric calculation must output exactly one measure and its declared semantic key"
                }
                calculation.invocation.measurementSlot?.let { slot ->
                    ExecutableInputSymbols.requireMeasurement(slot, internal, internal.outputs.first { it.name == calculation.measureOutput }.quantity)
                }
            } catch (failure: EngineExecutionException) { throw failure
            } catch (failure: RuntimeException) {
                val observed = diagnostic["actualKey"]?.let { "; actualOutputs=${diagnostic["actualOutputs"]}, actualKey=${it.javaClass.simpleName}.$it" } ?: ""
                throw EngineExecutionException("ANALYZE_STAGES", "EXECUTABLE_STAGE_ANALYSIS_FAILED",
                    "Metric calculation ${metric.reference.key()} at scope ${scope.path} declares measureOutput=${calculation.measureOutput}, keyOutputs=${calculation.keyOutputs}$observed; ${failure.message}", failure, diagnostic)
            }
        }
    }
}
