package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.SymbolId

/** 一份实际来源出口及人口保持的作用域依赖图；同一完成位置不会按调用次数重新读取。 */
class CoreFinalizationGraph private constructor(val source: SourceCut, nodes: List<Node>) {
    data class SourceCut(val scope: ScopeId, val completed: Boolean)
    data class Node(val scope: ScopeId, val root: Root)
    sealed interface Root {
        data object Source : Root
        data class Forward(val scope: ScopeId) : Root
        data class Relation(val input: ExecutableRelationInput) : Root
        data class Measures(val calls: List<CallProjection>) : Root
    }
    val sourceScope: ScopeId get() = source.scope
    val nodes: List<Node> = java.util.List.copyOf(nodes)
    val scopes: List<ScopeId> = this.nodes.map { it.scope }

    class CallProjection(val inputScope: ScopeId, val callId: String, outputSlots: Map<String, String>, val measureAlias: String,
        val displayName: String, val valueContract: MetricValueContract) {
        val outputSlots: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(outputSlots))
    }

    companion object {
        fun select(graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>): CoreFinalizationGraph = Selection(graph, analyses).select()
        private fun path(scope: ScopeId): List<Any> = listOf<Any>("input") + scope.path + "root"
    }

    private class Selection(private val graph: ExecutableInputGraph, private val analyses: Map<ScopeId, Analysis>) {
        private val nodes = linkedMapOf<ScopeId, Node>()
        private val active = linkedSetOf<ScopeId>()
        private var source: SourceCut? = null
        fun select(): CoreFinalizationGraph {
            visit(ScopeId.root())
            val cut = requireNotNull(source) { "core finalization needs an actual source completion boundary" }
            val result = CoreFinalizationGraph(cut, nodes.values.toList())
            requireCommonPopulation(result)
            return result
        }

        private fun visit(scope: ScopeId) {
            if (scope in nodes) return
            check(active.add(scope)) { "core scope dependencies contain a cycle" }
            try {
                val input = requireNotNull(graph.scope(scope))
                val root = when (val root = input.root) {
                    is ExecutableInputRoot.Input -> {
                        requireTransparent(input, scope)
                        val child = scope.child(root.inputId)
                        check(graph.scope(child) === input.additionalInputs[root.inputId]) { "root input differs from its bound scope" }
                        visit(child)
                        Root.Forward(child)
                    }
                    is ExecutableInputRoot.Relation -> {
                        requireTransparent(input, scope)
                        requireSource(SourceCut(root.input.inputScope, true), scope)
                        Root.Relation(root.input)
                    }
                    is ExecutableInputRoot.Measures -> if (root.calls.any { it !is ExecutableMetricCall.Fact }) {
                        requireTransparent(input, scope)
                        if (root.groupingLevels != null || root.outputCondition !is DimensionCondition.Unrestricted || root.population.mode != "CALLS")
                            CoreFinalizationExpressions.unsupported(path(scope), "core calls cannot add grouping levels, output filters or a different row population")
                        com.olap.prototype.query.RowPopulationContract.requireSelected(root.population, root.calls.map { it.invocation.callId }.toSet())
                        Root.Measures(root.calls.map { call ->
                            if (call !is ExecutableMetricCall.Calculation)
                                CoreFinalizationExpressions.unsupported(path(scope) + listOf("calls", call.invocation.callId), "core call assembly needs completed scalar calculations on the same explicit source output")
                            val child = scope.metric(call.invocation.callId)
                            check(graph.scope(child) === call.input) { "bound metric calculation differs from its exact call scope" }
                            visit(child)
                            val grain = analyses.getValue(child).alignmentGrain()
                            check(grain.values.toSet() == root.grouping.values.toSet()) { "calculation output grain differs from its call grouping" }
                            val projection = linkedMapOf<String, String>()
                            root.grouping.forEach { (alias, dimension) -> projection[alias] = grain.entries.first { it.value == dimension }.key }
                            projection[call.invocation.outputAlias] = call.measureOutput
                            CallProjection(child, call.invocation.callId, projection, call.invocation.outputAlias, call.invocation.displayName,
                                call.invocation.metric.definition.valueContract.adopted(call.invocation.bindings))
                        })
                    } else sourceRoot(scope)
                    else -> sourceRoot(scope)
                }
                nodes[scope] = Node(scope, root)
            } finally { active.remove(scope) }
        }

        private fun sourceRoot(scope: ScopeId): Root {
            requireSource(SourceCut(scope, false), scope)
            return Root.Source
        }
        private fun requireSource(candidate: SourceCut, owner: ScopeId) {
            val previous = source
            if (previous != null && previous != candidate)
                CoreFinalizationExpressions.unsupported(path(owner), "core scalar dependencies have different exact source outputs: ${previous.scope.path} (${if (previous.completed) "completed" else "root"}) and ${candidate.scope.path} (${if (candidate.completed) "completed" else "root"}); complete their relational composition in one explicit source ROOT input")
            check(graph.scope(candidate.scope) != null) { "source cut is outside the bound graph" }
            source = candidate
        }

        /** 每支保持同一份实际行，完整键也逐列回到相同的来源符号；因此CALLS子集不会改变人口。 */
        private fun requireCommonPopulation(plan: CoreFinalizationGraph) {
            val symbols = QueryStageAnalysis.symbols(analyses.values)
            val sourceAnalysis = analyses.getValue(plan.sourceScope)
            val sourceColumns = (if (plan.source.completed) sourceAnalysis.outputs else sourceAnalysis.inputs).map { it.id }.toSet()
            val lineage = linkedMapOf<SymbolId, Set<SymbolId>>()
            fun sources(id: SymbolId, active: MutableSet<SymbolId> = linkedSetOf()): Set<SymbolId> {
                if (id in sourceColumns) return setOf(id)
                lineage[id]?.let { return it }
                check(active.add(id)) { "cyclic source symbol dependency" }
                return try { symbols.getValue(id).dependencies.flatMapTo(linkedSetOf()) { sources(it, active) }.also { lineage[id] = it } }
                finally { active.remove(id) }
            }
            val key = (sourceAnalysis.key as? QueryStageAnalysis.UniqueKey)?.columns?.flatMapTo(linkedSetOf()) { sources(it) }
            plan.nodes.forEach { node ->
                val root = node.root as? Root.Measures ?: return@forEach
                val actualKey = key ?: CoreFinalizationExpressions.unsupported(path(node.scope), "core call assembly needs the source output's complete unique key")
                var common: Map<String, SymbolId>? = null
                root.calls.forEach { call ->
                    val branch = analyses.getValue(call.inputScope)
                    val projection = call.outputSlots.filterKeys { it != call.measureAlias }.mapValues { (_, name) ->
                        val symbol = branch.outputs.first { it.name == name }
                        sources(symbol.id).singleOrNull() ?: CoreFinalizationExpressions.unsupported(path(node.scope) + listOf("calls", call.callId), "call key is not a direct identity of the exact completed source output")
                    }
                    if (projection.values.toSet() != actualKey || common?.let { it != projection } == true)
                        CoreFinalizationExpressions.unsupported(path(node.scope) + listOf("calls", call.callId), "call keys do not preserve the same complete source identity; relational alignment must finish before the core cut")
                    common = projection
                }
            }
        }

        private fun requireTransparent(input: ExecutableInputScope, scope: ScopeId) {
            if (input.objectRestrictions.isNotEmpty() || input.additionalInputs.values.any { it.inputMode != "ROOT" })
                CoreFinalizationExpressions.unsupported(path(scope), "a core root call cannot cross object restrictions or aligned/imported relations; complete them in an explicit source ROOT input")
        }
    }
}
