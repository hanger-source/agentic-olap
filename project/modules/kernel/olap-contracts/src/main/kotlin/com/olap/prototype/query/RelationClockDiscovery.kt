package com.olap.prototype.query

import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import java.util.Collections
import java.util.TreeMap

/** 只展开明确路径的位置时钟身份，不搜索拓扑；声明与执行共用。 */
class RelationClockDiscovery(private val temporal: (RelationAccessPath.JoinRef) -> TemporalJoinContract.Constraint?) {
    private val discovered = TreeMap<String, TemporalJoinContract.QueryClock>()
    private val active = linkedSetOf<RelationAccessPath.JoinRef>()
    fun slots(): Map<String, TemporalJoinContract.QueryClock> = Collections.unmodifiableMap(TreeMap(discovered))
    fun path(role: String, start: List<RelationAccessPath.JoinRef>, path: RelationAccessPath): List<RelationAccessPath.JoinRef> {
        val position = start.toMutableList()
        path.joinPathRefs.forEach { ref ->
            require(active.add(ref)) { "cyclic clock access path: $active" }
            try {
                val source = position.toList()
                position.add(ref)
                temporal(ref)?.intervals?.forEachIndexed { index, interval ->
                    when (val clock = interval.sourceTime) {
                        is TemporalJoinContract.QueryClock -> {
                            val slot = TemporalClockBindings.slot(role, position, index)
                            val prior = discovered.putIfAbsent(slot, clock)
                            require(prior == null || prior == clock) { "one clock position has conflicting temporal meaning" }
                        }
                        is TemporalJoinContract.LogicUnitTime -> {
                            val access = RelationAccessPath(clock.joinPathRefs.map { RelationAccessPath.JoinRef(it.joinPathId, it.joinPathVersion) })
                            this.path("$role:clock:$index", if (clock is TemporalJoinContract.BackboneLogicUnitClock) emptyList() else source, access)
                        }
                    }
                }
            } finally { active.remove(ref) }
        }
        return java.util.List.copyOf(position)
    }
    fun expression(owner: String, local: RelationAccessPath, roles: Map<String, RelationAccessPath>, expressions: List<SemanticExpression>) {
        val at = path(owner, emptyList(), local)
        val used = sortedSetOf<String>()
        expressions.forEach { expression ->
            SemanticExpressionInspector.references(expression).filter { it.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT }.forEach { used.add(requireNotNull(it.role)) }
            SemanticExpressionInspector.quantifiers(expression).forEach { quantifier -> (quantifier.expression.from as? SemanticExpression.RoleParent)?.let { used.add(it.role) } }
        }
        used.forEach { role -> path("$owner:$role", emptyList(), requireNotNull(roles[role]) { "clock owner role is absent" }) }
        expressions.forEach { quantifiers(it, listOf(owner), at, roles) }
    }
    private fun quantifiers(expression: SemanticExpression, scope: List<String>, local: List<RelationAccessPath.JoinRef>, roles: Map<String, RelationAccessPath>) {
        SemanticExpressionInspector.quantifiers(expression).filter { it.parents.isEmpty() }.forEach { declaration ->
            val exists = declaration.expression
            val child = scope + exists.instanceId
            val parent = when (val from = exists.from) { is SemanticExpression.RoleParent -> requireNotNull(roles[from.role]) { "clock quantifier role is absent" }.joinPathRefs; is SemanticExpression.LocalParent -> local }
            val at = path(child.joinToString("/"), parent, exists.accessPath)
            quantifiers(exists.predicate, child, at, roles)
        }
    }
    companion object {
        fun relationSlots(sourceSlots: Map<String, String>, associationSlots: Collection<String>): Map<String, String> = Collections.unmodifiableMap(TreeMap(sourceSlots).apply {
            associationSlots.forEach { require(putIfAbsent(it, it) == null) { "association clock conflicts with a source clock name" } }
        })
    }
}
