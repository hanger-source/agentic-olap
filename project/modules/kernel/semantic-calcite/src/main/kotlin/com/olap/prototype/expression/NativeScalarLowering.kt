package com.olap.prototype.expression

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.*
import org.apache.calcite.sql.SqlAggFunction
import java.util.IdentityHashMap
import java.util.Collections

/** 原生纯标量在仍有类型的行作用域中展开，随后交给已有求值共享；不拥有关系规划。 */
internal class NativeScalarLowering {
    private val relations = IdentityHashMap<RelNode, RelNode>()

    fun lower(node: RelNode): RelNode = relations[node] ?: run {
        val inputs = node.inputs.map(::lower)
        val current = if (inputs.indices.all { inputs[it] === node.getInput(it) }) node else node.copy(node.traitSet, inputs)
        val expressions = IdentityHashMap<RexCall, RexNode>()
        current.accept(object : RexShuttle() {
            override fun visitCall(call: RexCall): RexNode = expressions[call] ?: run {
                val converted = super.visitCall(call) as RexCall
                val native = converted.operator as? NativeScalarOperator
                val result = if (native == null) converted else native.lower(node.cluster.rexBuilder, converted)
                if (result !== converted) validate(native!!.declaration, converted, result)
                result.also { expressions[call] = it }
            }

            override fun visitSubQuery(query: RexSubQuery): RexNode {
                val visited = super.visitSubQuery(query) as RexSubQuery
                val relation = lower(visited.rel)
                return if (relation === visited.rel) visited else visited.clone(relation)
            }
        }).also { relations[node] = it }
    }

    private fun validate(function: NativeScalarFunction, original: RexCall, result: RexNode) {
        require(result.type == original.type) {
            "native scalar ${function.id} expression changes its declared result type: ${original.type.fullTypeString} -> ${result.type.fullTypeString}"
        }
        require(SemanticCalciteOperators.statementStableExpression(original) && SemanticCalciteOperators.statementStableExpression(result)) {
            "native scalar ${function.id} expression expansion requires statement-stable function, arguments and scalar tree"
        }
        val arguments = references(original)
        require(references(result).all { it in arguments }) {
            "native scalar ${function.id} expression introduces an input or parameter outside its actual arguments"
        }
    }

    /** 精确参数／列引用来自原调用；整个相关字段作为一个引用，不能据其行变量读取另一个字段。 */
    private fun references(value: RexNode): Set<RexNode> = buildSet {
        val visited = Collections.newSetFromMap(IdentityHashMap<RexNode, Boolean>())
        fun visit(current: RexNode) {
            if (!visited.add(current)) return
            when (current) {
                is RexOver, is RexSubQuery -> error("native scalar expression cannot introduce a window or subquery")
                is RexCall -> {
                    require(current.operator !is SqlAggFunction) { "native scalar expression cannot introduce an aggregate" }
                    current.operands.forEach(::visit)
                }
                is RexFieldAccess -> if (current.referenceExpr is RexCorrelVariable) add(current) else visit(current.referenceExpr)
                is RexInputRef, is RexDynamicParam -> add(current)
                is RexLiteral -> Unit
                else -> error("native scalar expression has an unsupported reference: ${current.javaClass.simpleName}")
            }
        }
        visit(value)
    }
}
