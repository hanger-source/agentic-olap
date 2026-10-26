package com.olap.prototype.expression

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.RelVisitor
import org.apache.calcite.rel.core.*
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.*
import org.apache.calcite.schema.TransientTable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** 单条SQL的具名依赖；承接真实求值边界和重复关系，不推导关系等价或提升相关作用域。 */
internal data class SqlStatementRelations(val namedRelations: List<RelNode>, val parameterTypes: Map<Int, RelDataType>, val occupiedNames: Set<String>) {
    companion object {
        fun analyze(plan: RelNode, evaluationBoundaries: Set<RelNode>): SqlStatementRelations = Graph().run {
            visit(plan)
            SqlStatementRelations(namedRelations(evaluationBoundaries), parameters.types.toMap(), occupied.toSet())
        }
    }

    private class Graph {
        val parameters = ParameterTypes()
        val occupied = mutableSetOf<String>()
        private val uses = IdentityHashMap<RelNode, Int>()
        private val visited = Collections.newSetFromMap(IdentityHashMap<RelNode, Boolean>())
        private val order = mutableListOf<RelNode>()
        fun visit(node: RelNode) {
            if (!visited.add(node)) return
            node.accept(parameters)
            if (node is TableScan) requireNotNull(node.table) { "SQL table scan requires its bound table" }.qualifiedName.forEach { occupied.add(it.lowercase(Locale.ROOT)) }
            node.inputs.forEach { uses[it] = (uses[it] ?: 0) + 1; visit(it) }
            order.add(node)
        }
        fun namedRelations(evaluationBoundaries: Set<RelNode>): List<RelNode> {
            val stableWithinStatement = IdentityHashMap<RelNode, Boolean>()
            return buildList {
                order.forEach { node ->
                    val safe = statementStableExpressions(node) && node.inputs.all { stableWithinStatement[it] == true }
                    stableWithinStatement[node] = safe
                    if (safe && ((uses[node] ?: 0) >= 2 || node in evaluationBoundaries) && node !is TableScan && node !is Values) add(node)
                }
            }
        }
        private fun statementStableExpressions(node: RelNode): Boolean {
            if (node is Correlate || node is RepeatUnion || node is TableScan && requireNotNull(node.table) { "SQL table scan requires its bound table" }.unwrap(TransientTable::class.java) != null) return false
            val expressions = object : RexShuttle() {
                var safe = true
                override fun visitCall(call: RexCall): RexNode {
                    safe = safe && SemanticCalciteOperators.statementStable(call)
                    return super.visitCall(call)
                }
                override fun visitCorrelVariable(variable: RexCorrelVariable): RexNode { safe = false; return variable }
                override fun visitSubQuery(query: RexSubQuery): RexNode { safe = false; return query }
            }
            node.accept(expressions)
            return expressions.safe
        }
    }

    private class ParameterTypes : RexShuttle() {
        val types = linkedMapOf<Int, RelDataType>()
        override fun visitSubQuery(query: RexSubQuery): RexNode {
            val collector = this
            object : RelVisitor() {
                override fun visit(node: RelNode, ordinal: Int, parent: RelNode?) { node.accept(collector); super.visit(node, ordinal, parent) }
            }.go(query.rel)
            return super.visitSubQuery(query)
        }
        override fun visitDynamicParam(parameter: RexDynamicParam): RexNode {
            val prior = types.putIfAbsent(parameter.index, parameter.type)
            check(prior == null || prior == parameter.type) { "one SQL parameter index has conflicting compiled types" }
            return parameter
        }
    }
}
