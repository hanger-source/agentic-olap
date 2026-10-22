package com.olap.prototype.engine.executable

import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.IdentityHashMap

/** scope与owned调用边的完整索引，不决定人口或SQL消费。 */
class ExecutableInputGraph private constructor(val root: ExecutableInputScope, index: Index) {
    enum class EdgeKind { DECLARED_INPUT, METRIC_CALL, MEMBER_BODY }
    data class Edge(val kind: EdgeKind, val name: String, val scope: ScopeId, val input: ExecutableInputScope)
    val scopes: Map<ScopeId, ExecutableInputScope> = index.scopes.toMap()
    private val children: Map<ScopeId, List<Edge>> = index.children.toMap()
    val inputs: List<ExecutableInputScope> = index.inputs.toList()
    val sourceOrder: List<ExecutableInputScope> = index.sourceOrder.toList()
    fun scope(scope: ScopeId): ExecutableInputScope? = scopes[scope]
    fun children(scope: ScopeId): List<Edge> = children.getValue(scope)
    fun references(scope: ScopeId): List<ScopeId> = references(scopes.getValue(scope))

    companion object {
        fun index(root: ExecutableInputScope): ExecutableInputGraph {
            val index = Index().apply { add(root, ScopeId.root()) }
            index.inputs.forEach { input ->
                val local = (input.root as? ExecutableInputRoot.Members)?.input as? ExecutableMemberInput.Local
                require(local == null || local.inputScope in index.scopes) { "local member target is outside the bound input graph" }
                references(input).forEach { target ->
                    require(index.scopes[target]?.inputMode == "ROOT") { "completed relation target must be a ROOT input in the bound input graph: ${target.path}" }
                }
                val restrictions = input.objectRestrictions + input.metrics.flatMap { it.objectRestrictions }
                require(restrictions.all { it.objectsScope in index.scopes }) { "object population is outside the bound input graph" }
            }
            return ExecutableInputGraph(root, index)
        }
        private fun references(input: ExecutableInputScope): List<ScopeId> = when (val root = input.root) {
            is ExecutableInputRoot.Relation -> listOf(root.input.inputScope)
            is ExecutableInputRoot.Members -> (root.input as? ExecutableMemberInput.Local)?.let { listOf(it.inputScope) } ?: emptyList()
            else -> emptyList()
        }
    }
    private class Index {
        val scopes = linkedMapOf<ScopeId, ExecutableInputScope>()
        val children = linkedMapOf<ScopeId, List<Edge>>()
        val inputs = mutableListOf<ExecutableInputScope>()
        val sourceOrder = mutableListOf<ExecutableInputScope>()
        private val active = Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>())
        private val seen = Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>())
        fun add(input: ExecutableInputScope, scope: ScopeId) {
            require(input !in active) { "completed input dependencies contain a cycle" }
            scopes.putIfAbsent(scope, input)?.let { require(it === input) { "one scope has conflicting input identities" }; return }
            require(scope.depth() <= ScopedInputContract.MAX_DEPTH && scopes.size <= ScopedInputContract.MAX_INPUTS) { "input graph exceeds explicit depth/input budget" }
            active.add(input)
            val first = seen.add(input)
            if (first) inputs.add(input)
            val edges = input.additionalInputs.map { (id, child) -> Edge(EdgeKind.DECLARED_INPUT, id, scope.child(id), child) }.toMutableList()
            input.metricCalls.forEach { call -> call.calculationScopes(scope).forEach { (id, calculation) -> edges.add(Edge(EdgeKind.METRIC_CALL, call.invocation.callId, id, calculation.input)) } }
            input.memberComputations().forEach { (id, body) -> edges.add(Edge(EdgeKind.MEMBER_BODY, "", id, body)) }
            children[scope] = edges.toList()
            edges.forEach { add(it.input, it.scope) }
            active.remove(input)
            if (first) sourceOrder.add(input)
        }
    }
}
