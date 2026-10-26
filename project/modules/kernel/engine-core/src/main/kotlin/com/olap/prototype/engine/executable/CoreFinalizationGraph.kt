package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ResultAggregation

/** 实际来源前缀与核心后缀的依赖图；运输人口与各节点最终人口分别计算。 */
class CoreFinalizationGraph private constructor(sources: List<SourceCut>, nodes: List<Node>) {
    data class SourceCut(val scope: ScopeId, val stageCount: Int)
    data class SourceInput(val cut: SourceCut)
    data class Node(val scope: ScopeId, val root: Root, val stageStart: Int)
    data class Population(val kind: Calculation.PopulationKind, val scopes: List<ScopeId>)
    sealed interface Root {
        data object Source : Root
        data class Forward(val scope: ScopeId) : Root
        data class Relation(val input: ExecutableRelationInput) : Root
        data class Projection(val inputs: List<InputProjection>, val observationIdentity: List<String>?, val population: Population) : Root
    }
    val sources: List<SourceCut> = java.util.List.copyOf(sources)
    val nodes: List<Node> = java.util.List.copyOf(nodes)
    val scopes: List<ScopeId> = this.nodes.map { it.scope }

    /** 运输保留各切点的原袋；只有完成结果的实际组合才要求正式完整键。 */
    fun sourceInputs(analyses: Map<ScopeId, Analysis>, boundaries: Map<ScopeId, Analysis>): List<SourceInput> {
        this.sources.forEach { cut -> require(boundaries.getValue(cut.scope).stages.size == cut.stageCount) {
            "core population boundary differs from its selected source prefix"
        } }
        nodes.forEach { node ->
            val root = node.root as? Root.Projection ?: return@forEach
            if (root.inputs.size == 1 && root.observationIdentity == null) return@forEach
            var positions: Set<String>? = null
            root.inputs.forEach { input ->
                val branch = analyses.getValue(input.inputScope)
                val key = (branch.key as? QueryStageAnalysis.UniqueKey)?.columns
                    ?: CoreFinalizationExpressions.unsupported(path(node.scope) + input.position, "core completed alignment needs the input's complete unique key")
                val projection = input.identitySlots.mapValues { (_, name) -> branch.outputs.first { it.name == name }.id }
                if (projection.values.toSet() != key.toSet() || projection.values.distinct().size != projection.size)
                    CoreFinalizationExpressions.unsupported(path(node.scope) + input.position, "projection keys do not preserve the complete input identity")
                if (positions != null && positions != projection.keys)
                    CoreFinalizationExpressions.unsupported(path(node.scope) + input.position, "completed alignment maps different key positions")
                positions = projection.keys
            }
        }
        return this.sources.map(::SourceInput)
    }

