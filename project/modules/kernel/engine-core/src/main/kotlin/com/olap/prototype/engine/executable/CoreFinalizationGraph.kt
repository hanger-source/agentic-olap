package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSourceDefinition
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
        data class Projection(val inputs: List<InputProjection>, val observationIdentity: List<String>?) : Root
    }
    val sourceScope: ScopeId get() = source.scope
    val nodes: List<Node> = java.util.List.copyOf(nodes)
    val scopes: List<ScopeId> = this.nodes.map { it.scope }

    /** 共同人口以实际来源前缀出口为准，不以该scope最终CORE结果的键代替。 */
    fun requireCommonPopulation(analyses: Map<ScopeId, Analysis>, boundary: Analysis): List<String>? {
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
        var observationIdentity: List<String>? = null
        nodes.forEach { node ->
            val root = node.root as? Root.Projection ?: return@forEach
            if (root.inputs.size == 1 && root.observationIdentity == null) return@forEach
            val actualKey = key ?: CoreFinalizationExpressions.unsupported(path(node.scope), "core projection assembly needs the source output's complete unique key")
            var common: Map<String, SymbolId>? = null
            root.inputs.forEach { input ->
                val branch = analyses.getValue(input.inputScope)
                val projection = input.identitySlots.mapValues { (_, name) ->
                    val symbol = branch.outputs.first { it.name == name }
                    sources(symbol.id).singleOrNull() ?: CoreFinalizationExpressions.unsupported(path(node.scope) + input.position, "projection key is not a direct identity of the exact completed source output")
                }
                if (projection.values.toSet() != actualKey || common?.let { it != projection } == true)
                    CoreFinalizationExpressions.unsupported(path(node.scope) + input.position, "projection keys do not preserve the same complete source identity; relational alignment must finish before the core cut")
                common = projection
            }
            if (root.observationIdentity != null) observationIdentity = boundary.outputs.filter { it.id in sourceColumns && it.id in actualKey }.map { it.name }
        }
        return observationIdentity
    }

    class InputProjection(val inputScope: ScopeId, outputSlots: Map<String, String>, identitySlots: Map<String, String>,
        valueContracts: Map<String, MetricValueContract>, displayNames: Map<String, String>, position: List<Any>) {
        val outputSlots: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(outputSlots))
        val identitySlots: Map<String, String> = java.util.Map.copyOf(identitySlots)
        val valueContracts: Map<String, MetricValueContract> = java.util.Map.copyOf(valueContracts)
        val displayNames: Map<String, String> = java.util.Map.copyOf(displayNames)
        val position: List<Any> = java.util.List.copyOf(position)
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
                        Root.Projection(root.calls.map { call ->
                            if (call !is ExecutableMetricCall.Calculation)
                                CoreFinalizationExpressions.unsupported(path(scope) + listOf("calls", call.invocation.callId), "core call assembly needs completed scalar calculations on the same explicit source output")
                            val child = scope.metric(call.invocation.callId)
                            check(graph.scope(child) === call.input) { "bound metric calculation differs from its exact call scope" }
                            visit(child)
                            val grain = analyses.getValue(child).alignmentGrain()
                            check(grain.values.toSet() == root.grouping.values.toSet()) { "calculation output grain differs from its call grouping" }
                            val projection = linkedMapOf<String, String>()
                            root.grouping.forEach { (alias, dimension) -> projection[alias] = grain.entries.first { it.value == dimension }.key }
                            val identity = projection.toMap()
                            projection[call.invocation.outputAlias] = call.measureOutput
                            InputProjection(child, projection, identity,
                                mapOf(call.invocation.outputAlias to call.invocation.metric.definition.valueContract.adopted(call.invocation.bindings)),
                                mapOf(call.invocation.outputAlias to call.invocation.displayName), listOf("calls", call.invocation.callId))
                        }, null)
                    } else sourceRoot(scope, prefix)
                    is ExecutableInputRoot.AlignMeasurements -> {
                        if (root.keys.keys.all { canCompleteNatively(scope.child(it)) }) sourceRoot(scope, prefix)
                        else {
                            requireScalarAssembly(input, scope, prefix)
                            Root.Projection(root.keys.map { (id, keys) ->
                                val child = scope.child(id)
                                visit(child)
                                val identity = root.observationKeys.associateWith { keys.getValue(it) }
                                val outputs = LinkedHashMap(identity)
                                root.values.getValue(id).forEach { value -> outputs[value.outputAlias] = value.symbol }
                                InputProjection(child, outputs, identity, emptyMap(), emptyMap(), listOf("inputs", id))
                            }, root.observationKeys)
                        }
                    }
                    is ExecutableInputRoot.Members -> when (val member = root.input) {
                        is ExecutableMemberInput.Projection -> sourceRoot(scope, prefix)
                        else -> {
                            val child = when (member) {
                                is ExecutableMemberInput.Computed -> member.computationScope
                                is ExecutableMemberInput.Local -> member.inputScope
                                is ExecutableMemberInput.Projection -> error("projection is handled above")
                            }
                            if (canCompleteNatively(child)) sourceRoot(scope, prefix)
                            else {
                                requireScalarAssembly(input, scope, prefix)
                                if (member.condition !is MemberCondition.Unrestricted || member.range != null || member.selection.clocks.isNotEmpty())
                                    CoreFinalizationExpressions.unsupported(path(scope), "core completed member projection cannot add a source condition, range or clocks")
                                visit(child)
                                val outputs = member.outputs.mapValues { (_, name) -> if (member is ExecutableMemberInput.Computed)
                                    MemberSourceDefinition.completedSymbol(member.source.definition.result.outputs.getValue(name)) else name }
                                val analysis = analyses.getValue(scope)
                                val key = analysis.key as? QueryStageAnalysis.UniqueKey
                                val identity = analysis.outputs.filter { key != null && it.id in key.columns }.associate { it.name to outputs.getValue(it.name) }
                                val display = if (member is ExecutableMemberInput.Computed) member.outputs.mapValues { (_, name) -> member.source.definition.result.outputs.getValue(name).displayName } else emptyMap()
                                Root.Projection(listOf(InputProjection(child, outputs, identity, emptyMap(), display, listOf("member"))), null)
                            }
                        }
                    }
                    else -> {
                        if (!nativeRoot(scope, input)) CoreFinalizationExpressions.unsupported(path(scope),
                            "a source relational root cannot consume an earlier CORE child suffix")
                        sourceRoot(scope, prefix)
                    }
                }
                nodes[scope] = Node(scope, root, if (root == Root.Source) prefix else 0)
            } finally { active.remove(scope) }
        }

        private fun nativeStageCount(scope: ScopeId): Int = analyses.getValue(scope).stages.indexOfLast {
            it.operation !is PostOperationContract.Derive && it.operation !is PostOperationContract.Project
        } + 1

        /** 列投影可在来源保留；标量运算后缀及其透传依赖留在CORE。完整ROOT关系实参是明确来源出口。 */
        private fun canCompleteNatively(scope: ScopeId): Boolean {
            val analysis = analyses.getValue(scope)
            if (analysis.stages.drop(nativeStageCount(scope)).any { it.operation !is PostOperationContract.Project }) return false
            return nativeRoot(scope, requireNotNull(graph.scope(scope)))
        }

        private fun nativeRoot(scope: ScopeId, input: ExecutableInputScope): Boolean = when (val root = input.root) {
            is ExecutableInputRoot.Input -> canCompleteNatively(scope.child(root.inputId))
            is ExecutableInputRoot.Measures -> root.calls.all { it is ExecutableMetricCall.Fact }
            is ExecutableInputRoot.AlignMeasurements -> root.keys.keys.all { canCompleteNatively(scope.child(it)) }
            is ExecutableInputRoot.Members -> when (val member = root.input) {
                is ExecutableMemberInput.Computed -> canCompleteNatively(member.computationScope)
                is ExecutableMemberInput.Local -> canCompleteNatively(member.inputScope)
                is ExecutableMemberInput.Projection -> true
            }
            is ExecutableInputRoot.ObjectMeasure -> canCompleteNatively(scope.child("measurement")) && canCompleteNatively(scope.child("objects"))
            is ExecutableInputRoot.ObservationStatistics -> canCompleteNatively(scope.child("observations"))
            is ExecutableInputRoot.SetInputs -> root.inputs.all { canCompleteNatively(scope.child(it.inputId)) }
            is ExecutableInputRoot.Relation -> true
        }

        private fun requireScalarAssembly(input: ExecutableInputScope, scope: ScopeId, prefix: Int) {
            if (prefix > 0) CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1),
                "a source relational prefix cannot consume an earlier CORE child suffix")
            requireTransparent(input, scope)
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
