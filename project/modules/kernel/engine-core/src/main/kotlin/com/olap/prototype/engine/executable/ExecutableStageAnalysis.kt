package com.olap.prototype.engine.executable

import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.query.PostOperationTraversal
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ResultExpressionTree
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.Child
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.IdentityHashMap

/** 以绑定图的精确实例身份分析阶段；活动祖先和完成缓存仅属本次分析。 */
object ExecutableStageAnalysis {
    fun analyze(graph: ExecutableInputGraph, connectors: ConnectorRegistry): Map<ScopeId, Analysis> = Session(graph, connectors).analyze()

    /** 保留同一绑定图和已经完成的子计算，只重分析物理切点上的来源前缀。 */
    fun sourcePrefixes(graph: ExecutableInputGraph, fullAnalyses: Map<ScopeId, Analysis>, cuts: List<CoreFinalizationGraph.SourceCut>,
        connectors: ConnectorRegistry): Map<ScopeId, Analysis> {
        check(cuts.isNotEmpty() && cuts.map { it.scope }.distinct().size == cuts.size) { "source completions require one boundary per actual scope" }
        cuts.forEach { cut ->
            check(cut.scope in fullAnalyses && graph.scope(cut.scope) != null) { "core source cut must identify an actual analyzed scope" }
            check(cut.stageCount in 0..fullAnalyses.getValue(cut.scope).stages.size) { "source prefix exceeds its actual scope stages" }
        }
        val counts = cuts.associate { it.scope to it.stageCount }
        val session = Session(graph, connectors, fullAnalyses - counts.keys, counts)
        var result = fullAnalyses
        cuts.forEach { result = session.analyze(it.scope) }
        return result
    }

    private class Session(private val graph: ExecutableInputGraph, private val connectors: ConnectorRegistry,
        completed: Map<ScopeId, Analysis> = emptyMap(), private val nativeStageCounts: Map<ScopeId, Int> = emptyMap()) {
        private val result = LinkedHashMap(completed)
        private val ancestors = Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>())
        fun analyze(scope: ScopeId = ScopeId.root()): Map<ScopeId, Analysis> {
            analyze(requireNotNull(graph.scope(scope)), scope)
            return result.toMap()
        }
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
            val operations = facts.operations.take(nativeStageCounts[scope] ?: facts.operations.size)
            val nativeFunctions = operations.flatMap(PostOperationTraversal::expressions).flatMap(ResultExpressionTree::nodes)
                .filterIsInstance<ResultExpression.ResultNativeCall>().associateWith { call ->
                    connectors.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function).also {
                        it.requireScalarCall(call.function, call.arguments.size)
                    }
                }
            val analysis = QueryStageAnalysis.analyze(scope, facts.columns, facts.key, children, operations, facts.population,
                "input[${scope.path}].postOps", facts.observations, nativeFunctions)
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