    class InputProjection(val inputScope: ScopeId, outputSlots: Map<String, String>, identitySlots: Map<String, String>,
        valueContracts: Map<String, MetricValueContract>, displayNames: Map<String, String>, position: List<Any>,
        zeroWhenAbsent: Set<String> = emptySet()) {
        val outputSlots: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(outputSlots))
        val identitySlots: Map<String, String> = java.util.Map.copyOf(identitySlots)
        val valueContracts: Map<String, MetricValueContract> = java.util.Map.copyOf(valueContracts)
        val displayNames: Map<String, String> = java.util.Map.copyOf(displayNames)
        val position: List<Any> = java.util.List.copyOf(position)
        val zeroWhenAbsent: Set<String> = java.util.Set.copyOf(zeroWhenAbsent)
    }

    companion object {
        fun select(graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>): CoreFinalizationGraph = Selection(graph, analyses).select()
        private fun path(scope: ScopeId): List<Any> = listOf<Any>("input") + scope.path + "root"
    }

    private class Selection(private val graph: ExecutableInputGraph, private val analyses: Map<ScopeId, Analysis>) {
        private val nodes = linkedMapOf<ScopeId, Node>()
        private val active = linkedSetOf<ScopeId>()
        private val sources = linkedMapOf<ScopeId, SourceCut>()
        private val sourceRequired = linkedSetOf<ScopeId>()
        fun select(): CoreFinalizationGraph {
            // 关系消费者先确定其完整输入的执行位置，再为各计算选择末端标量切点。
            // 仅登记为 ROOT 并不要求来源完成；只沿实际消费边传播。
            collectSourceRequirements(ScopeId.root(), linkedSetOf())
            visit(ScopeId.root())
            check(sources.isNotEmpty()) { "core finalization needs actual source completion boundaries" }
            return CoreFinalizationGraph(sources.values.toList(), nodes.values.toList())
        }

        private fun visit(scope: ScopeId) {
            if (scope in nodes) return
            check(active.add(scope)) { "core scope dependencies contain a cycle" }
            try {
                val input = requireNotNull(graph.scope(scope))
                val prefix = nativeStageCount(scope)
                if (scope in sourceRequired && hasCoreMean(scope))
                    CoreFinalizationExpressions.unsupported(path(scope), "a source relation cannot consume a mathematical reduction completed in CORE")
                val root = if (scope in sourceRequired) sourceRoot(scope, analyses.getValue(scope).stages.size) else when (val root = input.root) {
                    is ExecutableInputRoot.Input -> {
                        val child = scope.child(root.inputId)
                        check(graph.scope(child) === input.additionalInputs[root.inputId]) { "root input differs from its bound scope" }
                        if (prefix > 0) {
                            if (!canCompleteNatively(child))
                                CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1), "a source relational stage cannot consume a value that is completed in CORE")
                            sourceRoot(scope, prefix)
                        } else {
                            requireTransparent(input, scope)
                            visit(child)
                            Root.Forward(child)
                        }
                    }
                    is ExecutableInputRoot.Relation -> {
                        if (prefix > 0) {
                            if (!canCompleteNatively(root.input.inputScope))
                                CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1), "a source relational prefix cannot consume an earlier CORE relation suffix")
                            sourceRoot(scope, prefix)
                        }
                        else {
                            requireTransparent(input, scope)
                            visit(root.input.inputScope)
                            Root.Relation(root.input)
                        }
                    }
                    is ExecutableInputRoot.Measures -> if (!nativeRoot(scope, input)) {
                        if (prefix > 0)
                            CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1), "a source relational stage cannot consume CORE calculation outputs or their output quantization")
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
                        }, null, Population(Calculation.PopulationKind.UNION, root.population.callIds.map(scope::metric)))
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
                                InputProjection(child, outputs, identity, emptyMap(), emptyMap(), listOf("inputs", id),
                                    root.values.getValue(id).filter { it.noMatch == Calculation.NoMatch.ZERO_WHEN_ABSENT }.mapTo(linkedSetOf()) { it.outputAlias })
                            }, root.observationKeys, Population(root.population.kind, root.population.inputIds.map(scope::child)))
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
                                Root.Projection(listOf(InputProjection(child, outputs, identity, emptyMap(), display, listOf("member"))), null,
                                    Population(Calculation.PopulationKind.BASE, listOf(child)))
                            }
                        }
                    }
                    else -> {
                        if (!nativeRoot(scope, input)) CoreFinalizationExpressions.unsupported(path(scope),
                            "a source relational root cannot consume an earlier CORE child suffix")
                        sourceRoot(scope, prefix)
                    }
                }
                nodes[scope] = Node(scope, root, if (root == Root.Source) sources.getValue(scope).stageCount else 0)
            } finally { active.remove(scope) }
        }

        private fun hasCoreMean(scope: ScopeId): Boolean = analyses.getValue(scope).stages.any { isCoreMean(it.operation) }

        private fun dependsOnCoreMean(scope: ScopeId, active: MutableSet<ScopeId> = linkedSetOf()): Boolean {
            check(active.add(scope)) { "core scope dependencies contain a cycle" }
            return try { hasCoreMean(scope) || sourceDependencies(scope, requireNotNull(graph.scope(scope))).any {
                dependsOnCoreMean(it, active)
            } } finally { active.remove(scope) }
        }

        private fun isCoreMean(operation: PostOperationContract.ValidatedOperation): Boolean =
            operation is PostOperationContract.Regroup && operation.grouping.aggregates.any {
                (it.aggregation as? ResultAggregation.Average)?.meanTarget == MeanTarget.MATHEMATICAL
            }

        private fun nativeStageCount(scope: ScopeId): Int {
            val stages = analyses.getValue(scope).stages
            val mean = stages.indexOfFirst { isCoreMean(it.operation) }
            val inherited = sourceDependencies(scope, requireNotNull(graph.scope(scope))).any { dependsOnCoreMean(it) }
            if (mean < 0 && !inherited) return stages.indexOfLast {
                it.operation !is PostOperationContract.Derive && it.operation !is PostOperationContract.Project &&
                    it.operation !is PostOperationContract.PostFilter
            } + 1
            val boundary = if (inherited) 0 else mean
            val prefix = stages.take(boundary).indexOfLast { stage -> when (val operation = stage.operation) {
                is PostOperationContract.Project -> false
                is PostOperationContract.Derive -> CoreFinalizationExpressions.needsSource(operation.expression)
                is PostOperationContract.PostFilter -> CoreFinalizationExpressions.needsSource(operation.expression)
                else -> true
            } } + 1
            stages.drop(boundary).forEach { stage -> when (val operation = stage.operation) {
                is PostOperationContract.Regroup, is PostOperationContract.Project -> Unit
                is PostOperationContract.Derive -> if (CoreFinalizationExpressions.needsSource(operation.expression))
                    CoreFinalizationExpressions.unsupported(stagePath(scope, stage.index), "a source expression cannot consume an earlier CORE reduction")
                is PostOperationContract.PostFilter -> if (CoreFinalizationExpressions.needsSource(operation.expression))
                    CoreFinalizationExpressions.unsupported(stagePath(scope, stage.index), "a source filter cannot consume an earlier CORE reduction")
                else -> CoreFinalizationExpressions.unsupported(stagePath(scope, stage.index), "this relational operation is not supported after a CORE reduction")
            } }
            return prefix
        }

        /** 关系消费要求在来源完成其输入；其余末端标量才留在 CORE。 */
        private fun canCompleteNatively(scope: ScopeId): Boolean {
            if (hasCoreMean(scope)) return false
            val analysis = analyses.getValue(scope)
            if (scope !in sourceRequired && analysis.stages.drop(nativeStageCount(scope)).any {
                it.operation is PostOperationContract.Derive || it.operation is PostOperationContract.PostFilter
            }) return false
            return nativeRoot(scope, requireNotNull(graph.scope(scope)))
        }

        private fun needsSourceRoot(scope: ScopeId, input: ExecutableInputScope): Boolean {
            if (nativeStageCount(scope) > 0 || input.additionalInputs.values.any { it.inputMode != "ROOT" } ||
                input.objectRestrictions.isNotEmpty() || input.metrics.any { it.objectRestrictions.isNotEmpty() }) return true
            return when (val root = input.root) {
                is ExecutableInputRoot.ObjectMeasure, is ExecutableInputRoot.ObservationStatistics,
                is ExecutableInputRoot.SetInputs -> true
                is ExecutableInputRoot.Members -> root.input.let { member ->
                    member.condition !is MemberCondition.Unrestricted || member.range != null || member.selection.clocks.isNotEmpty()
                }
                is ExecutableInputRoot.Measures -> root.groupingLevels != null || root.outputCondition !is DimensionCondition.Unrestricted ||
                    root.population.mode != "CALLS" || root.calls.any { it is ExecutableMetricCall.Fact }
                is ExecutableInputRoot.Input, is ExecutableInputRoot.Relation, is ExecutableInputRoot.AlignMeasurements -> false
            }
        }

        private fun requireSourceCompletion(scope: ScopeId) {
            if (!sourceRequired.add(scope)) return
            sourceDependencies(scope, requireNotNull(graph.scope(scope))).forEach(::requireSourceCompletion)
        }

        private fun collectSourceRequirements(scope: ScopeId, visited: MutableSet<ScopeId>) {
            if (!visited.add(scope)) return
            val input = requireNotNull(graph.scope(scope))
            val dependencies = sourceDependencies(scope, input)
            if (needsSourceRoot(scope, input)) dependencies.forEach(::requireSourceCompletion)
            dependencies.forEach { collectSourceRequirements(it, visited) }
        }

        private fun nativeRoot(scope: ScopeId, input: ExecutableInputScope): Boolean =
            sourceDependencies(scope, input).all(::canCompleteNatively)

        private fun sourceDependencies(scope: ScopeId, input: ExecutableInputScope): List<ScopeId> {
            val calendars = analyses.getValue(scope).stages.map { it.operation }.filterIsInstance<PostOperationContract.ObserveWindow>()
                .mapNotNull { it.calendar?.inputId }.toSet()
            val auxiliary = input.additionalInputs.filter { (id, child) -> child.inputMode != "ROOT" || id in calendars }.keys.map(scope::child) +
                (input.objectRestrictions + input.metrics.flatMap { it.objectRestrictions }).map { it.objectsScope }
            val rootDependencies = when (val root = input.root) {
            is ExecutableInputRoot.Input -> listOf(scope.child(root.inputId))
            is ExecutableInputRoot.Measures -> input.metricCalls.flatMap { call -> call.calculationScopes(scope).keys }
            is ExecutableInputRoot.AlignMeasurements -> root.keys.keys.map(scope::child)
            is ExecutableInputRoot.Members -> when (val member = root.input) {
                is ExecutableMemberInput.Computed -> listOf(member.computationScope)
                is ExecutableMemberInput.Local -> listOf(member.inputScope)
                is ExecutableMemberInput.Projection -> emptyList()
            }
            is ExecutableInputRoot.ObjectMeasure -> listOf(scope.child("measurement"), scope.child("objects"))
            is ExecutableInputRoot.ObservationStatistics -> listOf(scope.child("observations"))
            is ExecutableInputRoot.SetInputs -> root.inputs.map { scope.child(it.inputId) }
            is ExecutableInputRoot.Relation -> listOf(root.input.inputScope)
            }
            return (auxiliary + rootDependencies).distinct()
        }

        private fun requireScalarAssembly(input: ExecutableInputScope, scope: ScopeId, prefix: Int) {
            if (prefix > 0) CoreFinalizationExpressions.unsupported(stagePath(scope, prefix - 1),
                "a source relational prefix cannot consume an earlier CORE child suffix")
            requireTransparent(input, scope)
        }

        private fun sourceRoot(scope: ScopeId, stageCount: Int): Root {
            if (!nativeRoot(scope, requireNotNull(graph.scope(scope))))
                CoreFinalizationExpressions.unsupported(path(scope), "a source relational boundary depends on a value or population completed in CORE")
            val stages = analyses.getValue(scope).stages
            val scalar = if (scope in sourceRequired) -1 else stages.drop(stageCount).indexOfFirst {
                it.operation is PostOperationContract.Derive || it.operation is PostOperationContract.PostFilter || isCoreMean(it.operation)
            }
            requireSource(SourceCut(scope, if (scalar < 0) stages.size else stageCount + scalar), scope)
            return Root.Source
        }
        private fun requireSource(candidate: SourceCut, owner: ScopeId) {
            val previous = sources.putIfAbsent(candidate.scope, candidate)
            if (previous != null && previous != candidate)
                CoreFinalizationExpressions.unsupported(path(owner), "one exact source scope has different selected completion boundaries")
            check(graph.scope(candidate.scope) != null) { "source cut is outside the bound graph" }
            check(candidate.stageCount in 0..analyses.getValue(candidate.scope).stages.size) { "source cut is outside its actual scope stages" }
        }

        private fun stagePath(scope: ScopeId, index: Int): List<Any> = listOf<Any>("input") + scope.path + listOf("postOps", index)

        private fun requireTransparent(input: ExecutableInputScope, scope: ScopeId) {
            if (input.objectRestrictions.isNotEmpty() || input.additionalInputs.values.any { it.inputMode != "ROOT" })
                CoreFinalizationExpressions.unsupported(path(scope), "core scalar assembly cannot execute object restrictions or imported relation operations")
        }
    }
}
