package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.SymbolId

/** 一份实际来源出口及人口保持的作用域依赖图；同一完成位置不会按调用次数重新读取。 */
class CoreFinalizationGraph private constructor(val source: SourceCut, nodes: List<Node>) {
    data class SourceCut(val scope: ScopeId, val stageCount: Int)
    data class Node(val scope: ScopeId, val root: Root, val stageStart: Int)
    sealed interface Root {
        data object Source : Root
        data class Forward(val scope: ScopeId) : Root
        data class Relation(val input: ExecutableRelationInput) : Root
        data class Measures(val calls: List<CallProjection>) : Root
    }
    val sourceScope: ScopeId get() = source.scope
    val nodes: List<Node> = java.util.List.copyOf(nodes)
    val scopes: List<ScopeId> = this.nodes.map { it.scope }

    /** 共同人口以实际来源前缀出口为准，不以该scope最终CORE结果的键代替。 */
    fun requireCommonPopulation(analyses: Map<ScopeId, Analysis>, boundary: Analysis) {
        require(boundary.scope == sourceScope && boundary.stages.size == source.stageCount) { "core population boundary differs from its selected source prefix" }
        val symbols = QueryStageAnalysis.symbols(analyses.values)
        val sourceColumns = boundary.outputs.map { it.id }.toSet()
        val lineage = linkedMapOf<SymbolId, Set<SymbolId>>()
        fun sources(id: SymbolId, active: MutableSet<SymbolId> = linkedSetOf()): Set<SymbolId> {
            if (id in sourceColumns) return setOf(id)
            lineage[id]?.let { return it }
            check(active.add(id)) { "cyclic source symbol dependency" }
            return try { symbols.getValue(id).dependencies.flatMapTo(linkedSetOf()) { sources(it, active) }.also { lineage[id] = it } }
            finally { active.remove(id) }
        }
        val key = (boundary.key as? QueryStageAnalysis.UniqueKey)?.columns?.flatMapTo(linkedSetOf()) { sources(it) }
        nodes.forEach { node ->
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
            return CoreFinalizationGraph(cut, nodes.values.toList())
        }

        private fun visit(scope: ScopeId) {
            if (scope in nodes) return
            check(active.add(scope)) { "core scope dependencies contain a cycle" }
            try {
                val input = requireNotNull(graph.scope(scope))
                val prefix = nativeStageCount(scope)
                val root = when (val root = input.root) {
                    is ExecutableInputRoot.Input -> {
                        val child = scope.child(root.inputId)
                        check(graph.scope(child) === input.additionalInputs[root.inputId]) { "root input differs from its bound scope" }
                        if (prefix > 0) {
                            if (!canCompleteNatively(child))
                                CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1), "a source relational prefix cannot consume an earlier CORE child suffix; finish the relational population in an explicit source ROOT before core scalar evaluation")
                            sourceRoot(scope, prefix)
                        } else {
                            requireTransparent(input, scope)
                            visit(child)
                            Root.Forward(child)
                        }
                    }
                    is ExecutableInputRoot.Relation -> {
                        if (prefix > 0) sourceRoot(scope, prefix)
                        else {
                            requireTransparent(input, scope)
                            val completed = analyses.getValue(root.input.inputScope)
                            requireSource(SourceCut(root.input.inputScope, completed.stages.size), scope)
                            Root.Relation(root.input)
                        }
                    }
                    is ExecutableInputRoot.Measures -> if (root.calls.any { it !is ExecutableMetricCall.Fact }) {
                        if (prefix > 0)
                            CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1), "a source relational prefix cannot consume CORE calculation outputs or their output quantization; finish relational composition before the core cut")
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
                    } else sourceRoot(scope, prefix)
                    else -> sourceRoot(scope, prefix)
                }
                nodes[scope] = Node(scope, root, if (root == Root.Source) prefix else 0)
            } finally { active.remove(scope) }
        }

        private fun nativeStageCount(scope: ScopeId): Int = analyses.getValue(scope).stages.indexOfLast {
            it.operation !is PostOperationContract.Derive && it.operation !is PostOperationContract.Project
        } + 1

        /** 只吸收没有CORE后缀的透明输入；完整ROOT关系实参始终由来源完整兑现。 */
        private fun canCompleteNatively(scope: ScopeId): Boolean {
            val analysis = analyses.getValue(scope)
            if (nativeStageCount(scope) != analysis.stages.size) return false
            return when (val root = requireNotNull(graph.scope(scope)).root) {
                is ExecutableInputRoot.Input -> canCompleteNatively(scope.child(root.inputId))
                is ExecutableInputRoot.Measures -> root.calls.all { it is ExecutableMetricCall.Fact }
                else -> true
            }
        }

        private fun sourceRoot(scope: ScopeId, stageCount: Int): Root {
            requireSource(SourceCut(scope, stageCount), scope)
            return Root.Source
        }
        private fun requireSource(candidate: SourceCut, owner: ScopeId) {
            val previous = source
            if (previous != null && previous != candidate)
                CoreFinalizationExpressions.unsupported(path(owner), "core scalar dependencies have different exact source outputs: ${previous.scope.path} after ${previous.stageCount} stages and ${candidate.scope.path} after ${candidate.stageCount} stages; complete their relational composition in one explicit source ROOT input")
            check(graph.scope(candidate.scope) != null) { "source cut is outside the bound graph" }
            check(candidate.stageCount in 0..analyses.getValue(candidate.scope).stages.size) { "source cut is outside its actual scope stages" }
            source = candidate
        }

        private fun stagePath(scope: ScopeId, index: Int): List<Any> = listOf<Any>("input") + scope.path + listOf("postOps", index)

        private fun requireTransparent(input: ExecutableInputScope, scope: ScopeId) {
            if (input.objectRestrictions.isNotEmpty() || input.additionalInputs.values.any { it.inputMode != "ROOT" })
                CoreFinalizationExpressions.unsupported(path(scope), "a core root call cannot cross object restrictions or aligned/imported relations; complete them in an explicit source ROOT input")
        }
    }
}
